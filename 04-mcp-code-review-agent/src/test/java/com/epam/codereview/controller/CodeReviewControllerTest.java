package com.epam.codereview.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.codereview.api.model.UserRequest;
import com.epam.codereview.exception.AgentIterationLimitExceededException;
import com.epam.codereview.exception.CodeReviewExceptionHandler;
import com.epam.codereview.service.CodeReviewReactAgent;
import com.epam.codereview.util.PrReferenceResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Finalized (Increment 5) end-to-end proof, through the real {@link CodeReviewController} and the
 * real {@link CodeReviewExceptionHandler} wired together via MockMvc's standalone setup, that the
 * advice is actually applied to the controller's request path - not merely unit-tested in isolation.
 * {@link CodeReviewReactAgent} is mocked, since triggering most of these exceptions through the
 * real ReAct loop would require a live model call; the point of most of these tests is to prove the
 * exception-to-response wiring, which does not depend on how the exception was produced.
 *
 * <p>{@link PrReferenceResolver} is real (stateless, no fixture required): {@code
 * CodeReviewController}'s own pre-check genuinely runs {@link
 * PrReferenceResolver#validatePrReferencePresent(String)} against every request, so a
 * signal-free {@code userInput} is rejected by the real production rule itself, before {@code
 * reviewReactAgent.interact(...)} is ever called.
 *
 * <p>Uses a standalone {@code MockMvc} setup (no {@code @SpringBootTest}/embedded servlet
 * container) - the full-context wiring is covered separately by the hermetic Spring-context
 * integration test.
 *
 * <p>Supersedes Increment 1's partial version of this class (mocked agent, real resolver, direct
 * {@code controller.processUserQuery(...)} calls, no {@code MockMvc}) which existed only because
 * {@link CodeReviewExceptionHandler} did not exist yet to register as controller advice.
 */
class CodeReviewControllerTest {

  private final CodeReviewReactAgent reviewReactAgent = mock(CodeReviewReactAgent.class);
  private final PrReferenceResolver prReferenceResolver = new PrReferenceResolver();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders
      .standaloneSetup(new CodeReviewController(prReferenceResolver, reviewReactAgent))
      .setControllerAdvice(new CodeReviewExceptionHandler())
      .build();
  }

  @Test
  void shouldReturnOkWithThePlainReviewText_whenAgentCompletesSuccessfully() throws Exception {
    // Arrange
    String userInput = "https://github.com/octocat/Hello-World/pull/42";
    when(reviewReactAgent.interact(userInput)).thenReturn("Review complete.");

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson(userInput)))
      .andExpect(status().isOk())
      .andExpect(content().string("Review complete."));
    verify(reviewReactAgent).interact(userInput);
  }

  // ---------------------------------------------------------------------------------------------
  // Pre-check (PrReferenceResolver runs in CodeReviewController before reviewReactAgent.interact(...)
  // is ever called) and Bean Validation (@NotBlank on UserRequest.userInput) - proved
  // effect-verified (the mocked agent's interact(...) is never invoked), not just status-verified.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnBadRequestWithPrReferenceNotFoundCode_whenUserInputHasNoPrSignal()
    throws Exception {
    // Act & Assert: CodeReviewController's own pre-check rejects this input itself, via the real
    // PrReferenceResolver - reviewReactAgent is never stubbed to throw here, since it is never
    // reached at all.
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("Please review my latest changes")))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("PR_REFERENCE_NOT_FOUND"));
    verify(reviewReactAgent, never()).interact(any());
  }

  @Test
  void shouldReturnBadRequestWithValidationFailedCode_whenUserInputIsBlank() throws Exception {
    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("")))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
      .andExpect(jsonPath("$.violations[0].field").value("userInput"));
    verify(reviewReactAgent, never()).interact(any());
  }

  // ---------------------------------------------------------------------------------------------
  // Exceptions thrown by the (mocked) agent
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnInternalServerErrorWithIterationLimitCode_whenAgentExhaustsMaxIterations()
    throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any())).thenThrow(
      new AgentIterationLimitExceededException("Exhausted maxIterations=15"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("https://github.com/octocat/Hello-World/pull/42")))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.code").value("AGENT_ITERATION_LIMIT_EXCEEDED"));
  }

  @Test
  void shouldReturnBadGatewayWithAiProviderFailureCode_whenTheChatModelThrowsATransientAiException()
    throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any()))
      .thenThrow(new TransientAiException("simulated AI provider outage"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("https://github.com/octocat/Hello-World/pull/42")))
      .andExpect(status().isBadGateway())
      .andExpect(jsonPath("$.code").value("AI_PROVIDER_FAILURE"));
  }

  @Test
  void shouldReturnInternalServerErrorWithInternalErrorCode_whenAnUnmappedExceptionOccurs()
    throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any())).thenThrow(new RuntimeException("unexpected failure"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("https://github.com/octocat/Hello-World/pull/42")))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }

  // ---------------------------------------------------------------------------------------------
  // Framework-dispatch rows, proved end-to-end through the real controller/advice pair (see
  // CodeReviewExceptionHandler's own Javadoc for why these three rows exist as dedicated handlers;
  // CodeReviewExceptionHandlerTest covers the handler-unit level, these prove the whole MockMvc
  // dispatch actually reaches them).
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnBadRequestWithMalformedRequestCode_whenRequestBodyIsNotValidJson()
    throws Exception {
    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{ this is not valid json"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    verify(reviewReactAgent, never()).interact(any());
  }

  @Test
  void shouldReturnMethodNotAllowed_whenRequestUsesAnUnsupportedHttpMethod() throws Exception {
    // Act & Assert
    mockMvc.perform(get("/code-review"))
      .andExpect(status().isMethodNotAllowed())
      .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    verify(reviewReactAgent, never()).interact(any());
  }

  @Test
  void shouldReturnUnsupportedMediaType_whenRequestContentTypeIsNotJson() throws Exception {
    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.TEXT_PLAIN)
        .content(requestJson("https://github.com/octocat/Hello-World/pull/42")))
      .andExpect(status().isUnsupportedMediaType())
      .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    verify(reviewReactAgent, never()).interact(any());
  }

  private String requestJson(String userInput) throws Exception {
    return objectMapper.writeValueAsString(new UserRequest(userInput));
  }
}
