package com.epam.prompting_llm.exception.handler;

import com.epam.prompting_llm.api.model.ErrorDetails;
import com.epam.prompting_llm.api.model.ErrorResponse;
import com.epam.prompting_llm.exception.InvalidSamplingParametersException;
import com.epam.prompting_llm.exception.StructuredOutputException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatExceptionHandlerTest {

  private final ChatExceptionHandler handler = new ChatExceptionHandler();

  @Test
  void shouldReturnStableBadRequestWhenSamplingResolutionFails() {
    // Arrange
    InvalidSamplingParametersException exception =
      new InvalidSamplingParametersException();

    // Act
    ResponseEntity<ErrorResponse> response = handler.handleInvalidSamplingParameters(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().message())
      .isEqualTo("Only one of temperature and topP may be provided");
  }

  @Test
  void shouldNotExposeRawModelOutputWhenStructuredConversionFails() {
    // Arrange
    StructuredOutputException exception = new StructuredOutputException(
      new RuntimeException("Api-Key=do-not-expose malformed-private-output"));

    // Act
    ResponseEntity<ErrorResponse> response = handler.handleStructuredOutputException(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody().message())
      .isEqualTo("Model returned an invalid structured response")
      .doesNotContain("do-not-expose", "malformed-private-output");
  }

  @Test
  void shouldNotExposeProviderDetailsWhenProviderCallFails() {
    // Arrange
    TransientAiException exception = new TransientAiException(
      "Authorization: secret-provider-message");

    // Act
    ResponseEntity<ErrorResponse> response = handler.handleAiException(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody().message())
      .isEqualTo("AI provider request failed")
      .doesNotContain("secret-provider-message");
  }

  @Test
  void shouldNotExposeInternalDetailsWhenUnexpectedFailureOccurs() {
    // Arrange
    RuntimeException exception = new RuntimeException("private internal details");

    // Act
    ResponseEntity<ErrorResponse> response = handler.handleException(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().message())
      .isEqualTo("Unable to process the chat request")
      .doesNotContain("private internal details");
  }

  @Test
  void shouldReturnFieldAndCrossFieldDetailsWhenValidationFails() {
    // Arrange
    BeanPropertyBindingResult bindingResult =
      new BeanPropertyBindingResult(new Object(), "promptRequest");
    bindingResult.addError(new FieldError(
      "promptRequest", "message", "Message must not be blank"));
    bindingResult.addError(new ObjectError(
      "promptRequest", "Only one of temperature and topP may be provided"));
    MethodArgumentNotValidException exception =
      new MethodArgumentNotValidException(null, bindingResult);

    // Act
    ResponseEntity<List<ErrorDetails>> response = handler.handleValidationExceptions(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsExactly(
      new ErrorDetails("message", "Message must not be blank"),
      new ErrorDetails("promptRequest", "Only one of temperature and topP may be provided")
    );
  }
}
