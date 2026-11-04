package com.zentro.identitypartner.courier;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for dispatcher updates to a courier profile.
 * Note: assignedZone and vehicleType are proposed fields pending S3 contract approval.
 * Account status management is handled via AdminUserController to ensure centralized audit control.
 */
public record UpdateCourierProfileRequest(
    Boolean active,

    // Proposed field pending S3 contract approval
    @Size(max = 64, message = "Assigned zone must not exceed 64 characters")
    String assignedZone,

    // Proposed field pending S3 contract approval
    @Pattern(regexp = "^(BICYCLE|MOTORCYCLE|CAR|VAN|TRUCK)$", message = "Vehicle type must be BICYCLE, MOTORCYCLE, CAR, VAN, or TRUCK")
    String vehicleType,

    @Size(max = 200, message = "Name must not exceed 200 characters")
    String name,

    @Size(max = 32, message = "Phone must not exceed 32 characters")
    String phone) {}
