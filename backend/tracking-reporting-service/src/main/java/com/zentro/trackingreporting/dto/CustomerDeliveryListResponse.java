package com.zentro.trackingreporting.dto;

import java.math.BigDecimal;
import java.util.List;

public record CustomerDeliveryListResponse(
    List<CustomerDeliveryResponse> content,
    List<CustomerDeliveryResponse> parcels,
    long totalElements,
    long total,
    int totalPages,
    int page,
    int size,
    BigDecimal totalSpent,
    String formattedTotalSpent
) {}

