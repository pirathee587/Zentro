package com.zentro.notificationintegration.messaging.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A validated parcel event from parcel.created or parcel.status.updated.
 * Contains only what notifications need; the payloads never carry personal data.
 *
 * @param failureReason present only when {@code status} is FAILED
 */
public record ParcelEvent(
    UUID eventId,
    String eventType,
    UUID parcelId,
    OffsetDateTime occurredAt,
    String trackingRef,
    String status,
    String failureReason
) {}
