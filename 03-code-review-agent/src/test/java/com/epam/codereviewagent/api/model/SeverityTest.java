package com.epam.codereviewagent.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class SeverityTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void shouldSerializeAndDeserializeExactLowercaseJsonString_whenSeverityIsBlocker()
    throws Exception {
    // Arrange
    Severity severity = Severity.BLOCKER;

    // Act
    String json = objectMapper.writeValueAsString(severity);
    Severity roundTripped = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(json).isEqualTo("\"blocker\"");
    assertThat(roundTripped).isEqualTo(Severity.BLOCKER);
  }

  @Test
  void shouldSerializeAndDeserializeExactLowercaseJsonString_whenSeverityIsHigh() throws Exception {
    // Arrange
    Severity severity = Severity.HIGH;

    // Act
    String json = objectMapper.writeValueAsString(severity);
    Severity roundTripped = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(json).isEqualTo("\"high\"");
    assertThat(roundTripped).isEqualTo(Severity.HIGH);
  }

  @Test
  void shouldSerializeAndDeserializeExactLowercaseJsonString_whenSeverityIsMedium()
    throws Exception {
    // Arrange
    Severity severity = Severity.MEDIUM;

    // Act
    String json = objectMapper.writeValueAsString(severity);
    Severity roundTripped = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(json).isEqualTo("\"medium\"");
    assertThat(roundTripped).isEqualTo(Severity.MEDIUM);
  }

  @Test
  void shouldSerializeAndDeserializeExactLowercaseJsonString_whenSeverityIsLow() throws Exception {
    // Arrange
    Severity severity = Severity.LOW;

    // Act
    String json = objectMapper.writeValueAsString(severity);
    Severity roundTripped = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(json).isEqualTo("\"low\"");
    assertThat(roundTripped).isEqualTo(Severity.LOW);
  }

  @Test
  void shouldSerializeAndDeserializeExactLowercaseJsonString_whenSeverityIsInfo() throws Exception {
    // Arrange
    Severity severity = Severity.INFO;

    // Act
    String json = objectMapper.writeValueAsString(severity);
    Severity roundTripped = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(json).isEqualTo("\"info\"");
    assertThat(roundTripped).isEqualTo(Severity.INFO);
  }

  @Test
  void shouldRejectUnknownSeverityValue_whenDeserializingAnEntirelyUnrelatedString() {
    // Arrange
    String json = "\"critical\"";

    // Act / Assert
    assertThatThrownBy(() -> objectMapper.readValue(json, Severity.class))
      .hasRootCauseInstanceOf(IllegalArgumentException.class)
      .rootCause()
      .hasMessageContaining("critical");
  }

  // ---------------------------------------------------------------------------------------
  // Case-insensitivity (code review, retry 1, Medium finding): real models routinely emit
  // capitalized severities. A merely differently-cased variant of a real value is now accepted,
  // while a wholly unrelated value remains rejected identically to before.
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldAcceptFullyUppercaseSeverityValue_whenDeserializingUppercaseVariantOfAValidSeverity()
    throws Exception {
    // Arrange
    String json = "\"HIGH\"";

    // Act
    Severity severity = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(severity).isEqualTo(Severity.HIGH);
  }

  @Test
  void shouldAcceptTitleCaseSeverityValue_whenDeserializingTitleCaseVariantOfAValidSeverity()
    throws Exception {
    // Arrange
    String json = "\"High\"";

    // Act
    Severity severity = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(severity).isEqualTo(Severity.HIGH);
  }

  @Test
  void shouldAcceptLowercaseSeverityValue_whenDeserializingTheCanonicalLowercaseForm()
    throws Exception {
    // Arrange
    String json = "\"high\"";

    // Act
    Severity severity = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(severity).isEqualTo(Severity.HIGH);
  }

  @Test
  void shouldAcceptAndTrimSurroundingWhitespace_whenDeserializingAPaddedSeverityValue()
    throws Exception {
    // Arrange: this project's documented decision is to trim - see Severity's own Javadoc.
    String json = "\" high \"";

    // Act
    Severity severity = objectMapper.readValue(json, Severity.class);

    // Assert
    assertThat(severity).isEqualTo(Severity.HIGH);
  }

  @Test
  void shouldRejectEmptySeverityValue_whenDeserializingAnEmptyString() {
    // Arrange
    String json = "\"\"";

    // Act / Assert
    assertThatThrownBy(() -> objectMapper.readValue(json, Severity.class))
      .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldReturnNull_whenDeserializingAnExplicitJsonNull() throws Exception {
    // Arrange
    String json = "null";

    // Act
    Severity severity = objectMapper.readValue(json, Severity.class);

    // Assert: explicit JSON null is not a "severity value" to reject - it maps to Java null,
    // matching how a Finding's own nullable `severity` field is meant to be represented.
    assertThat(severity).isNull();
  }

  @Test
  void shouldThrowIllegalArgumentException_whenFromJsonValueIsCalledDirectlyWithNull() {
    // Act / Assert: fromJsonValue's own null-guard, exercised directly (not only through Jackson's
    // own null short-circuit, proven separately above).
    assertThatThrownBy(() -> Severity.fromJsonValue(null))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldExposeExactLowercaseJsonValue_forEverySeverityConstant() {
    // Arrange / Act / Assert
    assertThat(Severity.BLOCKER.jsonValue()).isEqualTo("blocker");
    assertThat(Severity.HIGH.jsonValue()).isEqualTo("high");
    assertThat(Severity.MEDIUM.jsonValue()).isEqualTo("medium");
    assertThat(Severity.LOW.jsonValue()).isEqualTo("low");
    assertThat(Severity.INFO.jsonValue()).isEqualTo("info");
  }
}
