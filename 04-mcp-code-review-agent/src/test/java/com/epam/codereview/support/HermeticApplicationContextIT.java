package com.epam.codereview.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.codereview.McpCodeReviewAgentApplication;
import com.epam.codereview.api.model.UserRequest;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * The module's own "boot it first" proof, mirroring {@code 03-code-review-agent}'s equivalent
 * {@code HermeticApplicationContextIT}: boots the entire real {@link
 * McpCodeReviewAgentApplication} context - real {@code CodeReviewController}, real {@code
 * CodeReviewExceptionHandler}, real {@code PrReferenceResolver}, real {@code AgentConfig} beans,
 * real {@code CodeReviewTools}/{@code ConventionService}, real {@code CodeReviewReactAgent} - with
 * only {@code ChatModel} swapped for a hermetic {@link RecordingChatModel} and MCP swapped for an
 * empty-client-list {@code SyncMcpToolCallbackProvider} (both supplied by {@link
 * HermeticCodeReviewTestConfiguration}), on a random port, with no network access and no {@code
 * AZURE_OPEN_AI_*}/{@code GITHUB_TOKEN} required.
 *
 * <p>Adapted from module 3's version to this module's own contract (per {@code context/PLAN.md}'s
 * Architecture Notes): {@code interact(String)} is single-phase - no phase-2 structured-output
 * call - so a {@link RecordingChatModel} response with no tool calls makes the ReAct loop return
 * that exact text verbatim on iteration 1; there is no {@code CodeReviewResponse} JSON contract to
 * parse here, unlike module 3.
 */
@ActiveProfiles("test")
@SpringBootTest(
    classes = {McpCodeReviewAgentApplication.class, HermeticCodeReviewTestConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HermeticApplicationContextIT {

  private static final Set<String> EXPECTED_LOCAL_TOOL_NAMES =
      Set.of("retrieveCodeLanguage", "retrieveCodeConvention");

  @LocalServerPort
  private int port;

  @Value("${spring.application.name}")
  private String applicationName;

  @Autowired
  private RecordingChatModel recordingChatModel;

  @Autowired
  private ChatOptions chatOptions;

  @Test
  void shouldResolveConfiguredApplicationName_fromSpringApplicationNameProperty() {
    // Arrange & Act: applicationName is injected from spring.application.name at field
    // construction; nothing further to arrange/act for this boot-configuration check.

    // Assert
    assertThat(applicationName).isEqualTo("code-review-agent");
  }

  @Test
  void shouldKeepTheApplicationClassInTheCodeReviewPackage_notTheOldCodeReviewAgentPackage() {
    // Arrange & Act: package identity is a compile-time fact of the class itself - there is
    // nothing further to arrange/act for this regression guard.

    // Assert: a real structural check against McpCodeReviewAgentApplication's own package,
    // unlike the applicationName check above (which only proves a YAML string, with no
    // structural connection to Java package identity) - this fails if this module's classes
    // were ever accidentally moved into com.epam.codereviewagent, module 3's sibling package.
    assertThat(McpCodeReviewAgentApplication.class.getPackageName()).isEqualTo("com.epam.codereview");
  }

  @Test
  void shouldReturn200OkWithTheRecordingChatModelsCannedText_whenPostingAValidPrReferenceAgainstTheFullRealContext() {
    // Arrange
    recordingChatModel.setResponse("Hermetic canned PR review summary for the full-context boot proof.");
    RestClient restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();

    // Act
    ResponseEntity<String> response = restClient.post()
        .uri("/code-review")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UserRequest("https://github.com/octocat/hello-world/pull/1"))
        .retrieve()
        .toEntity(String.class);

    // Assert
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody())
        .isEqualTo("Hermetic canned PR review summary for the full-context boot proof.");
  }

  @Test
  void shouldAttachExactlyTheTwoLocalToolCallbacksAndZeroMcpToolCallbacks_whenTheRealContextIsBooted() {
    // Arrange & Act: chatOptions is the real AgentConfig#chatOptions bean, already built during
    // context refresh from the real CodeReviewTools plus this profile's empty-client-list
    // SyncMcpToolCallbackProvider - nothing further to arrange/act.
    Set<String> toolNames = ((AzureOpenAiChatOptions) chatOptions).getToolCallbacks().stream()
        .map(callback -> callback.getToolDefinition().name())
        .collect(Collectors.toSet());

    // Assert
    assertThat(toolNames).isEqualTo(EXPECTED_LOCAL_TOOL_NAMES);
  }
}
