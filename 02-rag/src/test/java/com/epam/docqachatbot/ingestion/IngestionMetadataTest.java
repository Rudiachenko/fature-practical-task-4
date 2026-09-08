package com.epam.docqachatbot.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionMetadataTest {

  @Test
  void shouldReplaceSingleQuote_whenDocumentNameContainsOne() {
    // Arrange
    String raw = "O'Brien_report.md";

    // Act
    String sanitized = IngestionMetadata.sanitizeDocumentName(raw);

    // Assert
    assertThat(sanitized).isEqualTo("O_Brien_report.md");
  }

  @Test
  void shouldReplaceDoubleQuote_whenDocumentNameContainsOne() {
    // Arrange
    String raw = "user\"s guide.pdf";

    // Act
    String sanitized = IngestionMetadata.sanitizeDocumentName(raw);

    // Assert
    assertThat(sanitized).isEqualTo("user_s guide.pdf");
  }

  @Test
  void shouldReplaceBackslash_whenDocumentNameContainsOne() {
    // Arrange
    String raw = "notes\\path.md";

    // Act
    String sanitized = IngestionMetadata.sanitizeDocumentName(raw);

    // Assert
    assertThat(sanitized).isEqualTo("notes_path.md");
  }

  @Test
  void shouldReplaceControlCharacter_whenDocumentNameContainsOne() {
    // Arrange
    String raw = "report" + (char) 7 + ".md";

    // Act
    String sanitized = IngestionMetadata.sanitizeDocumentName(raw);

    // Assert
    assertThat(sanitized).isEqualTo("report_.md");
  }

  @Test
  void shouldReplaceEveryHostileCharacter_whenDocumentNameContainsACombination() {
    // Arrange
    String raw = "O'Brien\\user\"s \"guide'.md";

    // Act
    String sanitized = IngestionMetadata.sanitizeDocumentName(raw);

    // Assert
    assertThat(sanitized).isEqualTo("O_Brien_user_s _guide_.md");
    assertThat(sanitized).doesNotContain("'", "\"", "\\");
  }

  @Test
  void shouldReturnValueUnchanged_whenDocumentNameContainsNoHostileCharacter() {
    // Arrange
    String raw = "EPAM_JavaSecureCodingGD.md";

    // Act
    String sanitized = IngestionMetadata.sanitizeDocumentName(raw);

    // Assert
    assertThat(sanitized).isEqualTo(raw);
  }

  @Test
  void shouldReturnSameValue_whenSanitizingAnAlreadySanitizedValue() {
    // Arrange
    String raw = "O'Brien\\user\"s \"guide'.md";
    String sanitizedOnce = IngestionMetadata.sanitizeDocumentName(raw);

    // Act
    String sanitizedTwice = IngestionMetadata.sanitizeDocumentName(sanitizedOnce);

    // Assert
    assertThat(sanitizedTwice).isEqualTo(sanitizedOnce);
  }

  @Test
  void shouldBeDeterministic_whenCalledRepeatedlyWithTheSameInput() {
    // Arrange
    String raw = "O'Brien_report.md";

    // Act
    String first = IngestionMetadata.sanitizeDocumentName(raw);
    String second = IngestionMetadata.sanitizeDocumentName(raw);

    // Assert
    assertThat(first).isEqualTo(second);
  }

  @Test
  void shouldReturnNull_whenDocumentNameIsNull() {
    // Arrange / Act
    String sanitized = IngestionMetadata.sanitizeDocumentName(null);

    // Assert
    assertThat(sanitized).isNull();
  }

  @Test
  void shouldReturnEmptyString_whenDocumentNameIsEmpty() {
    // Arrange / Act
    String sanitized = IngestionMetadata.sanitizeDocumentName("");

    // Assert
    assertThat(sanitized).isEmpty();
  }
}
