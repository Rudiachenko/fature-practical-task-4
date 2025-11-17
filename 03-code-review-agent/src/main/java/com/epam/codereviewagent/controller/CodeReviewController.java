package com.epam.codereviewagent.controller;

import com.epam.codereviewagent.api.CodeReviewApi;
import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.UserRequest;
import com.epam.codereviewagent.service.CodeReviewReactAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CodeReviewController implements CodeReviewApi {

  private final CodeReviewReactAgent reviewReactAgent;

  @Override
  public ResponseEntity<CodeReviewResponse> processUserQuery(UserRequest request) {
    return ResponseEntity.ok(reviewReactAgent.interact(request.userInput()));
  }
}

