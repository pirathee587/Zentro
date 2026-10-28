package com.zentro.parcelpricing.domain;

import java.math.BigDecimal;

public enum ParcelSize {
  SMALL(BigDecimal.valueOf(1.0)),
  MEDIUM(BigDecimal.valueOf(3.0)),
  LARGE(BigDecimal.valueOf(5.0));

  private final BigDecimal defaultWeightKg;

  ParcelSize(BigDecimal defaultWeightKg) {
    this.defaultWeightKg = defaultWeightKg;
  }

  public BigDecimal defaultWeightKg() {
    return defaultWeightKg;
  }
}
