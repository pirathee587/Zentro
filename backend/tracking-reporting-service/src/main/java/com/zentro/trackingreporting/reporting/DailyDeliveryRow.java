package com.zentro.trackingreporting.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyDeliveryRow(LocalDate day, long created, long delivered, long failed,
    long active, BigDecimal successRate) {}
