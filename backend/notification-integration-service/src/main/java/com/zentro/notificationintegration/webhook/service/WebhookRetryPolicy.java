package com.zentro.notificationintegration.webhook.service;

import com.zentro.notificationintegration.webhook.client.WebhookSendResult;
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import java.time.Duration;
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
   * @param delay until the next attempt; only for RETRY
   * @param error short description recorded with the attempt; null for SUCCEEDED
   */
  public record Outcome(Decision decision, Duration delay, String error) {}

  private final WebhookProperties.Retry retry;

  public WebhookRetryPolicy(WebhookProperties properties) {
    this.retry = properties.retry();
  }

  public Outcome classify(WebhookSendResult result, int attempt) {
    if (result.hasResponse()) {
      int status = result.status();
      if (status >= 200 && status < 300) {
        return new Outcome(Decision.SUCCEEDED, null, null);
      }
      String error = "HTTP " + status;
      return isRetryable(status) ? retryOrFail(error, attempt) : new Outcome(Decision.FAILED, null, error);
    }
    return retryOrFail(result.error() == null ? "network error" : result.error(), attempt);
  }

  public int maxAttempts() {
    return retry.maxAttempts();
  }

  static boolean isRetryable(int status) {
    return status == 408 || status == 429 || (status >= 500 && status < 600);
  }

  private Outcome retryOrFail(String error, int attempt) {
    if (attempt >= retry.maxAttempts()) {
      return new Outcome(Decision.FAILED, null, error + " (gave up after " + attempt + " attempts)");
    }
    return new Outcome(Decision.RETRY, retry.delayAfter(attempt), error);
  }
}
