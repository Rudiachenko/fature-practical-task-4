package com.epam.codereviewagent.exception;

/**
 * Thrown when {@code CodeReviewReactAgent}'s ReAct loop exhausts the configured
 * {@code app.code-review.max-iterations} budget while the model is still requesting tool calls
 * (Experiment #7, "Loop non-termination"). This is a defined, honest failure outcome, not a silent
 * truncation: the loop never returns a partial or fabricated answer once its iteration budget runs
 * out — it aborts and reports exactly this instead.
 */
public class AgentIterationLimitExceededException extends RuntimeException {

  public AgentIterationLimitExceededException(String message) {
    super(message);
  }

  public AgentIterationLimitExceededException(String message, Throwable cause) {
    super(message, cause);
  }
}
