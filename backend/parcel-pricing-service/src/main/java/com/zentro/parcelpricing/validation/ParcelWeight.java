package com.zentro.parcelpricing.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@DecimalMin(ParcelWeight.MIN)
@DecimalMax(ParcelWeight.MAX)
@Digits(integer = 2, fraction = 4)
@ReportAsSingleViolation
public @interface ParcelWeight {
  String MIN = "0.1";
  String MAX = "20";
  String MESSAGE = "Weight must be between " + MIN + " and " + MAX + " kg with at most 4 decimal places.";
  String message() default MESSAGE;
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
