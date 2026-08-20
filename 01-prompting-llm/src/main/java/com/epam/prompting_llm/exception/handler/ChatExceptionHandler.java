package com.epam.prompting_llm.exception.handler;

import com.epam.prompting_llm.api.model.ErrorDetails;
import com.epam.prompting_llm.api.model.ErrorResponse;
import com.epam.prompting_llm.exception.InvalidSamplingParametersException;
import com.epam.prompting_llm.exception.StructuredOutputException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

/** Maps validation, provider, and structured-output failures to stable public error responses. */
@Slf4j
@ControllerAdvice
public class ChatExceptionHandler {

  @ExceptionHandler(InvalidSamplingParametersException.class)
  public ResponseEntity<ErrorResponse> handleInvalidSamplingParameters(
    InvalidSamplingParametersException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(new ErrorResponse(exception.getMessage()));
  }

  @ExceptionHandler(StructuredOutputException.class)
  public ResponseEntity<ErrorResponse> handleStructuredOutputException(
    StructuredOutputException exception) {
    log.error("Structured model response could not be processed, type={}",
      exception.getClass().getName());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
      .body(new ErrorResponse("Model returned an invalid structured response"));
  }

  @ExceptionHandler({TransientAiException.class, NonTransientAiException.class})
  public ResponseEntity<ErrorResponse> handleAiException(Exception exception) {
    log.error("AI provider request failed, type={}", exception.getClass().getName());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
      .body(new ErrorResponse("AI provider request failed"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception exception) {
    log.error("Unexpected chat request failure, type={}", exception.getClass().getName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(new ErrorResponse("Unable to process the chat request"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<List<ErrorDetails>> handleValidationExceptions(
    MethodArgumentNotValidException ex) {
    List<ErrorDetails> errorDetails = ex.getBindingResult().getAllErrors().stream()
      .map(this::toErrorDetails)
      .toList();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(errorDetails);
  }

  private ErrorDetails toErrorDetails(ObjectError error) {
    String name = error instanceof FieldError fieldError
      ? fieldError.getField()
      : error.getObjectName();
    return new ErrorDetails(name, error.getDefaultMessage());
  }
}
