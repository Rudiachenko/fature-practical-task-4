package com.epam.docqachatbot.exception;

import com.epam.docqachatbot.api.model.ApiError;
import com.epam.docqachatbot.api.model.ApiViolation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void shouldReturnSortedViolations_whenValidationContainsFieldAndObjectErrors() {
    // Arrange
    BeanPropertyBindingResult bindingResult =
      new BeanPropertyBindingResult(new Object(), "request");
    bindingResult.addError(new ObjectError("request", "Request is invalid"));
    bindingResult.addError(new FieldError("request", "input", "Input must not be blank"));
    MethodArgumentNotValidException exception =
      new MethodArgumentNotValidException(null, bindingResult);

    // Act
    ResponseEntity<ApiError> response = handler.handleValidationException(exception);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo(new ApiError(
      "INVALID_REQUEST",
      "Request validation failed",
      List.of(
        new ApiViolation("input", "Input must not be blank"),
        new ApiViolation("request", "Request is invalid")
      )
    ));
  }
}
