package com.epam.codereviewagent.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.codereviewagent.CodeReviewAgentApplication;
import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.UserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * The module's own "boot it first" proof (Architecture Note A3), executed as part of Increment 3
 * rather than deferred to an end-of-workflow audit: boots the entire real {@link
 * CodeReviewAgentApplication} context — real {@code AgentConfig}, real {@code
 * CodeReviewTools}, real {@code ConventionService}, real {@code
 * RepositoryPathResolver}/{@code FileUtils} — with only {@code ChatModel} swapped for a
 * hermetic {@link RecordingChatModel}, on a random port, with no network
 * access and no {@code AZURE_OPEN_AI_KEY}/{@code AZURE_OPEN_AI_ENDPOINT} required.
 *
 * <p><b>Tightened by Increment 5</b> now that {@code CodeReviewReactAgent.interact(String)} is
 * actually implemented: the request-handling test configures {@link #recordingChatModel}'s canned
 * response to a minimal, valid {@code CodeReviewResponse} JSON document (so phase 2's real {@link
 * com.epam.codereviewagent.service.CodeReviewStructuredOutputConverter} — wired into the real
 * context, not mocked — successfully parses it) and asserts {@code 200 OK} with real,
 * deserialized
 * {@code CodeReviewResponse} content, not merely "a response was received".
 */
@ActiveProfiles("test")
@SpringBootTest(
  classes = {CodeReviewAgentApplication.class, HermeticCodeReviewTestConfiguration.class},
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HermeticApplicationContextIT {

  @LocalServerPort
  private int port;

  @Value("${spring.application.name}")
  private String applicationName;

  @Autowired
  private RecordingChatModel recordingChatModel;

  @Test
  void shouldResolveConfiguredApplicationName_notTheOldMcpCodeReviewAgentName() {
    assertThat(applicationName).isEqualTo("code-review-agent");
  }

  @Test
  void shouldReceive200OkWithParseableCodeReviewResponse_whenPostingARealCodeReviewRequestAgainstTheFullRealContext() {
    recordingChatModel.setResponse(
      "{\"review\":\"Hermetic canned review for the full-context boot proof.\",\"findings\":[],"
        + "\"truncated\":false}");
    RestClient restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();

    ResponseEntity<CodeReviewResponse> response = restClient.post()
      .uri("/code-review")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new UserRequest("nested/nested-file.txt"))
      .retrieve()
      .toEntity(CodeReviewResponse.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().review())
      .isEqualTo("Hermetic canned review for the full-context boot proof.");
    assertThat(response.getBody().findings()).isEmpty();
    assertThat(response.getBody().truncated()).isFalse();
  }
}
