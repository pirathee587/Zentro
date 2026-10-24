package com.zentro.identitypartner.merchant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMerchantApplicationRequest(
    @NotBlank @Size(max = 140) String businessName,
    @Size(max = 80) String businessRegNo,
    @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}") String contactPhone) {}
