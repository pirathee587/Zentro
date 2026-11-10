package com.zentro.identitypartner.courier.dto;


import com.zentro.identitypartner.user.controller.AdminUserController;
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

    @Pattern(regexp = "^(\\+?[0-9]{9,15})?$", message = "Phone must be a valid phone number (9-15 digits, optional leading +)")
    @Size(max = 32, message = "Phone must not exceed 32 characters")
    String phone) {}
