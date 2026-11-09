package com.zentro.notificationintegration.webhook.service;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters:
 * <ul>
 *   <li>Counters {@code zentro.webhooks.succeeded / failed / retried / skipped / duplicates / rejected}</li>
 *   <li>Counter {@code zentro.webhooks.attempts} with low-cardinality tags {@code response_class} and {@code outcome}</li>
 *   <li>Counter {@code zentro.webhooks.terminal} with low-cardinality tag {@code category}</li>
 *   <li>Gauges {@code zentro.webhooks.pending} (PENDING + RETRY rows) and {@code zentro.webhooks.failed.total}</li>
 * </ul>
 */
@Component
public class WebhookMetrics {
  public static final String SUCCEEDED = "zentro.webhooks.succeeded";
  public static final String FAILED = "zentro.webhooks.failed";
  public static final String RETRIED = "zentro.webhooks.retried";
  public static final String SKIPPED = "zentro.webhooks.skipped";
  public static final String DUPLICATES = "zentro.webhooks.duplicates";
  public static final String REJECTED = "zentro.webhooks.rejected";
  public static final String ATTEMPTS = "zentro.webhooks.attempts";
  public static final String TERMINAL = "zentro.webhooks.terminal";
  public static final String PENDING_GAUGE = "zentro.webhooks.pending";
  public static final String FAILED_GAUGE = "zentro.webhooks.failed.total";

  private final MeterRegistry registry;
  private final Counter succeeded;
  private final Counter failed;
  private final Counter retried;
  private final Counter skipped;
  private final Counter duplicates;
  private final Counter rejected;

  public WebhookMetrics(MeterRegistry registry, WebhookDeliveryRepository repository) {
    this.registry = registry;
    succeeded = registry.counter(SUCCEEDED);
    failed = registry.counter(FAILED);
    retried = registry.counter(RETRIED);
    skipped = registry.counter(SKIPPED);
    duplicates = registry.counter(DUPLICATES);
    rejected = registry.counter(REJECTED);
    Gauge.builder(PENDING_GAUGE, () -> safe(repository::countOpen))
        .description("Webhook deliveries waiting to be sent").register(registry);
    Gauge.builder(FAILED_GAUGE, () -> safe(() -> repository.countFailed(WebhookDeliveryService.DISABLED_ERROR)))
        .description("Webhook deliveries that failed permanently").register(registry);
  }

  public void succeeded() {
    succeeded.increment();
  }

  public void failed() {
    failed.increment();
  }

  public void retried() {
    retried.increment();
  }

  /** An event with no merchant, no active endpoint, or for a disabled endpoint. */
  public void skipped() {
    skipped.increment();
  }

  public void duplicate() {
    duplicates.increment();
  }

  public void rejected() {
    rejected.increment();
  }

  /**
   * Records an individual delivery attempt with bounded, low-cardinality classification tags.
   */
  public void recordAttempt(WebhookResponseClass responseClass, WebhookRetryPolicy.Decision outcome) {
    String rcTag = responseClass == null ? "unknown" : responseClass.name().toLowerCase(Locale.ROOT);
    String outcomeTag = outcome == null ? "unknown"
        : (outcome == WebhookRetryPolicy.Decision.RETRY ? "retried" : outcome.name().toLowerCase(Locale.ROOT));
    registry.counter(ATTEMPTS, "response_class", rcTag, "outcome", outcomeTag).increment();
  }

  /**
   * Records a terminal failure categorized by its failure path with bounded, low-cardinality tags.
   */
  public void recordTerminalFailure(WebhookTerminalCategory category) {
    String catTag = category == null ? "unknown" : category.name().toLowerCase(Locale.ROOT);
    registry.counter(TERMINAL, "category", catTag).increment();
  }

  /** A gauge must never throw; report "unknown" when the database cannot be read. */
  private static double safe(LongSupplier count) {
    try {
      return count.getAsLong();
    } catch (RuntimeException ex) {
      return Double.NaN;
    }
  }
}
