package com.epam.codereviewagent.api;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.UserRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The module's REST contract: a single {@code POST /code-review} endpoint that runs the ReAct
 * code review agent against a caller-supplied repository path and returns a
 * {@link CodeReviewResponse}.
 */
@RequestMapping(path = "/code-review")
public interface CodeReviewApi {

  /**
   * Runs a full code review for {@code request.userInput()} and returns the resulting
   * {@link CodeReviewResponse}.
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<CodeReviewResponse> processUserQuery(@Valid @RequestBody UserRequest request);

}
