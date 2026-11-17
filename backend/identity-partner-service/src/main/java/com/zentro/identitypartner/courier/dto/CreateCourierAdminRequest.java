package com.zentro.identitypartner.courier.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCourierAdminRequest(
    @NotBlank(message = "Courier name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    String name,

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    String email,

    String phone,

    String assignedZone,

    String vehicleType
) {}
