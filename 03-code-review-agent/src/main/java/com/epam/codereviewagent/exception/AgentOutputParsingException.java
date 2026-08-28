package com.epam.codereviewagent.exception;

/**
 * Thrown when the model's final structured-output call does not produce a valid
 * {@code CodeReviewResponse}: blank output, syntactically invalid JSON, JSON missing the
 * required {@code review} field, or JSON that otherwise fails to satisfy the response contract
 * (e.g. an out-of-enum {@code severity} value, or an impossible {@code Finding} line range).
 * <p>
 * This is a genuine, expected failure mode of a model-generated response, not a programming
 * error — see {@code CodeReviewStructuredOutputConverter} for where it is thrown and
 * {@code context/PROGRESS.md}'s Increment 4 entry for the adversarial payloads it is proven
 * against.
 */
public class AgentOutputParsingException extends RuntimeException {

  public AgentOutputParsingException(String message) {
    super(message);
  }

  public AgentOutputParsingException(String message, Throwable cause) {
    super(message, cause);
  }
}
