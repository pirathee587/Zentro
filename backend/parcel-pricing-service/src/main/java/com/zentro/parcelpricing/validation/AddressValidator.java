package com.zentro.parcelpricing.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class AddressValidator implements ConstraintValidator<Address, String> {
  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) return false;
    // Inspect before trim: CR/LF/TAB at the edges must not disappear before validation.
    if (value.codePoints().anyMatch(c -> Character.isISOControl(c)
        || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE)) return false;
    String trimmed = value.trim();
    int length = trimmed.codePointCount(0, trimmed.length());
    return length >= 8 && length <= 255
        && trimmed.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
  }
}
