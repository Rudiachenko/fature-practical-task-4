package com.epam.codereviewagent.controller;

import com.epam.codereviewagent.api.CodeReviewApi;
import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.UserRequest;
import com.epam.codereviewagent.event.CodeReviewCompletedEvent;
import com.epam.codereviewagent.exception.PathSecurityViolationException;
import com.epam.codereviewagent.service.CodeReviewReactAgent;
import com.epam.codereviewagent.util.RepositoryPathResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code context/PLAN.md} Increment 6 retry 1 (High finding, coordinator-decided fix): runs {@link
 * RepositoryPathResolver}'s security-only pre-check against {@code request.userInput()} before ever
 * invoking {@link CodeReviewReactAgent#interact(String)} - an out-of-root or malformed
 * {@code userInput} is rejected with a deterministic 400 {@link PathSecurityViolationException}
 * (mapped by {@code CodeReviewExceptionHandler}) with no model call spent on it at all. Existence and
 * "file vs directory" are deliberately left unchecked here (see
 * {@link RepositoryPathResolver#validateSecurityBoundary(String)}'s own Javadoc) so an ordinary
 * relative path, a relative directory path, and a syntactically valid but non-existent path all still
 * reach the agent unchanged.
 *
 * <p><b>Increment 7 retry 1 (code review Medium finding — "make the summary reachable from a live
 * review").</b> After {@link CodeReviewReactAgent#interact(String)} returns successfully, this
 * controller publishes a {@link CodeReviewCompletedEvent} carrying that same response via the generic
 * {@link ApplicationEventPublisher} - it holds no reference to {@code ExecutiveSummarySubAgent} or
 * {@code ExecutiveSummaryEventListener} anywhere (grep-verifiable), preserving the same structural
 * decoupling already established between this controller and the executive-summary sub-agent.
 * Publishing happens only after {@code interact(...)} has already produced {@code response}, so a
 * request that throws before completing a review never publishes anything.
 */
@RestController
@RequiredArgsConstructor
public class CodeReviewController implements CodeReviewApi {

  private final RepositoryPathResolver repositoryPathResolver;
  private final CodeReviewReactAgent reviewReactAgent;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  public ResponseEntity<CodeReviewResponse> processUserQuery(UserRequest request) {
    repositoryPathResolver.validateSecurityBoundary(request.userInput());
    CodeReviewResponse response = reviewReactAgent.interact(request.userInput());
    eventPublisher.publishEvent(new CodeReviewCompletedEvent(response));
    return ResponseEntity.ok(response);
  }
}

