package com.epam.codereview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.codereview.config.CodeReviewProperties;
import com.epam.codereview.exception.AgentIterationLimitExceededException;
import com.epam.codereview.support.FakeToolCallingManager;
import com.epam.codereview.support.RecordingChatModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;

/**
 * Hermetic tests for {@link CodeReviewReactAgent#interact(String)}, using hand-written fakes
 * ({@link FakeReactChatModel}, {@link FakeToolCallingManager}, {@link RecordingChatModel}) with
 * observable call-count/argument state - not Mockito mocks - so iteration-count and
 * tool-invocation-count assertions are exact and unambiguous, mirroring
 * {@code 03-code-review-agent}'s own {@code CodeReviewReactAgentTest} test strategy.
 *
 * <p>Trimmed to only the control-flow assertions that still apply to this module's single-phase
 * loop (see {@link CodeReviewReactAgent}'s own class-level Javadoc): no phase-2/structured-output,
 * evidence-tracking, or truncation assertions exist here, since none of those mechanisms exist in
 * this class.
 */
class CodeReviewReactAgentTest {

  private static final String SYSTEM_PROMPT_TEXT =
    "You are a test system prompt for the ReAct loop.";

  private CodeReviewProperties properties;
  private ChatOptions chatOptions;
  private FakeToolCallingManager fakeToolCallingManager;
  private final Logger agentLogger = (Logger) LoggerFactory.getLogger(CodeReviewReactAgent.class);
  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    properties = new CodeReviewProperties();
    properties.setSystemPrompt(
      new ByteArrayResource(SYSTEM_PROMPT_TEXT.getBytes(StandardCharsets.UTF_8)));
    properties.setMaxIterations(8);
    chatOptions = ChatOptions.builder().build();
    fakeToolCallingManager = new FakeToolCallingManager();
    logAppender.start();
    agentLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    agentLogger.detachAppender(logAppender);
  }

  // ---------------------------------------------------------------------------------------------
  // Exact-call-count / control-flow proofs
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnAssistantMessageText_afterExactlyTwoChatModelCalls_whenModelCallsAToolThenAnswers() {
    // Arrange
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "retrievePrMetadata", "{\"pr\":\"owner/repo#1\"}")),
      textResponse("Review complete: posted 2 inline comments and one summary.")));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    String result = agent.interact("review owner/repo#1");

    // Assert
    assertThat(chatModel.callCount()).isEqualTo(2);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(1);
    assertThat(result).isEqualTo("Review complete: posted 2 inline comments and one summary.");
  }

  @Test
  void shouldThrowAgentIterationLimitExceededException_afterExactlyMaxIterationsChatModelCalls_whenModelRequestsToolCallsIndefinitely() {
    // Arrange
    properties.setMaxIterations(3);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "retrievePrDiff", "{}")),
      toolCallResponse(toolCall("call-2", "retrievePrDiff", "{}")),
      toolCallResponse(toolCall("call-3", "retrievePrDiff", "{}"))));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("keep calling tools forever"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(3);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------------------------
  // maxIterations boundary values
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsZero() {
    // Arrange: the loop's own `for (iteration = 1; iteration <= maxIterations; ...)` condition is
    // never true when maxIterations <= 0, so the honest exhaustion path is reached with zero model
    // calls - an empty response queue below means any unexpected call throws loudly rather than
    // silently returning a stale response.
    properties.setMaxIterations(0);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of());
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(0);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(0);
  }

  @Test
  void shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsNegative() {
    // Arrange: a negative configured value must not be treated as "unlimited" or otherwise
    // misbehave - it must fail the same honest way as zero, immediately.
    properties.setMaxIterations(-1);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of());
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(0);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(0);
  }

  @Test
  void shouldThrowAgentIterationLimitExceededExceptionAfterExactlyOneChatModelCall_whenMaxIterationsIsOneAndModelRequestsATool() {
    // Arrange: maxIterations=1 allows exactly one chatModel call; if the model uses that one call
    // to request a tool rather than answering, the loop exhausts immediately afterward - one call,
    // one tool-execution batch, then the honest exhaustion exception, never an incomplete answer.
    properties.setMaxIterations(1);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "retrievePrDiff", "{}"))));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("keep calling tools"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(1);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(1);
  }

  @Test
  void shouldReturnAssistantMessageTextAfterExactlyOneChatModelCall_whenMaxIterationsIsOneAndModelAnswersImmediately() {
    // Arrange: maxIterations=1 must not be mistaken for "always exhausted" - a model that answers
    // on its very first call (no tool calls requested) still gets a normal, successful outcome.
    properties.setMaxIterations(1);
    RecordingChatModel chatModel = new RecordingChatModel();
    chatModel.setResponse("no tools needed; answering immediately.");
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    String result = agent.interact("review something");

    // Assert
    assertThat(chatModel.prompts()).hasSize(1);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(0);
    assertThat(result).isEqualTo("no tools needed; answering immediately.");
  }

  // ---------------------------------------------------------------------------------------------
  // Final answer contract - single-phase, nothing to parse (Architecture Notes)
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnTheFinalAssistantMessagesRawTextVerbatim_whenModelAnswersWithNoToolCalls() {
    // Arrange: a direct verbatim-equality assertion 03-code-review-agent never needed, since that
    // module's final answer always went through a second, structured-output parsing call - this
    // module has nothing to parse, so the model's own raw text is the entire contract.
    RecordingChatModel chatModel = new RecordingChatModel();
    String exactResponseText =
      "Final answer: reviewed PR #42, posted 3 inline comments and one overall summary.";
    chatModel.setResponse(exactResponseText);
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    String result = agent.interact("review owner/repo#42");

    // Assert
    assertThat(result).isEqualTo(exactResponseText);
  }

  // ---------------------------------------------------------------------------------------------
  // R8 - SafeLogFormatter is actually applied at the tool-call-argument log call site
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldRedactAndNeutralizeNewlines_inToolCallArgumentsLogLine_whenLoggingEachExecutedToolCall() {
    // Arrange
    fakeToolCallingManager.setToolResponseFunction(CodeReviewReactAgentTest::irrelevantToolResult);
    String craftedArguments = "line one\nline two api-key=super-secret-value";
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "postReviewComment", craftedArguments)),
      textResponse("done")));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    agent.interact("review something");

    // Assert
    List<String> formattedMessages = logMessages();
    assertThat(formattedMessages).isNotEmpty();
    assertThat(formattedMessages).allSatisfy(message -> assertThat(message)
      .doesNotContain("\n")
      .doesNotContain("\r")
      .doesNotContain("super-secret-value"));
    assertThat(formattedMessages).anyMatch(message -> message.contains("api-key=[REDACTED]"));
  }

  // ---------------------------------------------------------------------------------------------
  // decodeToolResult - resultLength reflects the tool result's actual decoded text, not its raw
  // JSON-quoted encoding (Medium finding closed as a same-day follow-up to Increment 4)
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldLogTheDecodedTextsOwnLength_whenToolResponseDataIsAJsonQuotedString() {
    // Arrange: DefaultToolCallResultConverter JSON-encodes a String tool result unless it is
    // already valid JSON, so a result may arrive quoted/escaped - decodeToolResult must recover
    // the decoded text, and the logged resultLength must reflect THAT length, not the raw quoted
    // JSON string's own (longer) length.
    String decodedText = "decoded tool output";
    String jsonQuotedResponseData = "\"" + decodedText + "\"";
    fakeToolCallingManager.setToolResponseFunction(
      assistantMessage -> toolResultResponse(assistantMessage, jsonQuotedResponseData));
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "retrievePrDiff", "{}")),
      textResponse("done")));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    agent.interact("review something");

    // Assert
    assertThat(logMessages()).anyMatch(message -> message.startsWith("Tool call executed:")
      && message.endsWith("resultLength=" + decodedText.length()));
  }

  @Test
  void shouldLogTheRawPayloadsOwnLength_whenToolResponseDataIsValidButNonTextualJson() {
    // Arrange: a non-textual JSON payload (e.g. an object) is not `isTextual()`, so
    // decodeToolResult passes it through unchanged - the logged resultLength must reflect the raw
    // JSON payload's own length.
    String nonTextualJsonResponseData = "{\"a\":1}";
    fakeToolCallingManager.setToolResponseFunction(
      assistantMessage -> toolResultResponse(assistantMessage, nonTextualJsonResponseData));
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "retrievePrDiff", "{}")),
      textResponse("done")));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    agent.interact("review something");

    // Assert
    assertThat(logMessages()).anyMatch(message -> message.startsWith("Tool call executed:")
      && message.endsWith("resultLength=" + nonTextualJsonResponseData.length()));
  }

  @Test
  void shouldLogZeroResultLength_whenToolResponseDataIsNull() {
    // Arrange: null responseData is legal per ToolResponseMessage.ToolResponse's own contract (no
    // null-validation in its compact constructor) - decodeToolResult must treat it as an empty
    // result rather than throwing.
    fakeToolCallingManager.setToolResponseFunction(
      assistantMessage -> toolResultResponse(assistantMessage, null));
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "retrievePrDiff", "{}")),
      textResponse("done")));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    agent.interact("review something");

    // Assert
    assertThat(logMessages()).anyMatch(
      message -> message.startsWith("Tool call executed:") && message.endsWith("resultLength=0"));
  }

  // ---------------------------------------------------------------------------------------------
  // Requirement 10 ("log incoming requests") - SafeLogFormatter applied at the request log line
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldLogReceivedRequestWithRedactedAndNewlineNeutralizedUserInput_whenInteractIsCalled() {
    // Arrange
    RecordingChatModel chatModel = new RecordingChatModel();
    chatModel.setResponse("no tools needed; answering immediately.");
    CodeReviewReactAgent agent = agentWith(chatModel);
    String craftedUserInput = "review owner/repo#1\napi-key=super-secret-value";

    // Act
    agent.interact(craftedUserInput);

    // Assert
    List<String> formattedMessages = logMessages();
    assertThat(formattedMessages)
      .anyMatch(message -> message.startsWith("Received PR review request: userInput=")
        && message.contains("review owner/repo#1 api-key=[REDACTED]"));
    assertThat(formattedMessages).allSatisfy(message -> assertThat(message)
      .doesNotContain("\n")
      .doesNotContain("\r")
      .doesNotContain("super-secret-value"));
  }

  // ---------------------------------------------------------------------------------------------
  // Token usage - logged per iteration, and summarized on both the completion and exhaustion paths
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldLogPerIterationAndSummaryTokenUsage_whenAReviewCompletesAfterAToolCallRound() {
    // Arrange
    fakeToolCallingManager.setToolResponseFunction(CodeReviewReactAgentTest::irrelevantToolResult);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      withUsage(toolCallResponse(toolCall("call-1", "retrievePrDiff", "{}")), 800, 40),
      withUsage(textResponse("Review complete."), 950, 30)));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    agent.interact("review a PR");

    // Assert
    assertThat(logMessages())
      .anyMatch(message -> message.startsWith("ReAct iteration 1/8:")
        && message.endsWith("promptTokens=800, completionTokens=40, totalTokens=840"))
      .anyMatch(message -> message.startsWith("ReAct iteration 2/8:")
        && message.endsWith("promptTokens=950, completionTokens=30, totalTokens=980"))
      .anyMatch(message -> message.startsWith("ReAct loop completed:")
        && message.endsWith("agentModelCalls=2, agentPromptTokens=1750, "
          + "agentCompletionTokens=70, agentTotalTokens=1820"));
  }

  @Test
  void shouldLogTheTokensConsumedSoFarInTheExhaustionSummary_whenTheIterationLimitIsExceeded() {
    // Arrange
    properties.setMaxIterations(2);
    fakeToolCallingManager.setToolResponseFunction(CodeReviewReactAgentTest::irrelevantToolResult);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      withUsage(toolCallResponse(toolCall("call-1", "retrievePrDiff", "{}")), 1200, 60),
      withUsage(toolCallResponse(toolCall("call-2", "retrievePrDiff", "{}")), 1500, 70)));
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act
    Throwable thrown = catchThrowable(() -> agent.interact("keep calling tools forever"));

    // Assert
    assertThat(thrown).isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(logMessages()).anyMatch(message -> message.startsWith("ReAct loop exhausted 2 ")
      && message.endsWith("agentModelCalls=2, agentPromptTokens=2700, "
        + "agentCompletionTokens=130, agentTotalTokens=2830"));
  }

  // ---------------------------------------------------------------------------------------------
  // Error paths
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldThrowIllegalStateException_whenSystemPromptResourceCannotBeRead() {
    // Arrange
    properties.setSystemPrompt(new AbstractResource() {
      @Override
      public String getDescription() {
        return "broken-system-prompt";
      }

      @Override
      public java.io.InputStream getInputStream() throws IOException {
        throw new IOException("Simulated unreadable system prompt resource");
      }
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of());
    CodeReviewReactAgent agent = agentWith(chatModel);

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Failed to load system prompt");
  }

  private List<String> logMessages() {
    return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static ChatResponse withUsage(ChatResponse response, int promptTokens,
                                        int completionTokens) {
    ChatResponseMetadata metadata = ChatResponseMetadata.builder()
      .usage(new DefaultUsage(promptTokens, completionTokens))
      .build();
    return new ChatResponse(response.getResults(), metadata);
  }

  private static ToolResponseMessage irrelevantToolResult(AssistantMessage assistantMessage) {
    AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
    return ToolResponseMessage.builder()
      .responses(
        List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "irrelevant")))
      .build();
  }

  /**
   * Like {@link #irrelevantToolResult(AssistantMessage)}, but with a caller-supplied
   * {@code responseData} so {@code decodeToolResult}'s branches (JSON-quoted-string, non-textual
   * JSON, {@code null}) can each be driven through {@link CodeReviewReactAgent#interact(String)}.
   */
  private static ToolResponseMessage toolResultResponse(AssistantMessage assistantMessage,
                                                          String responseData) {
    AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
    return ToolResponseMessage.builder()
      .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), responseData)))
      .build();
  }

  private CodeReviewReactAgent agentWith(ChatModel chatModel) {
    return new CodeReviewReactAgent(chatModel, chatOptions, fakeToolCallingManager, properties);
  }

  private static AssistantMessage.ToolCall toolCall(
    String id, String toolName, String argumentsJson) {
    return new AssistantMessage.ToolCall(id, "function", toolName, argumentsJson);
  }

  private static ChatResponse toolCallResponse(AssistantMessage.ToolCall... toolCalls) {
    return new ChatResponse(List.of(new Generation(
      AssistantMessage.builder().toolCalls(List.of(toolCalls)).build())));
  }

  private static ChatResponse textResponse(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  /**
   * Hermetic {@link ChatModel} test double purpose-built for exact-call-count control-flow proofs:
   * an ordered queue of canned {@link ChatResponse}s, one per expected {@code call(Prompt)}
   * invocation. Throws {@link IllegalStateException} if the production code under test makes more
   * calls than the test configured, so an unexpected extra call fails loudly and immediately rather
   * than silently reusing a stale response. Scoped as a private nested class (rather than a new
   * top-level test-support file), mirroring {@code 03-code-review-agent}'s identical precedent:
   * {@link RecordingChatModel} only supports plain-text responses and cannot itself simulate a
   * model requesting tool calls.
   */
  private static final class FakeReactChatModel implements ChatModel {

    private final Deque<ChatResponse> queuedResponses;
    private final List<Prompt> recordedPrompts = new CopyOnWriteArrayList<>();

    FakeReactChatModel(List<ChatResponse> responses) {
      this.queuedResponses = new ArrayDeque<>(responses);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      recordedPrompts.add(prompt);
      ChatResponse next = queuedResponses.pollFirst();
      if (next == null) {
        throw new IllegalStateException(
          "FakeReactChatModel ran out of queued responses after " + recordedPrompts.size()
            + " call(s)");
      }
      return next;
    }

    int callCount() {
      return recordedPrompts.size();
    }
  }
}
