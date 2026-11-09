package com.zentro.notificationintegration.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.notificationintegration.webhook.client.WebhookSendResult;
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.service.WebhookRetryPolicy.Decision;
import com.zentro.notificationintegration.webhook.service.WebhookRetryPolicy.Outcome;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookRetryPolicyTest {
  private final WebhookRetryPolicy policy = new WebhookRetryPolicy(new WebhookProperties(false, false, "",
      "merchant-webhooks", new WebhookProperties.Retry(8, 5000, 10000, 1800000),
      new WebhookProperties.Http(2000, 5000), new WebhookProperties.Health(60, 10)));

  @ParameterizedTest(name = "HTTP {0} -> SUCCEEDED, SUCCESSFUL")
  @ValueSource(ints = {200, 201, 202, 204, 299})
  @DisplayName("Any 2xx is a success with SUCCESSFUL response class")
  void successStatuses(int status) {
    Outcome outcome = policy.classify(WebhookSendResult.response(status), 1);
    assertThat(outcome.decision()).isEqualTo(Decision.SUCCEEDED);
    assertThat(outcome.responseClass()).isEqualTo(WebhookResponseClass.SUCCESSFUL);
    assertThat(outcome.error()).isNull();
  }

  @ParameterizedTest(name = "HTTP {0} -> RETRY, RETRYABLE_FAILURE")
  @ValueSource(ints = {408, 429, 500, 502, 503, 504, 599})
  @DisplayName("408, 429 and 5xx are retried with RETRYABLE_FAILURE response class")
  void retryableStatuses(int status) {
    Outcome outcome = policy.classify(WebhookSendResult.response(status), 1);
    assertThat(outcome.decision()).isEqualTo(Decision.RETRY);
    assertThat(outcome.responseClass()).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(outcome.error()).isEqualTo("HTTP " + status);
  }

  @ParameterizedTest(name = "HTTP {0} -> FAILED, NON_RETRYABLE_FAILURE")
  @ValueSource(ints = {400, 401, 403, 404, 405, 410, 413, 422, 301, 302, 307, 100})
  @DisplayName("Other 4xx and redirects fail permanently with NON_RETRYABLE_FAILURE response class")
  void permanentStatuses(int status) {
    Outcome outcome = policy.classify(WebhookSendResult.response(status), 1);
    assertThat(outcome.decision()).isEqualTo(Decision.FAILED);
    assertThat(outcome.responseClass()).isEqualTo(WebhookResponseClass.NON_RETRYABLE_FAILURE);
    assertThat(outcome.error()).isEqualTo("HTTP " + status);
  }

  @Test
  @DisplayName("Timeouts and network errors are retried with RETRYABLE_FAILURE response class")
  void networkProblemsAreRetried() {
    Outcome timeout = policy.classify(WebhookSendResult.timeout(), 1);
    assertThat(timeout.decision()).isEqualTo(Decision.RETRY);
    assertThat(timeout.responseClass()).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(timeout.error()).isEqualTo("timeout");

    Outcome network = policy.classify(WebhookSendResult.networkError("network error: ConnectException"), 2);
    assertThat(network.decision()).isEqualTo(Decision.RETRY);
    assertThat(network.responseClass()).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(network.error()).isEqualTo("network error: ConnectException");
  }

  @Test
  @DisplayName("Back-off is 10 s * 2^(attempt - 1), capped at 30 minutes")
  void backOff() {
    assertThat(policy.classify(WebhookSendResult.timeout(), 1).delay()).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.classify(WebhookSendResult.timeout(), 2).delay()).isEqualTo(Duration.ofSeconds(20));
    assertThat(policy.classify(WebhookSendResult.timeout(), 3).delay()).isEqualTo(Duration.ofSeconds(40));
    assertThat(policy.classify(WebhookSendResult.timeout(), 7).delay()).isEqualTo(Duration.ofSeconds(640));
    WebhookProperties.Retry retry = new WebhookProperties.Retry(50, 5000, 10000, 1800000);
    assertThat(retry.delayAfter(20)).isEqualTo(Duration.ofMinutes(30));
    assertThat(retry.delayAfter(60)).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  @DisplayName("The last allowed attempt turns a retryable failure into FAILED while retaining response class")
  void maxAttempts() {
    Outcome last = policy.classify(WebhookSendResult.response(503), 8);
    assertThat(last.decision()).isEqualTo(Decision.FAILED);
    assertThat(last.responseClass()).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(last.error()).isEqualTo("HTTP 503 (gave up after 8 attempts)");
    assertThat(policy.classify(WebhookSendResult.response(503), 7).decision()).isEqualTo(Decision.RETRY);
    assertThat(policy.maxAttempts()).isEqualTo(8);
  }

  @Test
  @DisplayName("Static classifyResponse accurately categorizes status and error inputs")
  void staticResponseClassification() {
    assertThat(WebhookRetryPolicy.classifyResponse(200, null)).isEqualTo(WebhookResponseClass.SUCCESSFUL);
    assertThat(WebhookRetryPolicy.classifyResponse(204, null)).isEqualTo(WebhookResponseClass.SUCCESSFUL);
    assertThat(WebhookRetryPolicy.classifyResponse(503, "HTTP 503")).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(429, "HTTP 429")).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(null, "timeout")).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(null, "network error: ConnectException")).isEqualTo(WebhookResponseClass.RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(400, "HTTP 400")).isEqualTo(WebhookResponseClass.NON_RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(404, "HTTP 404")).isEqualTo(WebhookResponseClass.NON_RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(null, "Endpoint not allowed: private IP")).isEqualTo(WebhookResponseClass.NON_RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(null, "Signing secret unavailable")).isEqualTo(WebhookResponseClass.NON_RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(null, "Subscription disabled")).isEqualTo(WebhookResponseClass.NON_RETRYABLE_FAILURE);
    assertThat(WebhookRetryPolicy.classifyResponse(null, null)).isNull();
  }

  @Test
  @DisplayName("Static classifyTerminalFailure accurately maps terminal errors to categories")
  void staticTerminalFailureClassification() {
    assertThat(WebhookRetryPolicy.classifyTerminalFailure(503, "HTTP 503 (gave up after 8 attempts)", 8, 8))
        .isEqualTo(WebhookTerminalCategory.EXHAUSTED_RETRIES);
    assertThat(WebhookRetryPolicy.classifyTerminalFailure(null, "timeout (gave up after 8 attempts)", 8, 8))
        .isEqualTo(WebhookTerminalCategory.EXHAUSTED_RETRIES);
    assertThat(WebhookRetryPolicy.classifyTerminalFailure(400, "HTTP 400", 1, 8))
        .isEqualTo(WebhookTerminalCategory.NON_RETRYABLE_STATUS);
    assertThat(WebhookRetryPolicy.classifyTerminalFailure(301, "HTTP 301", 1, 8))
        .isEqualTo(WebhookTerminalCategory.NON_RETRYABLE_STATUS);
    assertThat(WebhookRetryPolicy.classifyTerminalFailure(null, "Endpoint not allowed: loopback", 1, 8))
        .isEqualTo(WebhookTerminalCategory.ENDPOINT_NOT_ALLOWED);
    assertThat(WebhookRetryPolicy.classifyTerminalFailure(null, "Subscription disabled", 0, 8))
        .isEqualTo(WebhookTerminalCategory.SUBSCRIPTION_DISABLED);
    assertThat(WebhookRetryPolicy.classifyTerminalFailure(null, "Signing secret unavailable", 1, 8))
        .isEqualTo(WebhookTerminalCategory.SECRET_UNAVAILABLE);
  }
}
