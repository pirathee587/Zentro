package com.zentro.trackingreporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CustomerDeliveryResponse(
    String id,
    String trackingRef,
    String status,
    String rawStatus,
    PartyDto sender,
    PartyDto recipient,
    ParcelDetailsDto parcel,
    BigDecimal fee,
    String currency,
    String formattedFee,
    int estimatedDays,
    Instant createdAt,
    String formattedCreatedAt,
    Instant deliveredAt,
    String formattedDeliveredAt,
    String failureReason,
    List<TrackingMilestoneDto> timeline
) {}

