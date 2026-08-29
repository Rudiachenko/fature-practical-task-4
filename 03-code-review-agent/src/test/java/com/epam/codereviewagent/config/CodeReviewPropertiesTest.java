package com.epam.codereviewagent.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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

  // -----------------------------------------------------------------------------------------------
  // Closes a Low finding carried over from Increment 5's review: the four tests above prove the
  // @Min(1) annotation is individually correct via a direct jakarta.validation.Validator call, but do
  // not prove a real Spring Boot application actually refuses to start with a bad value.
  // ApplicationContextRunner boots a real (minimal) Spring context and drives real
  // @ConfigurationProperties binding/validation - unlike @SpringBootTest(webEnvironment = RANDOM_PORT),
  // it needs no servlet container, so it runs fine in this sandboxed environment (see
  // context/PROGRESS.md's "Known environment limit" note).
  // -----------------------------------------------------------------------------------------------

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
    .withUserConfiguration(CodeReviewPropertiesConfiguration.class);

  @Test
  void shouldFailContextRefresh_whenMaxIterationsPropertyIsZero() {
    contextRunner
      .withPropertyValues("app.code-review.max-iterations=0")
      .run(context -> {
        assertThat(context).hasFailed();
        java.util.List<Throwable> causeChain = collectCauseChain(context.getStartupFailure());

        // The top-level exception really is ConfigurationPropertiesBindException, confirmed by the
        // reviewer's own ad-hoc probe (see this file's comment above) - asserted here, not just
        // assumed, so this test would fail loudly if Spring Boot ever changed which exception type
        // wraps a configuration-properties validation failure at context-refresh time.
        assertThat(context.getStartupFailure())
          .isInstanceOf(org.springframework.boot.context.properties.ConfigurationPropertiesBindException.class);

        // Somewhere in the cause chain, the real Jakarta Bean Validation failure for exactly the
        // maxIterations field must be present - proves this is genuinely the @Min(1) constraint
        // tripping, not an unrelated binding failure.
        assertThat(causeChain)
          .as("expected a BindValidationException naming the maxIterations field somewhere in the "
            + "cause chain")
          .anySatisfy(cause -> assertThat(cause)
            .isInstanceOf(org.springframework.boot.context.properties.bind.validation.BindValidationException.class)
            .hasMessageContaining("maxIterations")
            .hasMessageContaining("app.code-review"));
      });
  }

  @Test
  void shouldRefreshContextSuccessfully_whenMaxIterationsPropertyIsAValidValue() {
    contextRunner
      .withPropertyValues("app.code-review.max-iterations=5")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBean(CodeReviewProperties.class).getMaxIterations()).isEqualTo(5);
      });
  }

  private static java.util.List<Throwable> collectCauseChain(Throwable throwable) {
    java.util.List<Throwable> chain = new java.util.ArrayList<>();
    Throwable current = throwable;
    while (current != null && chain.size() < 20) {
      chain.add(current);
      current = current.getCause() == current ? null : current.getCause();
    }
    return chain;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CodeReviewProperties.class)
  static class CodeReviewPropertiesConfiguration {
  }
}
