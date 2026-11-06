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

  @ParameterizedTest(name = "HTTP {0} -> SUCCEEDED")
  @ValueSource(ints = {200, 201, 202, 204, 299})
  @DisplayName("Any 2xx is a success")
  void successStatuses(int status) {
    Outcome outcome = policy.classify(WebhookSendResult.response(status), 1);
    assertThat(outcome.decision()).isEqualTo(Decision.SUCCEEDED);
    assertThat(outcome.error()).isNull();
  }

  @ParameterizedTest(name = "HTTP {0} -> RETRY")
  @ValueSource(ints = {408, 429, 500, 502, 503, 504, 599})
  @DisplayName("408, 429 and 5xx are retried")
  void retryableStatuses(int status) {
    Outcome outcome = policy.classify(WebhookSendResult.response(status), 1);
    assertThat(outcome.decision()).isEqualTo(Decision.RETRY);
    assertThat(outcome.error()).isEqualTo("HTTP " + status);
  }

  @ParameterizedTest(name = "HTTP {0} -> FAILED")
  @ValueSource(ints = {400, 401, 403, 404, 405, 410, 413, 422, 301, 302, 307, 100})
  @DisplayName("Other 4xx (and redirects, which are not followed) fail permanently at the first attempt")
  void permanentStatuses(int status) {
    Outcome outcome = policy.classify(WebhookSendResult.response(status), 1);
    assertThat(outcome.decision()).isEqualTo(Decision.FAILED);
    assertThat(outcome.error()).isEqualTo("HTTP " + status);
  }

  @Test
  @DisplayName("Timeouts and network errors are retried with their category recorded")
  void networkProblemsAreRetried() {
    assertThat(policy.classify(WebhookSendResult.timeout(), 1))
        .extracting(Outcome::decision, Outcome::error).containsExactly(Decision.RETRY, "timeout");
    assertThat(policy.classify(WebhookSendResult.networkError("network error: ConnectException"), 2))
        .extracting(Outcome::decision, Outcome::error)
        .containsExactly(Decision.RETRY, "network error: ConnectException");
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
  @DisplayName("The last allowed attempt turns a retryable failure into FAILED")
  void maxAttempts() {
    Outcome last = policy.classify(WebhookSendResult.response(503), 8);
    assertThat(last.decision()).isEqualTo(Decision.FAILED);
    assertThat(last.error()).isEqualTo("HTTP 503 (gave up after 8 attempts)");
    assertThat(policy.classify(WebhookSendResult.response(503), 7).decision()).isEqualTo(Decision.RETRY);
    assertThat(policy.maxAttempts()).isEqualTo(8);
  }
}
