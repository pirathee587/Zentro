package com.zentro.parcelpricing.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CodePointSizeValidator.class)
public @interface CodePointSize {
  int max();
  String message() default "Text is too long.";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
