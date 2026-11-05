package com.zentro.parcelpricing.pricing.rules;

import java.util.UUID;

public record DeliveryZone(
    UUID id,
    String code,
    String name,
    String description,
    boolean active
) {}
