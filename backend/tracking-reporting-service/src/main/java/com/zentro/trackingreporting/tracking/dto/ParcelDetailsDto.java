package com.zentro.trackingreporting.tracking.dto;

import java.math.BigDecimal;

public record ParcelDetailsDto(
    String description,
    BigDecimal weightKg,
    String category,
    String size
) {}

