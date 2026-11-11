package com.zentro.trackingreporting.graphql.dto;

import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import java.util.List;

public record PublicTrackingParcelDto(
    String trackingRef,
    TrackingStatus status,
    String rawStatus,
    String category,
    String size,
    String description,
    String originCity,
    String destinationCity,
    int estimatedDays,
    String estimatedDeliveryDate,
    String createdAt,
    String deliveredAt,
    String failureReason,
    List<TrackingEventDto> events
) {}

