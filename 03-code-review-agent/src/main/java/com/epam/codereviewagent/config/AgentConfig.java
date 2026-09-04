package com.epam.codereviewagent.config;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.epam.codereviewagent.service.CodeReviewTools;
import com.epam.codereviewagent.util.RepositoryPathResolver;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Azure OpenAI-backed {@link ChatModel} and the tool-calling-enabled {@link ChatOptions}
 * that {@code CodeReviewReactAgent}'s manually-driven ReAct loop (Increment 5) attaches to its own
 * {@link org.springframework.ai.chat.prompt.Prompt} explicitly.
 *
 * <p>The exact builder method names and the internal-tool-execution flag used below were verified
 * by decompiling the resolved {@code spring-ai-azure-openai-1.1.2.jar},
 * {@code spring-ai-model-1.1.2.jar},
 * {@code spring-ai-autoconfigure-model-azure-openai-1.1.2.jar} and
 * {@code spring-ai-commons-1.1.2.jar} jars (via {@code javap -p -v}) rather than assumed from
 * memory, per this module's plan Architecture Note A2. See {@code context/PROGRESS.md}'s
 * Increment 3 entry for the specific classes/methods inspected and what each decompilation
 * confirmed.
 *
 * <p>{@link #chatModel} deliberately declares its return type as the concrete
 * {@link AzureOpenAiChatModel} class, not the {@link ChatModel} interface. Spring Boot's own
 * auto-configured {@code AzureOpenAiChatAutoConfiguration.azureOpenAiChatModel(...)} bean is
 * annotated {@code ConditionalOnMissingBean}, and that condition's implicit target type is
 * inferred from its own annotated method's declared return type
 * ({@code AzureOpenAiChatModel}) — a bean whose {@code Bean} method instead declares the broader
 * {@link ChatModel} interface as its return type is <em>not</em> detected as satisfying that
 * condition, so Spring constructs a second, duplicate {@code ChatModel} bean alongside ours. This
 * was confirmed the hard way: an earlier draft returning {@code ChatModel} produced exactly that
 * duplicate ({@code azureOpenAiChatModel}) plus a {@code NoUniqueBeanDefinitionException} once a
 * hermetic test's own {@code Primary} double was added on top (three {@code ChatModel}-assignable
 * beans, two of them {@code Primary}). Declaring the concrete return type here matches Spring
 * Boot's own documented idiom for cleanly overriding an auto-configured bean, and removes the
 * need for {@code Primary} entirely: with the auto-configured bean correctly skipped, this is the
 * only {@code ChatModel}-assignable bean in production, and in a hermetic test that adds its own
 * {@code Primary} {@code ChatModel} double on top, that double alone is primary — this bean
 * simply loses the autowiring preference (Architecture Note A3), without ever failing context
 * refresh.
 *
 * <p>Deliberately two distinct {@link AzureOpenAiChatOptions} instances exist in this
 * configuration, not one shared object:
 * <ul>
 *   <li>{@link #chatModel}'s own {@code defaultOptions} carries only the configured deployment
 *       name (mirroring exactly what Spring's own auto-configured
 *       {@code AzureOpenAiChatAutoConfiguration.azureOpenAiChatModel(...)} bean does) and,
 *       critically, carries no tool callbacks. This keeps {@code CodeReviewTools}'s two
 *       LLM-backed sub-tool calls (which invoke {@code ChatModel.call(String)} with no explicit
 *       {@link Prompt} options, see Increment 2) free of any risk of the model recursively
 *       requesting a tool call from inside a tool invocation, since
 *       {@link ChatModel#call(Prompt)} merges a null-options {@link Prompt} with the model's own
 *       {@code defaultOptions}.</li>
 *   <li>The {@link #chatOptions} bean is a separate, richer {@link ChatOptions} object carrying
 *       the six {@code CodeReviewTools} tool callbacks and explicit internal-tool-execution
 *       disablement. Only {@code CodeReviewReactAgent}'s own top-level {@code Prompt}
 *       (Increment 5) attaches this bean, so every tool invocation the model requests during the
 *       main review workflow is executed by Increment 5's own explicit
 *       {@link ToolCallingManager#executeToolCalls} call, never automatically by the
 *       {@link ChatModel} itself.</li>
 * </ul>
 */
@Configuration
public class AgentConfig {

  /**
   * {@link RepositoryPathResolver} (Increment 1) is a deliberately Spring-agnostic pure utility
   * class — no {@code @Component}, hand-constructed directly in every existing unit test — and
   * no earlier increment ever booted a real Spring context to notice it was consequently never
   * registered as a bean anywhere in production wiring. {@link #chatOptions} (and, transitively,
   * {@code
   * CodeReviewReactAgent}/{@code CodeReviewController}) needs a real {@link CodeReviewTools}, which
   * needs a real {@link RepositoryPathResolver}; this factory method is the minimal fix, added here
   * rather than by annotating {@code RepositoryPathResolver} itself, so it stays exactly as
   * Increment 1 designed it: a plain, hand-constructible utility with zero Spring coupling.
   */
  @Bean
  public RepositoryPathResolver repositoryPathResolver(CodeReviewProperties codeReviewProperties) {
    return new RepositoryPathResolver(codeReviewProperties.getRepositoryRoot());
  }

  /** The Azure OpenAI {@link ChatModel} used for phase-1/phase-2 calls in the ReAct loop. */
  @Bean
  public AzureOpenAiChatModel chatModel(OpenAIClientBuilder openAIClientBuilder,
                                         ToolCallingManager toolCallingManager,
                                         AzureOpenAiChatProperties azureOpenAiChatProperties) {
    return AzureOpenAiChatModel.builder()
      .openAIClientBuilder(openAIClientBuilder)
      .defaultOptions(azureOpenAiChatProperties.getOptions())
      .toolCallingManager(toolCallingManager)
      .build();
  }

  /** The tool-calling-enabled {@link ChatOptions} the main ReAct loop attaches to its prompt. */
  @Bean
  public ChatOptions chatOptions(CodeReviewTools codeReviewTools,
                                  AzureOpenAiChatProperties azureOpenAiChatProperties) {
    return AzureOpenAiChatOptions.builder()
      .deploymentName(azureOpenAiChatProperties.getOptions().getDeploymentName())
      .toolCallbacks(ToolCallbacks.from(codeReviewTools))
      .internalToolExecutionEnabled(false)
      .build();
  }
}
