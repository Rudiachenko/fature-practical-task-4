package com.epam.codereview.controller;

import com.epam.codereview.api.CodeReviewApi;
import com.epam.codereview.api.model.UserRequest;
import com.epam.codereview.service.CodeReviewReactAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CodeReviewController implements CodeReviewApi {

  private final CodeReviewReactAgent reviewReactAgent;

  @Override
  public ResponseEntity<String> processUserQuery(UserRequest request) {
    return ResponseEntity.ok(reviewReactAgent.interact(request.userInput()));
  }
}

