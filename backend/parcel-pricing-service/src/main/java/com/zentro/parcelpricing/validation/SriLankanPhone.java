package com.zentro.parcelpricing.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SriLankanPhoneValidator.class)
public @interface SriLankanPhone {
  String message() default "Enter a Sri Lankan number, e.g. 0771234567 or +94771234567.";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
