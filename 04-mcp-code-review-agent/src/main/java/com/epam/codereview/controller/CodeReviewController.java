package com.epam.codereview.controller;

import com.epam.codereview.api.CodeReviewApi;
import com.epam.codereview.api.model.UserRequest;
import com.epam.codereview.service.CodeReviewReactAgent;
import com.epam.codereview.util.PrReferenceResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs {@link PrReferenceResolver}'s request-boundary gate against {@code request.userInput()}
 * before ever invoking {@link CodeReviewReactAgent#interact(String)} - a {@code userInput} with no
 * PR-shaped signal at all is rejected with a deterministic 400
 * {@code PrReferenceNotFoundException} (mapped by {@code CodeReviewExceptionHandler}) with zero
 * {@code ChatModel} calls spent on it. Mirrors {@code 03-code-review-agent}'s own
 * {@code CodeReviewController}, which enforces its equivalent path-security pre-check the same way.
 */
@RestController
@RequiredArgsConstructor
public class CodeReviewController implements CodeReviewApi {

  private final PrReferenceResolver prReferenceResolver;
  private final CodeReviewReactAgent reviewReactAgent;

  /**
   * Validates the PR-reference boundary, then runs the review (see this class's own Javadoc).
   */
  @Override
  public ResponseEntity<String> processUserQuery(UserRequest request) {
    prReferenceResolver.validatePrReferencePresent(request.userInput());
    return ResponseEntity.ok(reviewReactAgent.interact(request.userInput()));
  }
}

