package com.epam.prompting_llm.api.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptRequestTest {

  private static jakarta.validation.ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  void shouldDeserializeMessageWhenCanonicalFieldProvided() throws Exception {
    // Arrange
    String json = """
      {"message":"Hello","conversationId":"conversation-1","maxTokens":10}
      """;

    // Act
    PromptRequest request = new ObjectMapper().readValue(json, PromptRequest.class);

    // Assert
    assertThat(request.message()).isEqualTo("Hello");
    assertThat(request.conversationId()).isEqualTo("conversation-1");
  }

  @Test
  void shouldDeserializeMessageWhenLegacyInputFieldProvided() throws Exception {
    // Arrange
    String json = """
      {"input":"Legacy hello","conversationId":"conversation-1"}
      """;

    // Act
    PromptRequest request = new ObjectMapper().readValue(json, PromptRequest.class);

    // Assert
    assertThat(request.message()).isEqualTo("Legacy hello");
  }

  @Test
  void shouldAcceptRequestWhenExperimentBoundaryValuesProvided() {
    // Arrange
    PromptRequest temperatureRequest = request("message", 1.5, null, 10);
    PromptRequest topPRequest = request("message", null, 1.0, 20);
    PromptRequest zeroBoundaries = request("message", 0.0, null, 1);

    // Act / Assert
    assertThat(validator.validate(temperatureRequest)).isEmpty();
    assertThat(validator.validate(topPRequest)).isEmpty();
    assertThat(validator.validate(zeroBoundaries)).isEmpty();
  }

  @Test
  void shouldRejectRequestWhenMessageIsBlank() {
    // Arrange
    PromptRequest request = request("  ", null, null, null);

    // Act
    Set<ConstraintViolation<PromptRequest>> violations = validator.validate(request);

    // Assert
    assertThat(violations)
      .extracting(ConstraintViolation::getMessage)
      .contains("Message must not be blank");
  }

  @Test
  void shouldRejectRequestWhenBothSamplingParametersProvided() {
    // Arrange
    PromptRequest request = request("message", 0.7, 0.9, null);

    // Act
    Set<ConstraintViolation<PromptRequest>> violations = validator.validate(request);

    // Assert
    assertThat(violations)
      .extracting(ConstraintViolation::getMessage)
      .contains("Only one of temperature and topP may be provided");
  }

  @Test
  void shouldRejectRequestWhenNumericValuesAreOutsideBoundaries() {
    // Arrange
    PromptRequest invalidTemperature = request("message", 2.01, null, 10);
    PromptRequest invalidTopP = request("message", null, 1.01, 10);
    PromptRequest invalidMaxTokens = request("message", null, null, 0);

    // Act / Assert
    assertThat(validator.validate(invalidTemperature))
      .extracting(ConstraintViolation::getMessage)
      .contains("Temperature must be between 0.0 and 2.0");
    assertThat(validator.validate(invalidTopP))
      .extracting(ConstraintViolation::getMessage)
      .contains("TopP must be between 0.0 and 1.0");
    assertThat(validator.validate(invalidMaxTokens))
      .extracting(ConstraintViolation::getMessage)
      .contains("MaxTokens must be greater than zero");
  }

  private PromptRequest request(String message, Double temperature, Double topP,
                                Integer maxTokens) {
    return new PromptRequest(message, "conversation-1", temperature, topP, maxTokens);
  }
}
