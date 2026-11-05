package com.zentro.identitypartner.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
    @Size(max = 200) String name,
    @Size(max = 32) String phone) {}
