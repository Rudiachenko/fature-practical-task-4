package com.epam.codereviewagent.controller;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.UserRequest;
import com.epam.codereviewagent.exception.AgentIterationLimitExceededException;
import com.epam.codereviewagent.exception.AgentOutputParsingException;
import com.epam.codereviewagent.exception.CodeReviewExceptionHandler;
import com.epam.codereviewagent.exception.FileNotFoundInRepositoryException;
import com.epam.codereviewagent.service.CodeReviewReactAgent;
import com.epam.codereviewagent.util.RepositoryPathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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

/**
 * End-to-end proof, through the real {@link CodeReviewController} and the real
 * {@link CodeReviewExceptionHandler} wired together via MockMvc's standalone setup, that the advice
 * is actually applied to the controller's request path - not merely unit-tested in isolation (this
 * increment's own Test Strategy). {@link CodeReviewReactAgent} is mocked, since triggering most of
 * these exceptions through the real ReAct loop would require a live model call; the point of most of
 * these tests is to prove the exception-to-response wiring, which does not depend on how the
 * exception was produced.
 *
 * <p>{@link RepositoryPathResolver} is real (bound to the same {@code fixtures/repo-root} fixture
 * tree Increment 1 established), not mocked: {@code CodeReviewController}'s own security pre-check
 * (retry 1, code review High finding) genuinely runs {@link RepositoryPathResolver#validateSecurityBoundary(String)}
 * against every request, so a path-traversal/absolute-path {@code userInput} is rejected by the real
 * production security rule itself, before {@code reviewReactAgent.interact(...)} is ever called - see
 * the "security pre-check" section below for the effect-verified proof of that ordering.
 *
 * <p>Uses a standalone {@code MockMvc} setup (no {@code @SpringBootTest}/embedded servlet container),
 * consistent with this session's established environment limitation
 * (loopback sockets are unavailable; {@code @WebMvcTest}/standalone MockMvc need none).
 */
class CodeReviewControllerTest {

  private static final String FIXTURE_ROOT = "src/test/resources/fixtures/repo-root";

