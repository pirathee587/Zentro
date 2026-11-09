package com.zentro.notificationintegration.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttemptHistoryResponse(
    List<NotificationAttempt> notifications,
    List<WebhookAttempt> webhooks,
    int page,
    int size,
    long totalNotifications,
    long totalWebhooks) {
  public record NotificationAttempt(
      UUID id,
      UUID eventId,
      UUID parcelId,
      String eventType,
      String trackingRef,
      String parcelStatus,
      String channel,
      String status,
      int attempts,
      String lastError,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record WebhookAttempt(
      UUID id,
      UUID eventId,
      UUID parcelId,
      String eventType,
      String status,
      int attempts,
      Integer responseStatus,
      String responseClass,
      String lastError,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    public WebhookAttempt(
        UUID id,
        UUID eventId,
        UUID parcelId,
        String eventType,
        String status,
        int attempts,
        Integer responseStatus,
        String lastError,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
      this(id, eventId, parcelId, eventType, status, attempts, responseStatus, null, lastError, nextAttemptAt, createdAt, updatedAt);
    }
  }
}
