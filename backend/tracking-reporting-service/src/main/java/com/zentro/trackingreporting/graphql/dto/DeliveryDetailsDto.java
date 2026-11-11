package com.zentro.trackingreporting.graphql.dto;

import com.zentro.trackingreporting.tracking.dto.PartyDto;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DeliveryDetailsDto(
    UUID id,
    String trackingRef,
    TrackingStatus status,
    String rawStatus,
    String category,
    String size,
    String description,
    String destinationType,
    boolean isExpress,
    PartyDto sender,
    PartyDto recipient,
    BigDecimal fee,
    String currency,
    String formattedFee,
    int estimatedDays,
    String createdAt,
    String deliveredAt,
    String failureReason,
    List<TrackingEventDto> events
) {}

