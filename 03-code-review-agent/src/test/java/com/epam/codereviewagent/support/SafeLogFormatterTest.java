package com.epam.codereviewagent.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Ported adversarial cases from {@code 01-prompting-llm}'s {@code SafeLogFormatterTest} - same
 * assertions, new package, proving the port preserved behavior exactly.
 */
class SafeLogFormatterTest {

  private static final int EXPECTED_TRUNCATED_VALUE_LENGTH = 503;

  // Every control/line-separator code point below is built from its integer code point via a char
  // cast, rather than typed as a literal escape character in this source file - the same
  // authoring-tool workaround already recorded for a literal NUL character in Increment 1's
  // context/PROGRESS.md entry (this exact authoring tool does not reliably preserve a literal
  // non-printable character typed directly into source text).
  private static final char ESCAPE_CODE_POINT = (char) 27;
  private static final char UNICODE_LINE_SEPARATOR = (char) 8232;
  private static final char UNICODE_NEXT_LINE = (char) 133;

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

  // ---------------------------------------------------------------------------------------------
  // Divergence from the 01-prompting-llm original (Increment 5 retry 1, code review Medium
  // finding): this module routes attacker-controlled repository file content through
  // SafeLogFormatter, so ANSI escapes and Unicode line separators must also be neutralized, not
  // just \r\n\t. See this class's own Javadoc for the full rationale. 01-prompting-llm's own
  // SafeLogFormatter/test are unmodified.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldNeutralizeRealAnsiColorEscapeSequence_whenValueContainsOne() {
    // Arrange: a real ANSI "set foreground to red" / "reset" escape sequence pair, as could
    // appear in a source-file comment fed back into a log line via a tool-call argument summary.
    String value = "before" + ESCAPE_CODE_POINT + "[31mRED" + ESCAPE_CODE_POINT + "[0mafter";

    // Act
    String formatted = SafeLogFormatter.format(value);

    // Assert
    assertThat(formatted).doesNotContain(String.valueOf(ESCAPE_CODE_POINT));
  }

  @Test
  void shouldNeutralizeBareEscapeCharacter_whenValueContainsALoneEscapeCodePoint() {
    // Arrange: a lone ESC code point with no following control-sequence body at all.
    String value = "before" + ESCAPE_CODE_POINT + "after";

    // Act
    String formatted = SafeLogFormatter.format(value);

    // Assert
    assertThat(formatted).doesNotContain(String.valueOf(ESCAPE_CODE_POINT));
  }

  @Test
  void shouldNeutralizeUnicodeLineSeparatorAndNextLine_whenValueContainsEither() {
    // Arrange: U+2028 LINE SEPARATOR and U+0085 NEXT LINE (NEL) - code points some log viewers
    // honor as line breaks even though they are neither \r nor \n, so the original \r\n\t-only
    // pattern left them untouched.
    String value = "before" + UNICODE_LINE_SEPARATOR + "middle" + UNICODE_NEXT_LINE + "after";

    // Act
    String formatted = SafeLogFormatter.format(value);

    // Assert
    assertThat(formatted)
      .doesNotContain(String.valueOf(UNICODE_LINE_SEPARATOR))
      .doesNotContain(String.valueOf(UNICODE_NEXT_LINE));
  }
}
