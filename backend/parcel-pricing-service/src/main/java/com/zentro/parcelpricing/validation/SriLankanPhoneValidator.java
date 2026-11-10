package com.zentro.parcelpricing.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class SriLankanPhoneValidator implements ConstraintValidator<SriLankanPhone, String> {
  @Override public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) return false;
    // Same separator normalization as persistence, without a recursive regex on unbounded input.
    String normalized = value.replaceAll("[\\s-]", "");
    return normalized.length() <= 12 && normalized.matches("^(\\+94|0)\\d{9}$");
  }
}
