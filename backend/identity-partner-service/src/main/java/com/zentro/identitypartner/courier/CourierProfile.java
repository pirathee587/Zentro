package com.zentro.identitypartner.courier;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Representation of a courier profile.
 * - active: Approved eligibility flag (courier profile active AND user account active).
 * - assignedZone, vehicleType: Proposed fields pending cross-service S3 contract approval.
 */
public record CourierProfile(
    UUID id,
    UUID userId,
    String email,
    String name,
    String phone,
    boolean active,
    String accountStatus,
    String assignedZone,
    String vehicleType,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
