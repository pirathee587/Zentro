package com.zentro.trackingreporting.graphql.dto;

import java.math.BigDecimal;
import java.util.List;

public record CustomerDashboardSummary(long activeCount, long deliveredCount, long awaitingPickupCount,
    long failedCount, long inTransitCount, long totalParcels, String month, String timeZone,
    List<CurrencyAmount> totalFees, List<CurrencyAmount> monthlyFees) {
  public record CurrencyAmount(String currency, BigDecimal amount) { }
}
