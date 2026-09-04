package com.epam.codereviewagent.event;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import java.util.Objects;

/**
 * Published by {@code com.epam.codereviewagent.controller.CodeReviewController} exactly once,
 * immediately after {@code reviewReactAgent.interact(...)} returns successfully for a {@code
 * POST /code-review} request — i.e. after a review has genuinely completed, carrying that same,
 * already-built {@link CodeReviewResponse}.
 *
 * <p><b>Structural decoupling (retry 1, code review Medium finding).</b> This event, not a direct
 * method call, is the only link between the primary review path ({@code CodeReviewController}/
 * {@code CodeReviewReactAgent}) and {@code ExecutiveSummaryEventListener}/{@code
 * ExecutiveSummarySubAgent}: {@code CodeReviewController} only knows how to publish this record
 * via the generic {@link org.springframework.context.ApplicationEventPublisher}; it holds no
 * reference to {@code ExecutiveSummarySubAgent} or {@code ExecutiveSummaryEventListener} at all
 * (grep-verifiable).
 * {@code CodeReviewReactAgent} is untouched by this change and still holds no reference to either
 * class, preserving the isolation already established in Increment 7's original implementation.
 */
public record CodeReviewCompletedEvent(CodeReviewResponse response) {

  public CodeReviewCompletedEvent {
    Objects.requireNonNull(response, "response must not be null");
  }
}
