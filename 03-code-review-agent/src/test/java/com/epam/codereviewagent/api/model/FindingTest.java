package com.epam.codereviewagent.api.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Not listed in {@code context/PLAN.md}'s literal Increment 4 "Files IN" test list (only
 * {@code CodeReviewResponseTest}, {@code SeverityTest}, and
 * {@code CodeReviewStructuredOutputConverterTest} are named there); added because the
 * implementation task explicitly requires deciding and testing {@code Finding}'s line-number
 * invariants (non-positive line numbers, {@code endLine < startLine}) directly, not only
 * indirectly through a JSON round trip. See {@code context/PROGRESS.md}'s Increment 4 entry for
 * the recorded reason.
 */
class FindingTest {

  @Test
  void shouldConstructSuccessfully_whenStartLineAndEndLineAreValidAndOrdered() {
    // Arrange / Act
    Finding finding = new Finding("Foo.java", 5, 10, "rule", Severity.HIGH, "explanation", "recommendation");

    // Assert
    assertThat(finding.startLine()).isEqualTo(5);
    assertThat(finding.endLine()).isEqualTo(10);
  }

  @Test
  void shouldConstructSuccessfully_whenStartLineEqualsEndLine() {
    // Arrange / Act
    Finding finding = new Finding("Foo.java", 5, 5, "rule", Severity.HIGH, "explanation", "recommendation");

    // Assert
    assertThat(finding.startLine()).isEqualTo(5);
    assertThat(finding.endLine()).isEqualTo(5);
  }

  @Test
  void shouldConstructSuccessfully_whenStartLineAndEndLineAreBothNull() {
    // Arrange / Act
    Finding finding = new Finding("Foo.java", null, null, "rule", Severity.HIGH, "explanation", "recommendation");

    // Assert
    assertThat(finding.startLine()).isNull();
    assertThat(finding.endLine()).isNull();
  }

  @Test
  void shouldConstructSuccessfully_whenOnlyStartLineIsProvided() {
    // Arrange / Act
    Finding finding = new Finding("Foo.java", 3, null, "rule", Severity.HIGH, "explanation", "recommendation");

    // Assert
    assertThat(finding.startLine()).isEqualTo(3);
    assertThat(finding.endLine()).isNull();
  }

  @Test
  void shouldConstructSuccessfully_whenOnlyEndLineIsProvided() {
    // Arrange / Act
    Finding finding = new Finding("Foo.java", null, 3, "rule", Severity.HIGH, "explanation", "recommendation");

    // Assert
    assertThat(finding.startLine()).isNull();
    assertThat(finding.endLine()).isEqualTo(3);
  }

  @Test
  void shouldRejectZeroStartLine_whenStartLineIsZero() {
    // Act / Assert
    assertThatThrownBy(() -> new Finding("Foo.java", 0, 5, "rule", Severity.HIGH, "explanation", "recommendation"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("startLine")
      .hasMessageContaining("0");
  }

  @Test
  void shouldRejectNegativeStartLine_whenStartLineIsNegative() {
    // Act / Assert
    assertThatThrownBy(() -> new Finding("Foo.java", -1, 5, "rule", Severity.HIGH, "explanation", "recommendation"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("startLine");
  }

  @Test
  void shouldRejectZeroEndLine_whenEndLineIsZero() {
    // Act / Assert
    assertThatThrownBy(() -> new Finding("Foo.java", 1, 0, "rule", Severity.HIGH, "explanation", "recommendation"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("endLine")
      .hasMessageContaining("0");
  }

  @Test
  void shouldRejectNegativeEndLine_whenEndLineIsNegative() {
    // Act / Assert
    assertThatThrownBy(() -> new Finding("Foo.java", 1, -5, "rule", Severity.HIGH, "explanation", "recommendation"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("endLine");
  }

  @Test
  void shouldRejectEndLineLessThanStartLine_whenEndLineIsBeforeStartLine() {
    // Act / Assert
    assertThatThrownBy(() -> new Finding("Foo.java", 10, 5, "rule", Severity.HIGH, "explanation", "recommendation"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("endLine")
      .hasMessageContaining("startLine");
  }

  @Test
  void shouldAllowAllOptionalFieldsToBeNull_whenOnlyLineNumbersArePresent() {
    // Arrange / Act
    Finding finding = new Finding(null, 1, 2, null, null, null, null);

    // Assert
    assertThat(finding.file()).isNull();
    assertThat(finding.rule()).isNull();
    assertThat(finding.severity()).isNull();
    assertThat(finding.explanation()).isNull();
    assertThat(finding.recommendation()).isNull();
  }
}