  private final CodeReviewReactAgent reviewReactAgent = mock(CodeReviewReactAgent.class);
  private final RepositoryPathResolver repositoryPathResolver = new RepositoryPathResolver(FIXTURE_ROOT);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders
      .standaloneSetup(new CodeReviewController(repositoryPathResolver, reviewReactAgent))
      .setControllerAdvice(new CodeReviewExceptionHandler())
      .build();
  }

  @Test
  void shouldReturnOkWithReviewBody_whenAgentCompletesSuccessfully() throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any()))
      .thenReturn(new CodeReviewResponse("No issues found.", List.of(), false));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("src/main/java/com/example/Foo.java")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.review").value("No issues found."))
      .andExpect(jsonPath("$.findings").isArray())
      .andExpect(jsonPath("$.truncated").value(false));
  }

  @Test
  void shouldReturnBadRequestWithPathSecurityViolationCode_whenUserInputAttemptsPathTraversal()
    throws Exception {
    // Act & Assert: CodeReviewController's own security pre-check (retry 1, code review High finding)
    // rejects this input itself, via the real RepositoryPathResolver - reviewReactAgent is never
    // stubbed to throw here anymore, since it is never reached at all (proved below).
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("../../etc/passwd")))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("PATH_SECURITY_VIOLATION"))
      .andExpect(content().string(org.hamcrest.Matchers.not(
        org.hamcrest.Matchers.containsString("../../etc/passwd"))));
    verify(reviewReactAgent, never()).interact(any());
  }

  // ---------------------------------------------------------------------------------------------
  // Security pre-check (retry 1, code review High finding): RepositoryPathResolver.validateSecurityBoundary
  // runs in CodeReviewController before reviewReactAgent.interact(...) is ever called, so a caller can
  // observe the repository-root security boundary via a deterministic HTTP status code alone, with no
  // model call spent on input that is rejected by construction. These tests prove that ordering is
  // effect-verified (the mocked agent's interact(...) is never invoked for a rejected path), not just
  // status-verified, and that an ordinary relative path/directory path/non-existent-but-in-root path
  // all still reach the agent unchanged.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldRejectBeforeInvokingTheAgent_whenUserInputIsAnAbsoluteWindowsPath() throws Exception {
    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("C:\\Windows\\System32\\drivers\\etc\\hosts")))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("PATH_SECURITY_VIOLATION"))
      .andExpect(content().string(org.hamcrest.Matchers.not(
        org.hamcrest.Matchers.containsString("C:\\Windows"))));
    verify(reviewReactAgent, never()).interact(any());
  }

  @Test
  void shouldReachTheAgent_whenUserInputIsAnOrdinaryInRootRelativeFilePath() throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any()))
      .thenReturn(new CodeReviewResponse("No issues found.", List.of(), false));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("nested/nested-file.txt")))
      .andExpect(status().isOk());
    verify(reviewReactAgent).interact("nested/nested-file.txt");
  }

  @Test
  void shouldReachTheAgent_whenUserInputIsAnInRootRelativeDirectoryPath() throws Exception {
    // Arrange: a directory (not a regular file) must still pass the pre-check and reach the agent -
    // this is the ticket's own "a relative file or repository path" wording (repository-exploration
    // use case), not merely single-file review.
    when(reviewReactAgent.interact(any()))
      .thenReturn(new CodeReviewResponse("No issues found.", List.of(), false));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("nested")))
      .andExpect(status().isOk());
    verify(reviewReactAgent).interact("nested");
  }

  @Test
  void shouldReachTheAgent_whenUserInputIsASyntacticallyValidButNonExistentInRootPath() throws Exception {
    // Arrange: existence is deliberately out of scope for the pre-check - Experiment #4 depends on the
    // agent itself handling a missing file, not on it being rejected upfront as a security concern.
    when(reviewReactAgent.interact(any())).thenThrow(new FileNotFoundInRepositoryException(
      "File not found in repository: nested/does-not-exist.txt"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("nested/does-not-exist.txt")))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    verify(reviewReactAgent).interact("nested/does-not-exist.txt");
  }

  @Test
  void shouldReturnNotFoundWithFileNotFoundCode_whenUserInputReferencesAValidLookingButMissingPath()
    throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any())).thenThrow(new FileNotFoundInRepositoryException(
      "File not found in repository: src/main/java/com/example/DoesNotExist.java"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("src/main/java/com/example/DoesNotExist.java")))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
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
  }

  @Test
  void shouldReturnInternalServerErrorWithIterationLimitCode_whenAgentExhaustsMaxIterations()
    throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any())).thenThrow(
      new AgentIterationLimitExceededException("Exhausted maxIterations=8"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("src/main/java/com/example/Foo.java")))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.code").value("AGENT_ITERATION_LIMIT_EXCEEDED"));
  }

  @Test
  void shouldReturnBadGatewayWithAgentOutputInvalidCode_whenModelOutputCannotBeParsed()
    throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any()))
      .thenThrow(new AgentOutputParsingException("Malformed JSON"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("src/main/java/com/example/Foo.java")))
      .andExpect(status().isBadGateway())
      .andExpect(jsonPath("$.code").value("AGENT_OUTPUT_INVALID"));
  }

  @Test
  void shouldReturnBadGatewayWithAiProviderFailureCode_whenTheChatModelThrowsATransientAiException()
    throws Exception {
    // Arrange
    when(reviewReactAgent.interact(any()))
      .thenThrow(new TransientAiException("simulated DIAL outage"));

    // Act & Assert
    mockMvc.perform(post("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestJson("src/main/java/com/example/Foo.java")))
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
        .content(requestJson("src/main/java/com/example/Foo.java")))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }

  // ---------------------------------------------------------------------------------------------
  // Framework-dispatch rows (retry 1, code review High finding), proved end-to-end through the real
  // controller/advice pair, not merely at the handler-unit level (CodeReviewExceptionHandlerTest
  // covers the handler-unit level; these prove the whole MockMvc dispatch actually reaches them).
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnBadRequestWithMalformedRequestCode_whenRequestBodyIsNotValidJson() throws Exception {
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
        .content(requestJson("nested/nested-file.txt")))
      .andExpect(status().isUnsupportedMediaType())
      .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    verify(reviewReactAgent, never()).interact(any());
  }

  private String requestJson(String userInput) throws Exception {
    return objectMapper.writeValueAsString(new UserRequest(userInput));
  }
}
