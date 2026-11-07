package com.zentro.parcelpricing.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {
  private int max;
  @Override public void initialize(CodePointSize rule) { max = rule.max(); }
  @Override public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) return true;
    String trimmed = value.trim();
    return trimmed.codePointCount(0, trimmed.length()) <= max;
  }
}
