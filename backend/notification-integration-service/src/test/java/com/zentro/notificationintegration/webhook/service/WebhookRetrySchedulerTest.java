package com.zentro.notificationintegration.webhook.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebhookRetrySchedulerTest {
  private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");

  @Test
  @DisplayName("The retry scheduler queries due rows using the application clock")
  void usesApplicationClockForDueTime() {
    WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
    WebhookDeliveryService service = mock(WebhookDeliveryService.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    UUID deliveryId = UUID.randomUUID();
    when(repository.findDue(WebhookRetryScheduler.BATCH_SIZE, now)).thenReturn(List.of(deliveryId));

    new WebhookRetryScheduler(repository, service, clock).retryDue();

    verify(repository).findDue(WebhookRetryScheduler.BATCH_SIZE, now);
    verify(service).attempt(deliveryId);
  }
}
