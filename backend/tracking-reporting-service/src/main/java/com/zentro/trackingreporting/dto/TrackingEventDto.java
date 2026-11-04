package com.zentro.trackingreporting.dto;

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

