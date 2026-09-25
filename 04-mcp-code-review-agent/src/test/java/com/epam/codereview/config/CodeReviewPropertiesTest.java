package com.epam.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Proves the {@code @Min(1)} constraint on {@link CodeReviewProperties#getMaxIterations()} - a
 * misconfigured {@code maxIterations} must fail fast via Spring Boot's own JSR-303
 * configuration-properties validation at startup, rather than silently exhausting the ReAct loop
 * on every single request at runtime. Mirrors {@code 03-code-review-agent}'s
 * {@code CodeReviewPropertiesTest} structure: direct {@code jakarta.validation.Validator} checks
 * plus {@code ApplicationContextRunner} boundary cases proving a real (minimal) Spring context
 * actually refuses to start with a bad value.
 */
class CodeReviewPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
    .withUserConfiguration(CodeReviewPropertiesConfiguration.class);

  @Test
  void shouldHaveNoViolations_whenMaxIterationsIsAtTheDefaultValue() {
    CodeReviewProperties properties = new CodeReviewProperties();

    Set<ConstraintViolation<CodeReviewProperties>> violations = validator.validate(properties);

    assertThat(violations).isEmpty();
    assertThat(properties.getMaxIterations()).isEqualTo(15);
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

  @Test
  void shouldFailContextRefresh_whenMaxIterationsPropertyIsZero() {
    contextRunner
      .withPropertyValues("app.code-review.max-iterations=0")
      .run(context -> {
        assertThat(context).hasFailed();
        List<Throwable> causeChain = collectCauseChain(context.getStartupFailure());

        assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);

        // Somewhere in the cause chain, the real Jakarta Bean Validation failure for exactly the
        // maxIterations field must be present - proves this is genuinely the @Min(1) constraint
        // tripping, not an unrelated binding failure.
        assertThat(causeChain)
          .as("expected a BindValidationException naming the maxIterations field somewhere in the "
            + "cause chain")
          .anySatisfy(cause -> assertThat(cause)
            .isInstanceOf(BindValidationException.class)
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

  private static List<Throwable> collectCauseChain(Throwable throwable) {
    List<Throwable> chain = new ArrayList<>();
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
