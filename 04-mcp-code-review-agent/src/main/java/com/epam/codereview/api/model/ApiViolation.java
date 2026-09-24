package com.epam.codereview.api.model;

/**
 * A single field-level validation violation, as reported for
 * {@code MethodArgumentNotValidException} by {@code CodeReviewExceptionHandler}. Same shape as
 * {@code 02-rag}'s {@code ApiViolation}.
 *
 * @param field   the name of the offending request field (or the object name for a cross-field
 *                error)
 * @param message the constraint's own default violation message (e.g., {@code "must not be
 *                blank"})
 */
public record ApiViolation(
  String field,
  String message) {
}
