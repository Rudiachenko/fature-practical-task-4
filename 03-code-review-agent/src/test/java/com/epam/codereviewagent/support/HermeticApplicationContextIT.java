package com.epam.codereviewagent.support;

import com.epam.codereviewagent.CodeReviewAgentApplication;
import com.epam.codereviewagent.api.model.UserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module's own "boot it first" proof (Architecture Note A3), executed as part of Increment 3
 * rather than deferred to an end-of-workflow audit: boots the entire real {@link
 * CodeReviewAgentApplication} context — real {@code AgentConfig}, real {@code CodeReviewTools}, real
 * {@code ConventionService}, real {@code RepositoryPathResolver}/{@code FileUtils} — with only {@code
 * ChatModel} swapped for a hermetic {@link RecordingChatModel}, on a random port, with no network
 * access and no {@code AZURE_OPEN_AI_KEY}/{@code AZURE_OPEN_AI_ENDPOINT} required.
 *
 * <p>{@code CodeReviewReactAgent.interact(String)} is not implemented until Increment 5 (it currently
 * returns {@code null}), so this increment only asserts that a real HTTP response is actually received
 * from a real {@code POST /code-review} call against the real controller/advice chain, and that {@code
 * spring.application.name} resolves correctly at the full-context level. Increment 5 tightens the
 * response-content assertion once {@code interact} is implemented.
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

  @Test
  void shouldResolveConfiguredApplicationName_notTheOldMcpCodeReviewAgentName() {
    assertThat(applicationName).isEqualTo("code-review-agent");
  }

  @Test
  void shouldReceiveAnHttpResponse_whenPostingARealCodeReviewRequestAgainstTheFullRealContext() {
    RestClient restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();

    HttpStatusCode statusCode = restClient.post()
      .uri("/code-review")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new UserRequest("nested/nested-file.txt"))
      .exchange((request, response) -> response.getStatusCode());

    assertThat(statusCode).isNotNull();
  }
}
