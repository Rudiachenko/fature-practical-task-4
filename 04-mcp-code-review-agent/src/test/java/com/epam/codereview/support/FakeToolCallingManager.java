package com.epam.codereview.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Hermetic {@link ToolCallingManager} test double, following the same hand-written-fake idiom as
 * {@link RecordingChatModel} (observable call-count/argument state, no Mockito) so
 * {@code CodeReviewReactAgentTest} can assert an exact number of tool-execution invocations rather
 * than merely "it was called".
 *
 * <p><b>Faithful to the real contract, not a simplification of it.</b> {@link
 * #executeToolCalls(Prompt, ChatResponse)} builds its returned {@link
 * ToolExecutionResult#conversationHistory()} exactly the way the real
 * {@code DefaultToolCallingManager} does - confirmed by decompiling
 * {@code spring-ai-model-1.1.2.jar}'s {@code
 * DefaultToolCallingManager.buildConversationHistoryAfterToolExecution} with {@code javap -p -c}:
 * {@code [...promptInstructions, assistantMessage, toolResponseMessage]}, in
 * that exact order, with the {@link ToolResponseMessage} always last. {@code CodeReviewReactAgent}
 * relies on that exact ordering to retrieve the tool-response message; using a faithful fake here
 * means this test double actually exercises that same assumption, rather than a fake shaped only to
 * make the production code trivially pass.
 */
public final class FakeToolCallingManager implements ToolCallingManager {

  private final AtomicInteger executeToolCallsInvocationCount = new AtomicInteger();
  private final CopyOnWriteArrayList<AssistantMessage> requestedToolCalls =
    new CopyOnWriteArrayList<>();

  private volatile Function<AssistantMessage, ToolResponseMessage> toolResponseFunction =
    assistantMessage -> ToolResponseMessage.builder().responses(List.of()).build();

  @Override
  public List<ToolDefinition> resolveToolDefinitions(
    ToolCallingChatOptions toolCallingChatOptions) {
    return List.of();
  }

  @Override
  public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    executeToolCallsInvocationCount.incrementAndGet();
    AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
    requestedToolCalls.add(assistantMessage);

    ToolResponseMessage toolResponseMessage = toolResponseFunction.apply(assistantMessage);

    List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
    conversationHistory.add(assistantMessage);
    conversationHistory.add(toolResponseMessage);

    return ToolExecutionResult.builder().conversationHistory(conversationHistory).build();
  }

  /**
   * @return the number of times {@link #executeToolCalls(Prompt, ChatResponse)} has been invoked
   */
  public int executeToolCallsInvocationCount() {
    return executeToolCallsInvocationCount.get();
  }

  /** @return an immutable snapshot of every {@link AssistantMessage} passed in, in call order */
  public List<AssistantMessage> requestedToolCalls() {
    return List.copyOf(requestedToolCalls);
  }

  /**
   * Configures every subsequent {@link #executeToolCalls(Prompt, ChatResponse)} call to derive its
   * {@link ToolResponseMessage} from the requesting {@link AssistantMessage} via {@code function}.
   */
  public void setToolResponseFunction(Function<AssistantMessage, ToolResponseMessage> function) {
    this.toolResponseFunction = function;
  }
}
