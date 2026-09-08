package com.epam.docqachatbot.exception;

import com.epam.docqachatbot.api.model.ApiError;
import com.epam.docqachatbot.api.model.ApiViolation;
import com.epam.docqachatbot.ingestion.DocumentIngestionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final String INVALID_REQUEST = "INVALID_REQUEST";
  private static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
  private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  @ExceptionHandler(DocumentIngestionException.class)
  public ResponseEntity<ApiError> handleDocumentIngestionException(
    DocumentIngestionException exception) {
    log.debug("Document ingestion request rejected, code={}", exception.code());
    return ResponseEntity.badRequest()
      .body(new ApiError(exception.code(), "Document request was rejected", List.of()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidationException(
    MethodArgumentNotValidException exception) {
    List<ApiViolation> violations = exception.getBindingResult().getAllErrors().stream()
      .map(this::toViolation)
      .sorted(Comparator.comparing(ApiViolation::field).thenComparing(ApiViolation::message))
      .toList();
    return ResponseEntity.badRequest()
      .body(new ApiError(INVALID_REQUEST, "Request validation failed", violations));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleMalformedRequest(
    HttpMessageNotReadableException exception) {
    log.debug("Malformed API request rejected, type={}", exception.getClass().getName());
    return ResponseEntity.badRequest()
      .body(new ApiError(MALFORMED_REQUEST, "Malformed JSON request", List.of()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
    log.error("Unexpected document QA request failure, type={}", exception.getClass().getName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(new ApiError(INTERNAL_ERROR, "Unable to process the request", List.of()));
  }

  private ApiViolation toViolation(ObjectError error) {
    String field = error instanceof FieldError fieldError
      ? fieldError.getField()
      : error.getObjectName();
    return new ApiViolation(field, error.getDefaultMessage());
  }
}
