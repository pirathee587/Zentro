package com.zentro.trackingreporting.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DeliverySummaryReport(LocalDate from, LocalDate to, String timezone,
    long created, long delivered, long failed, BigDecimal successRate) {}
