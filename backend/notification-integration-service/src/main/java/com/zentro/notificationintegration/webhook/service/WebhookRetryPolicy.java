package com.zentro.notificationintegration.webhook.service;

import com.zentro.notificationintegration.webhook.client.WebhookSendResult;
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Decides what happens after one attempt:
 * <ul>
 *   <li>2xx: SUCCEEDED;</li>
 *   <li>408, 429, 5xx, timeout or network error: RETRY after base * 2^(attempt - 1) (capped), or FAILED once
 *       {@code max-attempts} is reached;</li>
 *   <li>any other status (other 4xx, and 1xx/3xx because redirects are not followed): FAILED at once.</li>
 * </ul>
 */
@Component
public class WebhookRetryPolicy {

  public enum Decision {
    SUCCEEDED,
    RETRY,
    FAILED
  }

  /**
   * Outcome of classifying an attempt.
   *
   * @param decision outcome decision (SUCCEEDED, RETRY, FAILED)
   * @param responseClass high-level classification of the response/attempt
   * @param delay until the next attempt; only for RETRY
   * @param error short description recorded with the attempt; null for SUCCEEDED
   */
  public record Outcome(Decision decision, WebhookResponseClass responseClass, Duration delay, String error) {
    public Outcome(Decision decision, Duration delay, String error) {
      this(decision,
          decision == Decision.SUCCEEDED ? WebhookResponseClass.SUCCESSFUL
              : (decision == Decision.RETRY ? WebhookResponseClass.RETRYABLE_FAILURE
                  : WebhookResponseClass.NON_RETRYABLE_FAILURE),
          delay, error);
    }
  }

  private final WebhookProperties.Retry retry;

  public WebhookRetryPolicy(WebhookProperties properties) {
    this.retry = properties.retry();
  }

  public Outcome classify(WebhookSendResult result, int attempt) {
    if (result.hasResponse()) {
      int status = result.status();
      if (status >= 200 && status < 300) {
        return new Outcome(Decision.SUCCEEDED, WebhookResponseClass.SUCCESSFUL, null, null);
      }
      String error = "HTTP " + status;
      boolean retryable = isRetryable(status);
      WebhookResponseClass responseClass = retryable
          ? WebhookResponseClass.RETRYABLE_FAILURE
          : WebhookResponseClass.NON_RETRYABLE_FAILURE;
      return retryable ? retryOrFail(error, attempt, responseClass) : new Outcome(Decision.FAILED, responseClass, null, error);
    }
    String error = result.error() == null ? "network error" : result.error();
    return retryOrFail(error, attempt, WebhookResponseClass.RETRYABLE_FAILURE);
  }

  public int maxAttempts() {
    return retry.maxAttempts();
  }

  public static boolean isRetryable(int status) {
    return status == 408 || status == 429 || (status >= 500 && status < 600);
  }

  private Outcome retryOrFail(String error, int attempt, WebhookResponseClass responseClass) {
    if (attempt >= retry.maxAttempts()) {
      return new Outcome(Decision.FAILED, responseClass, null, error + " (gave up after " + attempt + " attempts)");
    }
    return new Outcome(Decision.RETRY, responseClass, retry.delayAfter(attempt), error);
  }

  /**
   * Derives the high-level response classification from the recorded attempt status and error.
   *
   * @param status HTTP response status code, or null if no HTTP response was received
   * @param error recorded last error string, or null
   * @return response classification, or null if the delivery has not been attempted yet
   */
  public static WebhookResponseClass classifyResponse(Integer status, String error) {
    if (status != null) {
      if (status >= 200 && status < 300) {
        return WebhookResponseClass.SUCCESSFUL;
      }
      if (isRetryable(status)) {
        return WebhookResponseClass.RETRYABLE_FAILURE;
      }
      return WebhookResponseClass.NON_RETRYABLE_FAILURE;
    }
    if (error == null) {
      return null;
    }
    String err = error.toLowerCase(Locale.ROOT);
    if (err.startsWith("http 408") || err.startsWith("http 429") || err.startsWith("http 5")
        || err.contains("timeout") || err.contains("network error") || err.contains("connectexception")
        || err.contains("host cannot be resolved")) {
      return WebhookResponseClass.RETRYABLE_FAILURE;
    }
    return WebhookResponseClass.NON_RETRYABLE_FAILURE;
  }

  /**
   * Maps a terminal delivery failure to its specific root category.
   */
  public static WebhookTerminalCategory classifyTerminalFailure(Integer status, String error, int attempts, int maxAttempts) {
    if (error != null) {
      if (error.contains("gave up after") || (attempts >= maxAttempts && attempts > 0)) {
        return WebhookTerminalCategory.EXHAUSTED_RETRIES;
      }
      if (error.equals(WebhookDeliveryService.DISABLED_ERROR)) {
        return WebhookTerminalCategory.SUBSCRIPTION_DISABLED;
      }
      if (error.startsWith("Endpoint not allowed")) {
        return WebhookTerminalCategory.ENDPOINT_NOT_ALLOWED;
      }
      if (error.equals("Signing secret unavailable")) {
        return WebhookTerminalCategory.SECRET_UNAVAILABLE;
      }
    }
    return WebhookTerminalCategory.NON_RETRYABLE_STATUS;
  }
}
