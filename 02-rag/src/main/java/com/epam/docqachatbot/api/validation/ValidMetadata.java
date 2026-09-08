package com.epam.docqachatbot.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = MetadataValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMetadata {

  String message() default "Metadata values must be flat scalar values and strings must contain at most 512 characters";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  int maxStringLength() default 512;
}
