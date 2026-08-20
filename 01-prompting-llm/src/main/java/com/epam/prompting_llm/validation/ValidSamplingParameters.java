package com.epam.prompting_llm.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Validates that a chat request activates at most one sampling control. */
@Documented
@Constraint(validatedBy = ValidSamplingParametersValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSamplingParameters {

  String message() default "Only one of temperature and topP may be provided";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
