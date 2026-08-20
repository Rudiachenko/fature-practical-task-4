package com.epam.prompting_llm.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeLogFormatterTest {

  private static final int EXPECTED_TRUNCATED_VALUE_LENGTH = 503;

  @Test
  void shouldNeutralizeLogForgingAndCredentialsWhenSensitiveTextProvided() {
    // Arrange
    String value = "hello\r\nforged=true Authorization: Bearer secret-token Api-Key=secret-value";

    // Act
    String formatted = SafeLogFormatter.format(value);

    // Assert
    assertThat(formatted)
      .doesNotContain("\r", "\n", "secret-token", "secret-value")
      .contains("Authorization=[REDACTED]", "Api-Key=[REDACTED]");
  }

  @Test
  void shouldBoundValueWhenPayloadIsLong() {
    // Arrange
    String value = "x".repeat(700);

    // Act
    String formatted = SafeLogFormatter.format(value);

    // Assert
    assertThat(formatted).hasSize(EXPECTED_TRUNCATED_VALUE_LENGTH).endsWith("...");
  }

  @Test
  void shouldRepresentNullWhenValueIsNull() {
    // Act / Assert
    assertThat(SafeLogFormatter.format(null)).isEqualTo("<null>");
  }
}
