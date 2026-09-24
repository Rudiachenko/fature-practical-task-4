package com.epam.codereview.exception;

/**
 * Thrown by {@link com.epam.codereview.service.CodeReviewReactAgent#interact(String)} when its
 * ReAct loop exhausts the configured {@code app.code-review.max-iterations} budget while the model
 * is still requesting tool calls. This is a defined, honest failure outcome, not a silent
 * truncation: the loop never returns an incomplete answer once its iteration budget runs out - it
 * aborts and reports exactly this instead.
 */
public class AgentIterationLimitExceededException extends RuntimeException {

  public AgentIterationLimitExceededException(String message) {
    super(message);
  }

  public AgentIterationLimitExceededException(String message, Throwable cause) {
    super(message, cause);
  }
}
