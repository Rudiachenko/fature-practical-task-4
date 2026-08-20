package com.epam.prompting_llm.service;

import com.epam.prompting_llm.api.model.MessageTone;
import com.epam.prompting_llm.api.model.StructuredChatResponse;
import com.epam.prompting_llm.exception.StructuredOutputException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatStructuredOutputConverterTest {

  private final ChatStructuredOutputConverter converter = new ChatStructuredOutputConverter();

  @Test
  void shouldConvertResponseWhenJsonMatchesSchema() {
    // Arrange
    String json = """
      {"response":"You can do it.","tone":"POSITIVE"}
      """;

    // Act
    StructuredChatResponse response = converter.convert(json);

    // Assert
    assertThat(response.response()).isEqualTo("You can do it.");
    assertThat(response.tone()).isEqualTo(MessageTone.POSITIVE);
    assertThat(converter.getFormat()).contains("POSITIVE", "NEGATIVE", "NEUTRAL");
  }

  @Test
  void shouldRejectResponseWhenToneIsOutsideEnum() {
    // Arrange
    String json = """
      {"response":"Maybe.","tone":"MIXED"}
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(StructuredOutputException.class)
      .hasMessage("Model returned an invalid structured response");
  }

  @Test
  void shouldRejectResponseWhenJsonIsMalformed() {
    // Arrange
    String json = "not-json";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(StructuredOutputException.class);
  }

  @Test
  void shouldRejectResponseWhenRequiredValueIsMissing() {
    // Arrange
    String json = """
      {"response":"","tone":"NEUTRAL"}
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(StructuredOutputException.class);
  }

  @Test
  void shouldRejectResponseWhenUnknownFieldIsPresent() {
    // Arrange
    String json = """
      {"response":"Okay.","tone":"NEUTRAL","unexpected":"value"}
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(StructuredOutputException.class);
  }
}
