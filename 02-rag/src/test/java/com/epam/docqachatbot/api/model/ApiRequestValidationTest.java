package com.epam.docqachatbot.api.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRequestValidationTest {

  private static final ValidatorFactory VALIDATOR_FACTORY =
    Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

  @AfterAll
  static void closeValidatorFactory() {
    VALIDATOR_FACTORY.close();
  }

  @Test
  void shouldRejectOversizedConversationId_whenConversationIdExceedsBound() {
    // Arrange
    ChatRequest request = new ChatRequest("question", "c".repeat(129));

    // Act / Assert
    assertThat(messages(request))
      .containsExactly("ConversationId must contain at most 128 characters");
  }

  @Test
  void shouldRejectInvalidResourceLocations_whenEntriesAreBlankOrOversized() {
    // Arrange
    DocumentIngestionRequest request = new DocumentIngestionRequest(
      List.of(" ", "x".repeat(513)), Map.of());

    // Act / Assert
    assertThat(messages(request)).containsExactlyInAnyOrder(
      "Resource location must not be blank",
      "Resource location must contain at most 512 characters"
    );
  }

  @Test
  void shouldRejectOversizedMetadata_whenEntryCountExceedsBound() {
    // Arrange
    Map<String, Object> metadata = new LinkedHashMap<>();
    for (int index = 0; index < 33; index++) {
      metadata.put("key-" + index, index);
    }
    DocumentIngestionRequest request =
      new DocumentIngestionRequest(List.of("classpath:policy.md"), metadata);

    // Act / Assert
    assertThat(messages(request))
      .containsExactly("Metadata must contain at most 32 entries");
  }

  @Test
  void shouldRejectInvalidMetadataKeys_whenKeysAreBlankOrOversized() {
    // Arrange
    DocumentIngestionRequest blankKey = new DocumentIngestionRequest(
      List.of("classpath:policy.md"), Map.of(" ", "value"));
    DocumentIngestionRequest oversizedKey = new DocumentIngestionRequest(
      List.of("classpath:policy.md"), Map.of("k".repeat(65), "value"));

    // Act / Assert
    assertThat(messages(blankKey)).containsExactly("Metadata key must not be blank");
    assertThat(messages(oversizedKey))
      .containsExactly("Metadata key must contain at most 64 characters");
  }

  @Test
  void shouldUseEmptyMetadata_whenMetadataIsNull() {
    // Act
    DocumentIngestionRequest request =
      new DocumentIngestionRequest(List.of("classpath:policy.md"), null);

    // Assert
    assertThat(request.metadata()).isEmpty();
    assertThat(messages(request)).isEmpty();
  }

  @Test
  void shouldAcceptAllowlistedScalarMetadata_whenValuesAreBounded() {
    // Arrange
    DocumentIngestionRequest request = new DocumentIngestionRequest(
      List.of("classpath:policy.md"),
      Map.of(
        "sourceType", "sample",
        "reviewed", true,
        "version", 2,
        "score", 0.75
      ));

    // Act / Assert
    assertThat(messages(request)).isEmpty();
  }

  @Test
  void shouldRejectMetadataString_whenValueExceedsBound() {
    // Arrange
    DocumentIngestionRequest request = new DocumentIngestionRequest(
      List.of("classpath:policy.md"), Map.of("description", "x".repeat(513)));

    // Act / Assert
    assertThat(messages(request)).containsExactly(
      "Metadata values must be flat scalar values and strings must contain at most 512 characters");
  }

  @Test
  void shouldRejectNestedMetadata_whenValueIsMapCollectionOrArray() {
    // Arrange
    List<Object> unsupportedValues = List.of(
      Map.of("nested", "value"),
      List.of("nested"),
      new String[]{"nested"}
    );

    // Act / Assert
    assertThat(unsupportedValues)
      .allSatisfy(value -> {
        DocumentIngestionRequest request = new DocumentIngestionRequest(
          List.of("classpath:policy.md"), Map.of("unsupported", value));
        assertThat(messages(request)).containsExactly(
          "Metadata values must be flat scalar values and strings must contain at most 512 characters");
      });
  }

  @Test
  void shouldRejectUnsupportedMetadataScalar_whenValueIsNullNonFiniteOrUnknown() {
    // Arrange
    List<Object> unsupportedValues = List.of(
      Double.NaN,
      Float.POSITIVE_INFINITY,
      new Object()
    );
    Map<String, Object> nullMetadata = new LinkedHashMap<>();
    nullMetadata.put("unsupported", null);

    // Act / Assert
    assertThat(unsupportedValues)
      .allSatisfy(value -> assertThat(messages(new DocumentIngestionRequest(
        List.of("classpath:policy.md"), Map.of("unsupported", value))))
        .containsExactly(
          "Metadata values must be flat scalar values and strings must contain at most 512 characters"));
    assertThat(messages(new DocumentIngestionRequest(
      List.of("classpath:policy.md"), nullMetadata)))
      .containsExactly(
        "Metadata values must be flat scalar values and strings must contain at most 512 characters");
  }

  private List<String> messages(Object request) {
    return VALIDATOR.validate(request).stream()
      .map(ConstraintViolation::getMessage)
      .sorted()
      .toList();
  }
}
