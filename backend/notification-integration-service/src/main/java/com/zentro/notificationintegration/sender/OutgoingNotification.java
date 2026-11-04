package com.zentro.notificationintegration.sender;

import java.util.UUID;

/**
 * One notification to send. Addressed by tracking reference only: parcel events carry no contact details.
 *
 * @param attemptNumber 1 for the first try, 2 for the first retry, and so on
 */
public record OutgoingNotification(
    UUID eventId,
    String trackingRef,
    String parcelStatus,
    String channel,
    String message,
    int attemptNumber
) {}
