package com.epam.codereview.exception;

/**
 * Thrown by {@link com.epam.codereview.util.PrReferenceResolver#validatePrReferencePresent(String)}
 * when a request's free-text {@code userInput} carries no PR-shaped signal at all - no GitHub PR
 * URL fragment, no {@code #<digits>} token, and no {@code owner/repo}-shaped slug. A weak or
 * incomplete signal never triggers this exception; only a complete absence of one does (see that
 * method's own Javadoc).
 *
 * <p>Only the single-argument constructor is declared: {@link com.epam.codereview.util.PrReferenceResolver}
 * never wraps an underlying cause (its own check is a pure input-shape test, not a delegation to
 * something that itself can fail), so a {@code (String, Throwable)} overload would have no call
 * site.
 */
public class PrReferenceNotFoundException extends RuntimeException {

  public PrReferenceNotFoundException(String message) {
    super(message);
  }
}
