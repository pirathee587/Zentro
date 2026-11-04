package com.zentro.trackingreporting.dto;

import java.math.BigDecimal;

public record ParcelDetailsDto(
    String description,
    BigDecimal weightKg,
    String category,
    String size
) {}

