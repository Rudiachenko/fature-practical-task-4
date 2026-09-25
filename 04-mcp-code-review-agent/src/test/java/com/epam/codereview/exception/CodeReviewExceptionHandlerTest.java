package com.epam.codereview.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.codereview.api.model.ApiError;
import com.epam.codereview.api.model.ApiViolation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Proves each row of {@code context/PLAN.md} Increment 5's exception-to-response mapping table:
 * exact HTTP status, exact {@code ApiError.code}, and that no response body ever contains the raw
 * exception's own {@code getMessage()} text. Calls each {@code @ExceptionHandler} method directly
 * with a hand-constructed exception - the same technique {@code 03-code-review-agent}'s
 * {@code CodeReviewExceptionHandlerTest} already uses - since none of these exception types require
 * a live MVC dispatch to construct.
 */
class CodeReviewExceptionHandlerTest {

  private final CodeReviewExceptionHandler handler = new CodeReviewExceptionHandler();

  // ---------------------------------------------------------------------------------------------
  // Exact error-code constants (RUNBOOK-precision guard: these strings are a public contract)
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldExposeExactErrorCodeConstants_matchingThePlanMappingTableLiterally() {
    assertThat(CodeReviewExceptionHandler.VALIDATION_FAILED).isEqualTo("VALIDATION_FAILED");
    assertThat(CodeReviewExceptionHandler.PR_REFERENCE_NOT_FOUND)
      .isEqualTo("PR_REFERENCE_NOT_FOUND");
    assertThat(CodeReviewExceptionHandler.AGENT_ITERATION_LIMIT_EXCEEDED)
      .isEqualTo("AGENT_ITERATION_LIMIT_EXCEEDED");
    assertThat(CodeReviewExceptionHandler.AI_PROVIDER_FAILURE).isEqualTo("AI_PROVIDER_FAILURE");
    assertThat(CodeReviewExceptionHandler.MALFORMED_REQUEST).isEqualTo("MALFORMED_REQUEST");
    assertThat(CodeReviewExceptionHandler.METHOD_NOT_ALLOWED).isEqualTo("METHOD_NOT_ALLOWED");
    assertThat(CodeReviewExceptionHandler.UNSUPPORTED_MEDIA_TYPE)
      .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(CodeReviewExceptionHandler.INTERNAL_ERROR).isEqualTo("INTERNAL_ERROR");
  }

