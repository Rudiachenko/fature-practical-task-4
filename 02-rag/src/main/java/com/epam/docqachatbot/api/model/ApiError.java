package com.epam.docqachatbot.api.model;

import java.util.List;

public record ApiError(
  String code,
  String message,
  List<ApiViolation> violations) {

  public ApiError {
    violations = violations == null ? List.of() : List.copyOf(violations);
  }
}
