package com.epam.codereviewagent.api.model;

import java.util.List;

/**
 * Machine-readable error body returned by {@code CodeReviewExceptionHandler} for every mapped
 * failure mode. Same shape as {@code 02-rag}'s {@code ApiError} (the in-repo precedent this
 * increment follows): a stable {@code code}, a human-readable {@code message}, and an optional list
 * of field-level {@code violations} (populated only for request-validation failures).
 *
 * @param code       a stable, machine-readable error code (e.g., {@code "PATH_SECURITY_VIOLATION"})
 * @param message    a static, human-readable description of the failure; never the raw exception's
 *                    own {@code getMessage()} text, so this body can never echo internal detail
 * @param violations field-level validation violations; empty for every non-validation failure
 */
public record ApiError(
  String code,
  String message,
  List<ApiViolation> violations) {

  public ApiError {
    violations = violations == null ? List.of() : List.copyOf(violations);
  }
}
