package com.epam.codereviewagent.controller;

import com.epam.codereviewagent.api.CodeReviewApi;
import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.UserRequest;
import com.epam.codereviewagent.exception.PathSecurityViolationException;
import com.epam.codereviewagent.service.CodeReviewReactAgent;
import com.epam.codereviewagent.util.RepositoryPathResolver;
import lombok.RequiredArgsConstructor;
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
 */
@RestController
@RequiredArgsConstructor
public class CodeReviewController implements CodeReviewApi {

  private final RepositoryPathResolver repositoryPathResolver;
  private final CodeReviewReactAgent reviewReactAgent;

  @Override
  public ResponseEntity<CodeReviewResponse> processUserQuery(UserRequest request) {
    repositoryPathResolver.validateSecurityBoundary(request.userInput());
    return ResponseEntity.ok(reviewReactAgent.interact(request.userInput()));
  }
}

