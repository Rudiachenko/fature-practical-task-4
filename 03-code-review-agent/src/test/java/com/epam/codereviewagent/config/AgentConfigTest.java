package com.epam.codereviewagent.config;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import com.epam.codereviewagent.service.CodeReviewTools;
import com.epam.codereviewagent.service.ConventionService;
import com.epam.codereviewagent.support.RecordingChatModel;
import com.epam.codereviewagent.util.RepositoryPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Focused unit/slice test isolating {@link AgentConfig}'s two {@code @Bean} factory methods, called
 * directly against hand-constructed collaborators (no Spring context). Full-context wiring, including
 * proof that Spring actually invokes these bean methods unconditionally during a real context refresh
 * (Architecture Note A3), is separately proven by {@code HermeticApplicationContextIT}.
 */
class AgentConfigTest {

  private static final String FIXTURE_ROOT = "src/test/resources/fixtures/repo-root";
  private static final Set<String> EXPECTED_TOOL_NAMES = Set.of(
    "readFile", "exploreRepository", "retrieveCodeLanguage", "retrieveCodeConvention",
    "getCodebaseContext", "analyzeCodeMetrics");

  private final AgentConfig agentConfig = new AgentConfig();

  private CodeReviewTools codeReviewTools;

  @BeforeEach
  void setUp() {
    RepositoryPathResolver repositoryPathResolver = new RepositoryPathResolver(FIXTURE_ROOT);
    RecordingChatModel recordingChatModel = new RecordingChatModel();
    ConventionProperties conventionProperties = new ConventionProperties();
    conventionProperties.setResources(List.of());
    ConventionService conventionService = new ConventionService(conventionProperties);
    CodeReviewProperties codeReviewProperties = new CodeReviewProperties();
    codeReviewProperties.setMaxFileChars(20_000);
    codeReviewTools = new CodeReviewTools(
      repositoryPathResolver, recordingChatModel, conventionService, codeReviewProperties);
  }

  // --- repositoryPathResolver -------------------------------------------------------------------

  @Test
  void shouldBuildARepositoryPathResolverRootedAtTheConfiguredRepositoryRoot_whenBeanIsBuilt()
    throws java.io.IOException {
    CodeReviewProperties codeReviewProperties = new CodeReviewProperties();
    codeReviewProperties.setRepositoryRoot(FIXTURE_ROOT);

    RepositoryPathResolver resolver = agentConfig.repositoryPathResolver(codeReviewProperties);

    assertThat(java.nio.file.Files.isSameFile(resolver.getRoot(), java.nio.file.Path.of(FIXTURE_ROOT)))
      .isTrue();
    assertThat(resolver.resolveFile("top-level.txt")).exists();
  }

  // --- chatOptions ----------------------------------------------------------------------------

  @Test
  void shouldAttachExactlyTheSixCodeReviewToolsToolCallbacks_whenChatOptionsBeanIsBuilt() {
    ChatOptions chatOptions = agentConfig.chatOptions(codeReviewTools, defaultAzureOpenAiChatProperties());

    List<ToolCallback> toolCallbacks = ((AzureOpenAiChatOptions) chatOptions).getToolCallbacks();
    Set<String> toolNames = toolCallbacks.stream()
      .map(callback -> callback.getToolDefinition().name())
      .collect(Collectors.toSet());

    assertThat(toolNames).isEqualTo(EXPECTED_TOOL_NAMES);
  }

  @Test
  void shouldReflectTheConfiguredDeploymentNameProperty_notAHardcodedLiteral_whenChatOptionsBeanIsBuilt() {
    AzureOpenAiChatProperties azureOpenAiChatProperties = defaultAzureOpenAiChatProperties();
    azureOpenAiChatProperties.getOptions().setDeploymentName("overridden-non-default-deployment");

    ChatOptions chatOptions = agentConfig.chatOptions(codeReviewTools, azureOpenAiChatProperties);

    assertThat(((AzureOpenAiChatOptions) chatOptions).getDeploymentName())
      .isEqualTo("overridden-non-default-deployment");
  }

  @Test
  void shouldDisableInternalToolExecution_whenChatOptionsBeanIsBuilt() {
    ChatOptions chatOptions = agentConfig.chatOptions(codeReviewTools, defaultAzureOpenAiChatProperties());

    assertThat(((AzureOpenAiChatOptions) chatOptions).getInternalToolExecutionEnabled()).isFalse();
  }

  // --- chatModel --------------------------------------------------------------------------------

  @Test
  void shouldBuildANonNullAzureOpenAiChatModel_wiredToTheInjectedToolCallingManager_whenChatModelBeanIsBuilt() {
    OpenAIClientBuilder openAIClientBuilder = new OpenAIClientBuilder()
      .credential(new KeyCredential("hermetic-test-key"))
      .endpoint("http://127.0.0.1:1");
    ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);

    ChatModel chatModel = agentConfig.chatModel(
      openAIClientBuilder, toolCallingManager, defaultAzureOpenAiChatProperties());

    assertThat(chatModel).isNotNull().isInstanceOf(AzureOpenAiChatModel.class);
  }

  @Test
  void shouldUseTheConfiguredDeploymentNameAsDefaultOptions_whenChatModelBeanIsBuilt() {
    OpenAIClientBuilder openAIClientBuilder = new OpenAIClientBuilder()
      .credential(new KeyCredential("hermetic-test-key"))
      .endpoint("http://127.0.0.1:1");
    ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
    AzureOpenAiChatProperties azureOpenAiChatProperties = defaultAzureOpenAiChatProperties();
    azureOpenAiChatProperties.getOptions().setDeploymentName("chat-model-deployment");

    ChatModel chatModel = agentConfig.chatModel(
      openAIClientBuilder, toolCallingManager, azureOpenAiChatProperties);

    assertThat(chatModel.getDefaultOptions()).isInstanceOf(AzureOpenAiChatOptions.class);
    assertThat(((AzureOpenAiChatOptions) chatModel.getDefaultOptions()).getDeploymentName())
      .isEqualTo("chat-model-deployment");
  }

  private static AzureOpenAiChatProperties defaultAzureOpenAiChatProperties() {
    return new AzureOpenAiChatProperties();
  }
}
