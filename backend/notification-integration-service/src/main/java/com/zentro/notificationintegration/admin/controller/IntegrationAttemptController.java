package com.zentro.notificationintegration.admin.controller;

import com.zentro.notificationintegration.admin.dto.AttemptHistoryResponse;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.webhook.entity.WebhookDelivery;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository.ReplayResult;
import com.zentro.notificationintegration.webhook.service.WebhookReplayService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('DISPATCHER')")
public class IntegrationAttemptController {
  private static final int MAX_SIZE = 100;

  private final NotificationRepository notifications;
  private final WebhookDeliveryRepository webhooks;
  private final WebhookReplayService replayService;

  public IntegrationAttemptController(NotificationRepository notifications, WebhookDeliveryRepository webhooks,
      WebhookReplayService replayService) {
    this.notifications = notifications;
    this.webhooks = webhooks;
    this.replayService = replayService;
  }

  @GetMapping("/notification-attempts")
  public AttemptHistoryResponse notifications(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return history(page, size);
  }

  @GetMapping("/webhook-deliveries")
  public AttemptHistoryResponse webhooks(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return history(page, size);
  }

  @PostMapping("/webhook-deliveries/{deliveryId}/replay")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void replay(@PathVariable("deliveryId") UUID deliveryId, Authentication authentication) {
    ReplayResult result = replayService.replay(deliveryId, authentication.getName());
    if (result != ReplayResult.REPLAYED) {
      throw new ResponseStatusException(result == ReplayResult.NOT_FOUND
          ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT, "Webhook delivery cannot be replayed: " + result);
    }
  }

  private AttemptHistoryResponse history(int page, int size) {
    if (page < 0 || size < 1 || size > MAX_SIZE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be non-negative and size must be 1-100.");
    }
    long offset = (long) page * size;
    List<AttemptHistoryResponse.NotificationAttempt> notificationRows = notifications.listHistory(size, offset).stream()
        .map(row -> new AttemptHistoryResponse.NotificationAttempt(row.id(), row.eventId(), row.parcelId(),
            row.eventType(), row.trackingRef(), row.parcelStatus(), row.channel(), row.status(), row.attempts(),
            row.lastError(), row.nextAttemptAt(), row.createdAt(), row.updatedAt()))
        .toList();
    List<AttemptHistoryResponse.WebhookAttempt> webhookRows = webhooks.listHistory(size, offset).stream()
        .map(row -> new AttemptHistoryResponse.WebhookAttempt(row.id(), row.eventId(), row.parcelId(),
            row.eventType(), row.status().name(), row.attempts(), row.responseStatus(), row.responseClass(),
            row.lastError(), row.nextAttemptAt(), row.createdAt(), row.updatedAt()))
        .toList();
    return new AttemptHistoryResponse(notificationRows, webhookRows, page, size,
        notifications.countHistory(), webhooks.countHistory());
  }
}
