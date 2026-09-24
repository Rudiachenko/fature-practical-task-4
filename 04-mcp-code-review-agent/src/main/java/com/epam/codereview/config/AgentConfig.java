package com.epam.codereview.config;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.epam.codereview.service.CodeReviewTools;
import com.epam.codereview.util.PrReferenceResolver;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Azure OpenAI-backed {@link ChatModel} and the tool-calling-enabled {@link ChatOptions}
 * that {@code CodeReviewReactAgent}'s manually-driven ReAct loop attaches to its own
 * {@link org.springframework.ai.chat.prompt.Prompt} explicitly, plus the {@link PrReferenceResolver}
 * request-boundary bean.
 *
 * <p>{@link #chatModel} deliberately declares its return type as the concrete
 * {@link AzureOpenAiChatModel} class, not the {@link ChatModel} interface, and carries no
 * {@code @Primary} annotation. Spring Boot's own auto-configured
 * {@code AzureOpenAiChatAutoConfiguration.azureOpenAiChatModel(...)} bean is annotated
 * {@code @ConditionalOnMissingBean}, and that condition's implicit target type is inferred from its
 * own annotated method's declared return type ({@code AzureOpenAiChatModel}) - a bean whose
 * {@code @Bean} method instead declares the broader {@link ChatModel} interface as its return type
 * is <em>not</em> detected as satisfying that condition, so Spring constructs a second, duplicate
 * {@code ChatModel} bean alongside ours. {@code 03-code-review-agent}'s own {@code AgentConfig}
 * documents this exact failure mode, decompilation-verified: this class's own original scaffold
 * (returning {@code ChatModel} with {@code @Primary}) would produce exactly that duplicate
 * ({@code azureOpenAiChatModel}) plus a {@code NoUniqueBeanDefinitionException} the moment a
 * hermetic test's own {@code @Primary} double is added on top (three {@code ChatModel}-assignable
 * beans, two of them {@code @Primary}). Declaring the concrete return type here matches Spring
 * Boot's own documented idiom for cleanly overriding an auto-configured bean, and removes the need
 * for {@code @Primary} entirely: with the auto-configured bean correctly skipped, this is the only
 * {@code ChatModel}-assignable bean in production, and in a hermetic test that adds its own
 * {@code @Primary} {@code ChatModel} double on top, that double alone is primary - this bean simply
 * loses the autowiring preference, without ever failing context refresh.</p>
 *
 * <p>{@link #chatModel}'s {@code defaultOptions} is also load-bearing for a second, module-4
 * specific reason: {@code CodeReviewTools#callSubModel} (the language-classification sub-call,
 * Increment 2) invokes {@code chatModel.call(new Prompt(new UserMessage(...)))} with no explicit
 * {@link ChatOptions} of its own, so the Azure OpenAI SDK resolves which deployment to call purely
 * from {@code chatModel}'s own {@code defaultOptions}. Without
 * {@code .defaultOptions(azureOpenAiChatProperties.getOptions())} supplying a deployment name here,
 * every such sub-call would fail at the Azure SDK layer (no deployment resolvable) - independent of
 * whatever deployment name {@link #chatOptions} separately attaches to the main ReAct loop's own
 * top-level prompt.</p>
 */
@Configuration
public class AgentConfig {

  /**
   * The Azure OpenAI {@link ChatModel} used both by {@code CodeReviewReactAgent}'s main ReAct loop
   * and by {@link CodeReviewTools}'s LLM-backed sub-calls. See the class-level Javadoc above for why
   * the concrete return type and {@code defaultOptions} are both load-bearing.
   *
   * <p>{@code toolCallingManager} is wired into the builder for consistency with
   * {@code 03-code-review-agent}'s own {@code AgentConfig}, not because this module currently relies
   * on it: {@link #chatOptions} disables internal tool execution for the main ReAct loop, and
   * {@link CodeReviewTools}'s LLM-backed sub-calls carry no tool callbacks of their own, so this
   * {@link ChatModel} never exercises internal tool execution through its own default options today.
   */
  @Bean
  public AzureOpenAiChatModel chatModel(
    OpenAIClientBuilder openAIClientBuilder,
    ToolCallingManager toolCallingManager,
    AzureOpenAiChatProperties azureOpenAiChatProperties) {
    return AzureOpenAiChatModel.builder()
      .openAIClientBuilder(openAIClientBuilder)
      .defaultOptions(azureOpenAiChatProperties.getOptions())
      .toolCallingManager(toolCallingManager)
      .build();
  }

  /**
   * The tool-calling-enabled {@link ChatOptions} the main ReAct loop attaches to its own prompt:
   * the two local {@link CodeReviewTools} callbacks merged with every tool dynamically discovered
   * from the remote GitHub MCP server via {@code mcpToolCallbackProvider}.
   */
  @Bean
  public ChatOptions chatOptions(
    CodeReviewTools codeReviewTools,
    AzureOpenAiChatProperties azureOpenAiChatProperties,
    SyncMcpToolCallbackProvider mcpToolCallbackProvider) {

    ToolCallback[] localToolCallbacks = ToolCallbacks.from(codeReviewTools);
    ToolCallback[] mcpToolCallbacks = mcpToolCallbackProvider.getToolCallbacks();
    List<ToolCallback> toolCallbacks =
      Stream.concat(Arrays.stream(localToolCallbacks), Arrays.stream(mcpToolCallbacks)).toList();

    return AzureOpenAiChatOptions.builder()
      .deploymentName(azureOpenAiChatProperties.getOptions().getDeploymentName())
      .toolCallbacks(toolCallbacks)
      .internalToolExecutionEnabled(false)
      .build();
  }

  @Bean
  public PrReferenceResolver prReferenceResolver() {
    return new PrReferenceResolver();
  }
}
