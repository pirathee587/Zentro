package com.zentro.notificationintegration.notification.service;

import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters: counters zentro.notifications.sent / failed / retried / skipped / duplicates / rejected and
 * gauges zentro.notifications.pending (PENDING + RETRY rows) and zentro.notifications.failed.total (FAILED rows).
 */
@Component
public class NotificationMetrics {
  public static final String SENT = "zentro.notifications.sent";
  public static final String FAILED = "zentro.notifications.failed";
  public static final String RETRIED = "zentro.notifications.retried";
  public static final String SKIPPED = "zentro.notifications.skipped";
  public static final String DUPLICATES = "zentro.notifications.duplicates";
  public static final String REJECTED = "zentro.notifications.rejected";
  public static final String PENDING_GAUGE = "zentro.notifications.pending";
  public static final String FAILED_GAUGE = "zentro.notifications.failed.total";

  private final Counter sent;
  private final Counter failed;
  private final Counter retried;
  private final Counter skipped;
  private final Counter duplicates;
  private final Counter rejected;

  public NotificationMetrics(MeterRegistry registry, NotificationRepository repository) {
    sent = registry.counter(SENT);
    failed = registry.counter(FAILED);
    retried = registry.counter(RETRIED);
    skipped = registry.counter(SKIPPED);
    duplicates = registry.counter(DUPLICATES);
    rejected = registry.counter(REJECTED);
    Gauge.builder(PENDING_GAUGE, () -> safe(repository::countOpen))
        .description("Notifications waiting to be sent").register(registry);
    Gauge.builder(FAILED_GAUGE, () -> safe(repository::countFailed))
        .description("Notifications that failed after all attempts").register(registry);
  }

  public void sent() {
    sent.increment();
  }

  public void failed() {
    failed.increment();
  }

  public void retried() {
    retried.increment();
  }

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
