package com.zentro.trackingreporting.tracking.dto;

import java.time.Instant;
import java.util.UUID;

public record TrackingEventDto(
    UUID id,
    String trackingRef,
    String status,
    String title,
    String description,
    String actorRole,
    String location,
    Instant occurredAt,
    String failureReason
) {}

