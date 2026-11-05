package com.zentro.trackingreporting.tracking.dto;

import java.time.Instant;
import java.util.List;

/**
 * Public tracking response view. Excludes all private/sensitive customer personal data
 * such as full names, phone numbers, full street addresses, fees, and internal notes.
 */
public record PublicTrackingResponse(
    String trackingRef,
    String status,
    String rawStatus,
    String category,
    String size,
    String description,
    String originCity,
    String destinationCity,
    int estimatedDays,
    Instant estimatedDeliveryDate,
    Instant createdAt,
    Instant deliveredAt,
    String failureReason,
    List<TrackingEventDto> events,
    List<TrackingMilestoneDto> timeline
) {}

