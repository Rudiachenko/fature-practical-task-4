package com.epam.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import com.epam.codereview.service.CodeReviewTools;
import com.epam.codereview.service.ConventionService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Focused unit/slice test isolating {@link AgentConfig}'s {@code @Bean} factory methods, called
 * directly against hand-constructed collaborators (no Spring context). Mirrors
 * {@code 03-code-review-agent}'s own {@code AgentConfigTest} structure, adapted to module 4's
 * {@code chatOptions} shape (local {@link CodeReviewTools} callbacks merged with dynamically
 * discovered MCP tool callbacks, rather than local tools alone).
 */
class AgentConfigTest {

  private static final Set<String> EXPECTED_LOCAL_TOOL_NAMES =
    Set.of("retrieveCodeLanguage", "retrieveCodeConvention");

  private final AgentConfig agentConfig = new AgentConfig();

  private CodeReviewTools codeReviewTools;

  @BeforeEach
  void setUp() {
    ChatModel subModelChatModel = mock(ChatModel.class);
    ConventionService conventionService = mock(ConventionService.class);
    codeReviewTools = new CodeReviewTools(subModelChatModel, conventionService);
  }

  // --- chatModel ----------------------------------------------------------------------------

  @Test
  void shouldBuildANonNullAzureOpenAiChatModel_whenChatModelBeanIsBuilt() {
    AzureOpenAiChatModel chatModel = agentConfig.chatModel(
      testOpenAiClientBuilder(), mock(ToolCallingManager.class), defaultAzureOpenAiChatProperties());

    assertThat(chatModel).isNotNull().isInstanceOf(AzureOpenAiChatModel.class);
  }

  @Test
  void shouldUseTheConfiguredDeploymentNameAsDefaultOptions_whenChatModelBeanIsBuilt() {
    AzureOpenAiChatProperties azureOpenAiChatProperties = defaultAzureOpenAiChatProperties();
    azureOpenAiChatProperties.getOptions().setDeploymentName("chat-model-deployment");

    AzureOpenAiChatModel chatModel = agentConfig.chatModel(
      testOpenAiClientBuilder(), mock(ToolCallingManager.class), azureOpenAiChatProperties);

    assertThat(chatModel.getDefaultOptions()).isInstanceOf(AzureOpenAiChatOptions.class);
    assertThat(chatModel.getDefaultOptions().getDeploymentName()).isEqualTo("chat-model-deployment");
  }

  // --- chatOptions --------------------------------------------------------------------------

  @Test
  void shouldAttachExactlyTheTwoLocalToolCallbacks_whenNoMcpClientsAreConfigured() {
    SyncMcpToolCallbackProvider mcpToolCallbackProvider = new SyncMcpToolCallbackProvider(List.of());

    ChatOptions chatOptions = agentConfig.chatOptions(
      codeReviewTools, defaultAzureOpenAiChatProperties(), mcpToolCallbackProvider);

    assertThat(toolNames(chatOptions)).isEqualTo(EXPECTED_LOCAL_TOOL_NAMES);
  }

  @Test
  void shouldMergeLocalAndMcpToolCallbacks_whenMcpToolsAreDiscovered() {
    SyncMcpToolCallbackProvider mcpToolCallbackProvider = mock(SyncMcpToolCallbackProvider.class);
    ToolCallback fakeCallback = fakeToolCallback("get_pull_request");
    when(mcpToolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[] {fakeCallback});

    ChatOptions chatOptions = agentConfig.chatOptions(
      codeReviewTools, defaultAzureOpenAiChatProperties(), mcpToolCallbackProvider);

    assertThat(toolNames(chatOptions)).isEqualTo(
      Set.of("retrieveCodeLanguage", "retrieveCodeConvention", "get_pull_request"));
  }

  @Test
  void shouldReflectTheConfiguredDeploymentNameProperty_notALiteral_whenChatOptionsBeanIsBuilt() {
    AzureOpenAiChatProperties azureOpenAiChatProperties = defaultAzureOpenAiChatProperties();
    azureOpenAiChatProperties.getOptions().setDeploymentName("overridden-non-default-deployment");
    SyncMcpToolCallbackProvider mcpToolCallbackProvider = new SyncMcpToolCallbackProvider(List.of());

    ChatOptions chatOptions =
      agentConfig.chatOptions(codeReviewTools, azureOpenAiChatProperties, mcpToolCallbackProvider);

    assertThat(((AzureOpenAiChatOptions) chatOptions).getDeploymentName())
      .isEqualTo("overridden-non-default-deployment");
  }

  @Test
  void shouldDisableInternalToolExecution_whenChatOptionsBeanIsBuilt() {
    SyncMcpToolCallbackProvider mcpToolCallbackProvider = new SyncMcpToolCallbackProvider(List.of());

    ChatOptions chatOptions = agentConfig.chatOptions(
      codeReviewTools, defaultAzureOpenAiChatProperties(), mcpToolCallbackProvider);

    assertThat(((AzureOpenAiChatOptions) chatOptions).getInternalToolExecutionEnabled()).isFalse();
  }

  private static Set<String> toolNames(ChatOptions chatOptions) {
    return ((AzureOpenAiChatOptions) chatOptions).getToolCallbacks().stream()
      .map(callback -> callback.getToolDefinition().name())
      .collect(Collectors.toSet());
  }

  private static ToolCallback fakeToolCallback(String name) {
    ToolDefinition toolDefinition = mock(ToolDefinition.class);
    when(toolDefinition.name()).thenReturn(name);
    ToolCallback toolCallback = mock(ToolCallback.class);
    when(toolCallback.getToolDefinition()).thenReturn(toolDefinition);
    return toolCallback;
  }

  private static OpenAIClientBuilder testOpenAiClientBuilder() {
    return new OpenAIClientBuilder()
      .credential(new KeyCredential("hermetic-test-key"))
      .endpoint("http://127.0.0.1:1");
  }

  private static AzureOpenAiChatProperties defaultAzureOpenAiChatProperties() {
    return new AzureOpenAiChatProperties();
  }
}
