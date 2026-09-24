package com.epam.codereview.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.codereview.api.model.UserRequest;
import com.epam.codereview.exception.PrReferenceNotFoundException;
import com.epam.codereview.service.CodeReviewReactAgent;
import com.epam.codereview.util.PrReferenceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Partial coverage for {@code context/PLAN.md} Increment 1: proves only that
 * {@link PrReferenceResolver#validatePrReferencePresent(String)} runs before
 * {@link CodeReviewReactAgent#interact(String)} is ever called. {@link CodeReviewReactAgent}
 * remains a TODO stub until Increment 4, so it is mocked here rather than exercised for real -
 * and no {@code CodeReviewExceptionHandler} exists yet (Increment 5), so this test asserts the
 * thrown exception directly rather than an HTTP response body/status.
 */
class CodeReviewControllerTest {

  private final CodeReviewReactAgent reviewReactAgent = mock(CodeReviewReactAgent.class);
  private final PrReferenceResolver prReferenceResolver = new PrReferenceResolver();
  private CodeReviewController controller;

  @BeforeEach
  void setUp() {
    controller = new CodeReviewController(prReferenceResolver, reviewReactAgent);
  }

  @Test
  void shouldThrowPrReferenceNotFoundExceptionAndNeverInvokeTheAgent_whenUserInputHasNoPrSignal() {
    // Arrange
    UserRequest request = new UserRequest("Please review my latest changes");

    // Act & Assert
    assertThatThrownBy(() -> controller.processUserQuery(request))
      .isInstanceOf(PrReferenceNotFoundException.class);
    verify(reviewReactAgent, never()).interact(any());
  }

  @Test
  void shouldInvokeTheAgentAndReturnItsResponse_whenUserInputCarriesAPrShapedSignal() {
    // Arrange
    UserRequest request = new UserRequest("https://github.com/octocat/Hello-World/pull/42");
    when(reviewReactAgent.interact(request.userInput())).thenReturn("Review complete.");

    // Act
    ResponseEntity<String> response = controller.processUserQuery(request);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("Review complete.");
    verify(reviewReactAgent).interact(request.userInput());
  }
}
