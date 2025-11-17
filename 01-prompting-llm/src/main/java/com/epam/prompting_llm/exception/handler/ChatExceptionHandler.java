package com.epam.prompting_llm.exception.handler;

import com.epam.prompting_llm.api.model.ErrorDetails;
import com.epam.prompting_llm.api.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

@ControllerAdvice
public class ChatExceptionHandler {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(new ErrorResponse("Error processing request: " + e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<List<ErrorDetails>> handleValidationExceptions(
    MethodArgumentNotValidException ex) {
    final List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
    List<ErrorDetails> errorDetails = fieldErrors.stream()
      .map(fieldError -> new ErrorDetails(
        fieldError.getField(), fieldError.getDefaultMessage())).toList();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(errorDetails);
  }

}
