package com.zentro.notificationintegration.webhook.service;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters: counters zentro.webhooks.succeeded / failed / retried / skipped / duplicates / rejected and
 * gauges zentro.webhooks.pending (PENDING + RETRY rows) and zentro.webhooks.failed.total (FAILED rows, not
 * counting deliveries closed because the merchant disabled the endpoint).
 */
@Component
public class WebhookMetrics {
  public static final String SUCCEEDED = "zentro.webhooks.succeeded";
  public static final String FAILED = "zentro.webhooks.failed";
  public static final String RETRIED = "zentro.webhooks.retried";
  public static final String SKIPPED = "zentro.webhooks.skipped";
  public static final String DUPLICATES = "zentro.webhooks.duplicates";
  public static final String REJECTED = "zentro.webhooks.rejected";
  public static final String PENDING_GAUGE = "zentro.webhooks.pending";
  public static final String FAILED_GAUGE = "zentro.webhooks.failed.total";

  private final Counter succeeded;
  private final Counter failed;
  private final Counter retried;
  private final Counter skipped;
  private final Counter duplicates;
  private final Counter rejected;

  public WebhookMetrics(MeterRegistry registry, WebhookDeliveryRepository repository) {
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

  /** A gauge must never throw; report "unknown" when the database cannot be read. */
  private static double safe(LongSupplier count) {
    try {
      return count.getAsLong();
    } catch (RuntimeException ex) {
      return Double.NaN;
    }
  }
}