  // ---------------------------------------------------------------------------------------------
  // ApiError's own null-safety contract (every handler above always passes a non-null violations
  // list, so this branch of the record's compact constructor is otherwise unreached by this test
  // class's own handler-level tests)
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldNormalizeNullViolationsToEmptyList_whenApiErrorIsConstructedDirectly() {
    ApiError error = new ApiError("SOME_CODE", "some message", null);

    assertThat(error.violations()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Row 1: MethodArgumentNotValidException -> 400 / VALIDATION_FAILED
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnBadRequestWithFieldViolations_whenValidationFails() {
    // Arrange
    BeanPropertyBindingResult bindingResult =
      new BeanPropertyBindingResult(new Object(), "userRequest");
    bindingResult.addError(new FieldError("userRequest", "userInput", "must not be blank"));
    MethodArgumentNotValidException exception =
      new MethodArgumentNotValidException(null, bindingResult);

    // Act
    ResponseEntity<ApiError> response = handler.handleValidationException(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
    assertThat(response.getBody().message()).isEqualTo("Request validation failed");
    assertThat(response.getBody().violations())
      .containsExactly(new ApiViolation("userInput", "must not be blank"));
  }

  @Test
  void shouldSortViolationsByFieldThenMessage_whenMultipleValidationErrorsOccur() {
    // Arrange
    BeanPropertyBindingResult bindingResult =
      new BeanPropertyBindingResult(new Object(), "userRequest");
    bindingResult.addError(new FieldError("userRequest", "zField", "must not be blank"));
    bindingResult.addError(new FieldError("userRequest", "aField", "must not be blank"));
    bindingResult.addError(new ObjectError("userRequest", "cross-field constraint violated"));
    MethodArgumentNotValidException exception =
      new MethodArgumentNotValidException(null, bindingResult);

    // Act
    ResponseEntity<ApiError> response = handler.handleValidationException(exception);

    // Assert
    assertThat(response.getBody().violations()).extracting(ApiViolation::field)
      .containsExactly("aField", "userRequest", "zField");
  }

  // ---------------------------------------------------------------------------------------------
  // Row 2: PrReferenceNotFoundException -> 400 / PR_REFERENCE_NOT_FOUND
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnBadRequestWithStaticMessage_whenPrReferenceNotFoundOccurs() {
    // Arrange
    PrReferenceNotFoundException exception = new PrReferenceNotFoundException(
      "No PR reference found in request: expected a GitHub PR URL, an owner/repo slug, or a "
        + "#<number> reference");

    // Act
    ResponseEntity<ApiError> response = handler.handlePrReferenceNotFound(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo("PR_REFERENCE_NOT_FOUND");
    assertThat(response.getBody().message())
      .isEqualTo("The request does not identify a GitHub pull request to review: include a PR "
        + "URL, an owner/repo reference, or a #<number> token.")
      .doesNotContain(exception.getMessage());
    assertThat(response.getBody().violations()).isEmpty();
  }

  @Test
  void shouldNotLeakRawNewlineInLogLine_whenPrReferenceNotFoundMessageContainsEmbeddedControlCharacters() {
    // Arrange
    Logger logbackLogger = (Logger) LoggerFactory.getLogger(CodeReviewExceptionHandler.class);
    Level originalLevel = logbackLogger.getLevel();
    logbackLogger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logbackLogger.addAppender(appender);

    try {
      PrReferenceNotFoundException exception = new PrReferenceNotFoundException(
        "No PR reference found: ../x\n[FAKE] ERROR forged-log-line api-key=super-secret");

      // Act
      handler.handlePrReferenceNotFound(exception);

      // Assert
      List<String> formattedMessages = appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
      assertThat(formattedMessages).isNotEmpty();
      assertThat(formattedMessages).allSatisfy(message -> assertThat(message)
        .doesNotContain("\n")
        .doesNotContain("\r")
        .doesNotContain("super-secret"));
      assertThat(formattedMessages).anyMatch(message -> message.contains("api-key=[REDACTED]"));
    } finally {
      logbackLogger.detachAppender(appender);
      logbackLogger.setLevel(originalLevel);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Row 3: AgentIterationLimitExceededException -> 500 / AGENT_ITERATION_LIMIT_EXCEEDED
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnInternalServerErrorWithStaticMessage_whenAgentIterationLimitExceeded() {
    // Arrange
    AgentIterationLimitExceededException exception = new AgentIterationLimitExceededException(
      "Code review agent exceeded the maximum of 15 ReAct loop iterations while the model was "
        + "still requesting tool calls.");

    // Act
    ResponseEntity<ApiError> response = handler.handleIterationLimitExceeded(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().code()).isEqualTo("AGENT_ITERATION_LIMIT_EXCEEDED");
    assertThat(response.getBody().message())
      .isEqualTo("The PR review could not be completed within the configured iteration limit.")
      .doesNotContain(exception.getMessage());
  }

  // ---------------------------------------------------------------------------------------------
  // Row 4: TransientAiException / NonTransientAiException -> 502 / AI_PROVIDER_FAILURE
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnBadGatewayWithStaticMessage_whenTransientAiExceptionIsThrown() {
    // Arrange
    TransientAiException exception =
      new TransientAiException("Authorization: secret-provider-token");

    // Act
    ResponseEntity<ApiError> response = handler.handleAiProviderFailure(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody().code()).isEqualTo("AI_PROVIDER_FAILURE");
    assertThat(response.getBody().message())
      .isEqualTo("The AI provider failed to process the request.")
      .doesNotContain(exception.getMessage());
  }

  @Test
  void shouldReturnBadGatewayWithStaticMessage_whenNonTransientAiExceptionIsThrown() {
    // Arrange
    NonTransientAiException exception =
      new NonTransientAiException("permanent provider failure: model deprecated");

    // Act
    ResponseEntity<ApiError> response = handler.handleAiProviderFailure(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody().code()).isEqualTo("AI_PROVIDER_FAILURE");
    assertThat(response.getBody().message())
      .isEqualTo("The AI provider failed to process the request.")
      .doesNotContain(exception.getMessage());
  }

  // ---------------------------------------------------------------------------------------------
  // Framework-dispatch rows: HttpMessageNotReadableException -> 400 / MALFORMED_REQUEST,
  // HttpRequestMethodNotSupportedException -> 405 / METHOD_NOT_ALLOWED,
  // HttpMediaTypeNotSupportedException -> 415 / UNSUPPORTED_MEDIA_TYPE. Each of these three
  // exception types would otherwise be intercepted by the catch-all handleUnexpectedException below
  // (Row 8), since ExceptionHandlerExceptionResolver runs before Spring's own
  // DefaultHandlerExceptionResolver (see this class's own Javadoc) - these tests prove each now
  // gets its own, correct status/code, and is logged below ERROR.
  // ---------------------------------------------------------------------------------------------

  // Row 5: HttpMessageNotReadableException -> 400 / MALFORMED_REQUEST

  @Test
  void shouldReturnBadRequestWithMalformedRequestCode_whenRequestBodyIsNotValidJson() {
    // Arrange
    HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
      "JSON parse error: Unexpected character ('{' (code 123))", null);

    // Act
    ResponseEntity<ApiError> response = handler.handleMalformedRequest(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo("MALFORMED_REQUEST");
    assertThat(response.getBody().message())
      .isEqualTo("The request body could not be parsed as valid JSON.")
      .doesNotContain(exception.getMessage());
    assertThat(response.getBody().violations()).isEmpty();
  }

  @Test
  void shouldNotLogAtErrorLevel_whenRequestBodyIsMalformedJson() {
    // Arrange: the DEBUG-level log call this handler makes is otherwise suppressed by the default
    // (INFO) root logger level in this test environment - lowered explicitly here, and restored in
    // finally, purely so the assertion below can observe the log record at all.
    Logger logbackLogger = (Logger) LoggerFactory.getLogger(CodeReviewExceptionHandler.class);
    Level originalLevel = logbackLogger.getLevel();
    logbackLogger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logbackLogger.addAppender(appender);

    try {
      HttpMessageNotReadableException exception =
        new HttpMessageNotReadableException("JSON parse error: Unexpected end-of-input", null);

      // Act
      handler.handleMalformedRequest(exception);

      // Assert: a client mistake must never be logged as though it were a server fault (R8).
      assertThat(appender.list).isNotEmpty();
      assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    } finally {
      logbackLogger.detachAppender(appender);
      logbackLogger.setLevel(originalLevel);
    }
  }

  // Row 6: HttpRequestMethodNotSupportedException -> 405 / METHOD_NOT_ALLOWED

  @Test
  void shouldReturnMethodNotAllowedWithStaticMessage_whenHttpMethodIsUnsupported() {
    // Arrange
    HttpRequestMethodNotSupportedException exception =
      new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

    // Act
    ResponseEntity<ApiError> response = handler.handleMethodNotSupported(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    assertThat(response.getBody().message())
      .isEqualTo("The HTTP method used is not supported for this endpoint.")
      .doesNotContain(exception.getMessage());
    assertThat(response.getBody().violations()).isEmpty();
  }

  @Test
  void shouldNotLogAtErrorLevel_whenHttpMethodIsUnsupported() {
    // Arrange: see shouldNotLogAtErrorLevel_whenRequestBodyIsMalformedJson's comment above for why
    // the logger level is lowered explicitly here.
    Logger logbackLogger = (Logger) LoggerFactory.getLogger(CodeReviewExceptionHandler.class);
    Level originalLevel = logbackLogger.getLevel();
    logbackLogger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logbackLogger.addAppender(appender);

    try {
      HttpRequestMethodNotSupportedException exception =
        new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

      // Act
      handler.handleMethodNotSupported(exception);

      // Assert
      assertThat(appender.list).isNotEmpty();
      assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    } finally {
      logbackLogger.detachAppender(appender);
      logbackLogger.setLevel(originalLevel);
    }
  }

  // Row 7: HttpMediaTypeNotSupportedException -> 415 / UNSUPPORTED_MEDIA_TYPE

  @Test
  void shouldReturnUnsupportedMediaTypeWithStaticMessage_whenContentTypeIsNotSupported() {
    // Arrange
    HttpMediaTypeNotSupportedException exception =
      new HttpMediaTypeNotSupportedException("Content-Type 'text/plain' is not supported");

    // Act
    ResponseEntity<ApiError> response = handler.handleUnsupportedMediaType(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(response.getBody().message())
      .isEqualTo("The request's Content-Type is not supported for this endpoint.")
      .doesNotContain(exception.getMessage());
    assertThat(response.getBody().violations()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Row 8: any other Exception -> 500 / INTERNAL_ERROR
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnInternalServerErrorWithStaticMessage_whenAnUnmappedExceptionIsThrown() {
    // Arrange
    RuntimeException exception = new RuntimeException("private internal implementation detail");

    // Act
    ResponseEntity<ApiError> response = handler.handleUnexpectedException(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message())
      .isEqualTo("An unexpected error occurred while processing the request.")
      .doesNotContain("private internal implementation detail")
      .doesNotContain(exception.getClass().getName());
  }
}
