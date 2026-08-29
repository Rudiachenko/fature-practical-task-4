package com.epam.codereviewagent.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code @Min(1)} constraint added to {@link CodeReviewProperties#getMaxIterations()} in
 * Increment 5 retry 1 (code review Medium finding) - a misconfigured {@code maxIterations} must fail
 * fast via Spring Boot's own JSR-303 configuration-properties validation at startup, rather than
 * silently exhausting the ReAct loop on every single request at runtime. Uses the same direct
 * {@code jakarta.validation.Validator} pattern already established by {@code 02-rag}'s
 * {@code RagPromptAndPropertiesTest.shouldRejectInvalidRetrievalBounds_whenConfigurationIsValidated()}.
 */
class CodeReviewPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void shouldHaveNoViolations_whenMaxIterationsIsAtTheDefaultValue() {
    CodeReviewProperties properties = new CodeReviewProperties();

    Set<ConstraintViolation<CodeReviewProperties>> violations = validator.validate(properties);

    assertThat(violations).isEmpty();
  }

  @Test
  void shouldHaveNoViolations_whenMaxIterationsIsExactlyOne() {
    CodeReviewProperties properties = new CodeReviewProperties();
    properties.setMaxIterations(1);

    Set<ConstraintViolation<CodeReviewProperties>> violations = validator.validate(properties);

    assertThat(violations).isEmpty();
  }

  @Test
  void shouldRejectMaxIterationsOfZero_whenConfigurationIsValidated() {
    CodeReviewProperties properties = new CodeReviewProperties();
    properties.setMaxIterations(0);

    Set<ConstraintViolation<CodeReviewProperties>> violations = validator.validate(properties);

    assertThat(violations)
      .extracting(violation -> violation.getPropertyPath().toString())
      .containsExactly("maxIterations");
  }

  @Test
  void shouldRejectNegativeMaxIterations_whenConfigurationIsValidated() {
    CodeReviewProperties properties = new CodeReviewProperties();
    properties.setMaxIterations(-1);

    Set<ConstraintViolation<CodeReviewProperties>> violations = validator.validate(properties);

    assertThat(violations)
      .extracting(violation -> violation.getPropertyPath().toString())
      .containsExactly("maxIterations");
  }
}
