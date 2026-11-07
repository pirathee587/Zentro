package com.zentro.parcelpricing.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AddressValidator.class)
public @interface Address {
  String message() default "Address must contain 8 to 255 characters after trimming, without control or format characters.";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
