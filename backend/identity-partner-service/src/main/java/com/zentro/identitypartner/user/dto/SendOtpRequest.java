package com.zentro.identitypartner.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendOtpRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    String email,

    String name,

    String entryPoint
) {}
