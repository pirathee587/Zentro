package com.zentro.trackingreporting.dto;

import java.time.Instant;

public record TrackingMilestoneDto(
    String id,
    String title,
    String description,
    Instant timestamp,
    String status,
    String location,
    String failureReason
) {}

