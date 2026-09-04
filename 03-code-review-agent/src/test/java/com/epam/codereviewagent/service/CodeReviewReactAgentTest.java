package com.epam.codereviewagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.config.CodeReviewProperties;
import com.epam.codereviewagent.exception.AgentIterationLimitExceededException;
import com.epam.codereviewagent.exception.AgentOutputParsingException;
import com.epam.codereviewagent.support.FakeToolCallingManager;
import com.epam.codereviewagent.util.FileUtils;
import com.epam.codereviewagent.util.RepositoryPathResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;

/**
 * Hermetic tests for {@link CodeReviewReactAgent#interact(String)}, using hand-written fakes
 * ({@link FakeReactChatModel}, {@link FakeToolCallingManager}) with observable call-count/argument
 * state - not Mockito mocks - so iteration-count and tool-invocation-count assertions are exact and
 * unambiguous, per this increment's own Test Strategy.
 *
 * <p>{@link CodeReviewStructuredOutputConverter} is used directly, unmocked (it is a plain,
 * dependency-free {@code @Component} with no Spring context required - see Increment 4), so
 * phase 2's real JSON parsing/validation contract is genuinely exercised, not stubbed.
 */
class CodeReviewReactAgentTest {

  private static final String SYSTEM_PROMPT_TEXT =
    "You are a test system prompt for the ReAct loop.";
  private static final String FIXTURE_ROOT = "src/test/resources/fixtures/repo-root";

  private CodeReviewProperties properties;
  private FakeToolCallingManager fakeToolCallingManager;
  private CodeReviewStructuredOutputConverter structuredOutputConverter;

  @BeforeEach
  void setUp() {
    properties = new CodeReviewProperties();
    properties.setSystemPrompt(
      new ByteArrayResource(SYSTEM_PROMPT_TEXT.getBytes(StandardCharsets.UTF_8)));
    properties.setMaxIterations(8);
    fakeToolCallingManager = new FakeToolCallingManager();
    structuredOutputConverter = new CodeReviewStructuredOutputConverter();
  }

  // ---------------------------------------------------------------------------------------------
  // Exact-call-count / control-flow proofs
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnPopulatedResponse_afterExactChatModelCallCount_whenModelCallsToolsTwiceThenAnswers() {
    // Arrange
    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
      return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
          wrappedRealContent("public class Foo {}"))))
        .build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "readFile", "{\"relativePath\":\"a.txt\"}")),
      toolCallResponse(toolCall("call-2", "readFile", "{\"relativePath\":\"b.txt\"}")),
      textResponse("I have enough evidence now."),
      textResponse("{\"review\":\"Looks fine.\",\"findings\":[],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review a.txt and b.txt");

    // Assert
    assertThat(chatModel.callCount()).isEqualTo(4);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(2);
    assertThat(result.review()).isEqualTo("Looks fine.");
    assertThat(result.findings()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void shouldThrowAgentIterationLimitExceededException_afterExactlyMaxIterationsModelCalls_whenModelRequestsToolCallsIndefinitely() {
    // Arrange
    properties.setMaxIterations(3);
    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
      return ToolResponseMessage.builder()
        .responses(
          List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "irrelevant")))
        .build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "readFile", "{}")),
      toolCallResponse(toolCall("call-2", "readFile", "{}")),
      toolCallResponse(toolCall("call-3", "readFile", "{}"))));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("keep calling tools forever"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(3);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------------------------
  // maxIterations boundary values (retry 1, code review Medium finding) - Experiment #7 is central
  // to this increment, but previously only maxIterations=3 and the default 8 were ever exercised.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsZero() {
    // Arrange: the loop's own `for (iteration = 1; iteration <= maxIterations; ...)` condition is
    // never true when maxIterations <= 0, so the honest exhaustion path is reached with zero model
    // calls - confirmed here by an empty response queue: any unexpected call would throw from
    // FakeReactChatModel itself, failing the test loudly rather than silently passing.
    properties.setMaxIterations(0);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of());
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(0);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(0);
  }

  @Test
  void shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsNegative() {
    // Arrange: same honest-exhaustion path as maxIterations=0 - a negative configured value
    // must not be treated as "unlimited" or otherwise misbehave, just fail the same way,
    // immediately.
    properties.setMaxIterations(-1);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of());
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(0);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(0);
  }

  @Test
  void shouldThrowAgentIterationLimitExceededExceptionAfterExactlyOneChatModelCall_whenMaxIterationsIsOneAndModelRequestsATool() {
    // Arrange: maxIterations=1 allows exactly one chatModel call; if the model uses that one
    // call to request a tool rather than answering, the loop exhausts immediately afterward -
    // one call, one tool-execution batch, then the honest exhaustion exception, never a
    // partial/fabricated answer.
    properties.setMaxIterations(1);
    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
      return ToolResponseMessage.builder()
        .responses(
          List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "irrelevant")))
        .build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "readFile", "{}"))));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("keep calling tools"))
      .isInstanceOf(AgentIterationLimitExceededException.class);
    assertThat(chatModel.callCount()).isEqualTo(1);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(1);
  }

  @Test
  void shouldReturnPopulatedResponseAfterExactlyOnePhaseOneCall_whenMaxIterationsIsOneAndModelAnswersImmediately() {
    // Arrange: maxIterations=1 must not be mistaken for "always exhausted" - a model that
    // answers on its very first phase-1 call (no tool calls requested) still gets a normal,
    // successful outcome: exactly 1 phase-1 call plus phase 2's own structured-output call, no
    // exception at all.
    properties.setMaxIterations(1);
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      textResponse("no tools needed; answering immediately."),
      textResponse("{\"review\":\"ok\",\"findings\":[],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review something");

    // Assert
    assertThat(chatModel.callCount()).isEqualTo(2);
    assertThat(fakeToolCallingManager.executeToolCallsInvocationCount()).isEqualTo(0);
    assertThat(result.review()).isEqualTo("ok");
  }

  // ---------------------------------------------------------------------------------------------
  // Architecture Note A4 - runtime evidence/truncation enforcement (Experiments #4 and #6)
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileNeverSucceedsButModelClaimsFindings() {
    // Arrange
    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
      return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
          FileUtils.READ_ERROR_PREFIX + "File not found in repository: 'missing.txt'.")))
        .build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "readFile", "{\"relativePath\":\"missing.txt\"}")),
      textResponse("I could not find the file, but I will report findings anyway."),
      textResponse("{\"review\":\"Found issues.\","
        + "\"findings\":[{\"file\":\"missing.txt\",\"startLine\":1,"
        + "\"endLine\":1,\"rule\":\"fabricated-rule\","
        + "\"severity\":\"high\",\"explanation\":\"fabricated\","
        + "\"recommendation\":\"fabricated\"}],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review missing.txt");

    // Assert
    assertThat(result.findings()).isEmpty();
    assertThat(result.review()).isEqualTo(CodeReviewReactAgent.NO_EVIDENCE_REVIEW_TEXT);
  }

  @Test
  void shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileReturnsTheRealEmptyFileSentinelButModelClaimsFindings() {
    // Arrange: retry 1 (code review, High finding) - context/TICKET.md's Experiment #4 trigger,
    // "Make readFile return empty". Uses the real, production CodeReviewTools.readFile(...)
    // against a real, in-root, zero-byte fixture file, so the tool response fed into the fake
    // tool-calling manager below is the actual message Increment 2 produces - not a
    // hand-constructed literal that merely resembles it - proving the fix tracks the real
    // sentinel, not an assumption about its text.
    RepositoryPathResolver repositoryPathResolver = new RepositoryPathResolver(FIXTURE_ROOT);
    CodeReviewTools realTools = new CodeReviewTools(repositoryPathResolver, null, null, properties);
    String emptyFileMessage = realTools.readFile("empty.txt");
    // Sanity check on the fixture/message shape this test relies on: the empty-file sentinel must
    // never be READ_ERROR_PREFIX-prefixed (Increment 2's own design decision) - if that ever
    // changed,
    // this test would otherwise degenerate into testing the already-covered error-sentinel case.
    assertThat(emptyFileMessage).doesNotStartWith(FileUtils.READ_ERROR_PREFIX);

    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
      return ToolResponseMessage.builder()
        .responses(
          List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), emptyFileMessage)))
        .build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "readFile", "{\"relativePath\":\"empty.txt\"}")),
      textResponse("The file is empty, but I will report findings anyway."),
      textResponse("{\"review\":\"Found issues.\","
        + "\"findings\":[{\"file\":\"empty.txt\",\"startLine\":1,"
        + "\"endLine\":1,\"rule\":\"fabricated-rule\","
        + "\"severity\":\"high\",\"explanation\":\"fabricated\","
        + "\"recommendation\":\"fabricated\"}],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review empty.txt");

    // Assert
    assertThat(result.findings()).isEmpty();
    assertThat(result.review()).isEqualTo(CodeReviewReactAgent.NO_EVIDENCE_REVIEW_TEXT);
  }

  @Test
  void shouldNotOverrideEvidenceGathered_whenOnlyANonReadFileToolSucceeds() {
    // Arrange: exploreRepository succeeding must not, by itself, count as readFile evidence.
    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
      return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
          wrappedRealContent("a.txt\nb.txt"))))
        .build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(
        toolCall("call-1", "exploreRepository", "{\"relativeDirectoryPath\":\".\"}")),
      textResponse("Directory explored, reporting findings without reading any file."),
      textResponse("{\"review\":\"Found issues.\","
        + "\"findings\":[{\"file\":\"a.txt\",\"startLine\":1,"
        + "\"endLine\":1,\"rule\":\"r\",\"severity\":\"low\","
        + "\"explanation\":\"e\",\"recommendation\":\"r\"}],"
        + "\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review the repository");

    // Assert
    assertThat(result.findings()).isEmpty();
    assertThat(result.review()).isEqualTo(CodeReviewReactAgent.NO_EVIDENCE_REVIEW_TEXT);
  }

  @Test
  void shouldNotOverride_whenModelHonestlyReportsNoFindingsDespiteNoEvidence() {
    // Arrange: no tool calls at all in phase 1 (natural exit on the first call, its own text
    // discarded per A5), and phase 2's structured answer already has empty findings - nothing
    // fabricated to correct, so the override must not fire and the model's own honest text
    // survives.
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      textResponse("no tools needed; nothing to report."),
      textResponse("{\"review\":\"Nothing to report; no evidence was gathered.\",\"findings\":[],"
        + "\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review something");

    // Assert
    assertThat(result.review()).isEqualTo("Nothing to report; no evidence was gathered.");
    assertThat(result.findings()).isEmpty();
  }

  @Test
  void shouldSetTruncatedTrue_whenAnyToolResultContainsTruncationMarker_evenIfModelClaimsFalse() {
    // Arrange
    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
      return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
          wrappedRealContent("partial content" + FileUtils.TRUNCATION_MARKER))))
        .build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(toolCall("call-1", "readFile", "{\"relativePath\":\"big.txt\"}")),
      textResponse("Reviewed the retrieved portion."),
      textResponse("{\"review\":\"Reviewed a large file.\",\"findings\":[],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review big.txt");

    // Assert
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void shouldSetTruncatedTrue_whenNoToolResultWasTruncatedButTheModelsOwnAnswerClaimsTrue() {
    // Arrange: the OR-merge (Task 6) must also honor the model's own claim when it is the only
    // source that observed truncation - never trusting the model as the sole source does not mean
    // ignoring it when it agrees or adds information the loop itself could not see.
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      textResponse("no tools needed"),
      textResponse("{\"review\":\"ok\",\"findings\":[],\"truncated\":true}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review something");

    // Assert
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void shouldKeepFirstToolCallsArguments_whenTwoToolCallsInTheSameTurnShareTheSameId() {
    // Arrange: defensive dedup in the argument-lookup map (Collectors.toMap's merge function) - two
    // tool calls sharing an id should never happen in practice, but must not crash the loop if it
    // ever does.
    fakeToolCallingManager.setToolResponseFunction(assistantMessage -> {
      List<ToolResponseMessage.ToolResponse> responses = assistantMessage.getToolCalls().stream()
        .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(),
          wrappedRealContent("x")))
        .toList();
      return ToolResponseMessage.builder().responses(responses).build();
    });
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      toolCallResponse(
        toolCall("dup-id", "readFile", "{\"relativePath\":\"a.txt\"}"),
        toolCall("dup-id", "readFile", "{\"relativePath\":\"b.txt\"}")),
      textResponse("done"),
      textResponse("{\"review\":\"ok\",\"findings\":[],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review something");

    // Assert
    assertThat(result.review()).isEqualTo("ok");
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
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Failed to load system prompt");
  }

  @Test
  void shouldThrowIllegalStateException_whenInjectedChatOptionsIsNotAzureOpenAiChatOptions() {
    // Arrange
    ChatOptions nonAzureChatOptions = ChatOptions.builder().build();
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(textResponse("no tools needed")));
    CodeReviewReactAgent agent = agentWith(chatModel, nonAzureChatOptions);

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("AzureOpenAiChatOptions");
  }

  // ---------------------------------------------------------------------------------------------
  // Increment 4 retry 1 hand-off decision - retry-once on a phase-2 parse failure
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldRetryPhaseTwoCallExactlyOnce_whenFirstStructuredOutputParseFails() {
    // Arrange
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      textResponse("no tools needed"),
      textResponse("this is not valid JSON at all"),
      textResponse("{\"review\":\"Recovered on retry.\",\"findings\":[],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act
    CodeReviewResponse result = agent.interact("review something");

    // Assert
    assertThat(chatModel.callCount()).isEqualTo(3);
    assertThat(result.review()).isEqualTo("Recovered on retry.");
  }

  @Test
  void shouldPropagateAgentOutputParsingException_whenBothStructuredOutputAttemptsFail() {
    // Arrange
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      textResponse("no tools needed"),
      textResponse("still not valid JSON"),
      textResponse("also not valid JSON")));
    CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());

    // Act / Assert
    assertThatThrownBy(() -> agent.interact("review something"))
      .isInstanceOf(AgentOutputParsingException.class);
    assertThat(chatModel.callCount()).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------------------------
  // Architecture Note A5 - phase separation
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldUseSeparateToolsDisabledResponseFormatOptions_forPhaseTwoCall_whilePhaseOneKeepsToolCallbacks() {
    // Arrange
    AzureOpenAiChatOptions phaseOneOptions = optionsWithToolCallbacks();
    FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
      textResponse("no tools needed"),
      textResponse("{\"review\":\"ok\",\"findings\":[],\"truncated\":false}")));
    CodeReviewReactAgent agent = agentWith(chatModel, phaseOneOptions);

    // Act
    agent.interact("review something");

    // Assert
    List<Prompt> prompts = chatModel.recordedPrompts();
    assertThat(prompts).hasSize(2);
    assertThat(prompts.get(0).getOptions()).isSameAs(phaseOneOptions);

    ChatOptions phaseTwoOptionsUsed = prompts.get(1).getOptions();
    assertThat(phaseTwoOptionsUsed).isNotSameAs(phaseOneOptions)
      .isInstanceOf(AzureOpenAiChatOptions.class);
    AzureOpenAiChatOptions azurePhaseTwoOptions = (AzureOpenAiChatOptions) phaseTwoOptionsUsed;
    assertThat(azurePhaseTwoOptions.getToolCallbacks()).isEmpty();
    assertThat(azurePhaseTwoOptions.getResponseFormat()).isNotNull();
    // The shared, singleton chatOptions bean itself must never be mutated by deriving phase 2's
    // options (the empirically-discovered builder-aliasing risk this class's own Javadoc records).
    assertThat(phaseOneOptions.getToolCallbacks()).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // R8 - SafeLogFormatter is actually applied at the log call site, not merely available
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldNotLeakRawNewlinesOrUnredactedSecrets_whenLoggingTheIncomingRequest() {
    // Arrange
    Logger logbackLogger = (Logger) LoggerFactory.getLogger(CodeReviewReactAgent.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logbackLogger.addAppender(appender);

    try {
      FakeReactChatModel chatModel = new FakeReactChatModel(List.of(
        textResponse("no tools needed"),
        textResponse("{\"review\":\"ok\",\"findings\":[],\"truncated\":false}")));
      CodeReviewReactAgent agent = agentWith(chatModel, optionsWithToolCallbacks());
      String userInput = "line one\nline two api-key=super-secret-value";

      // Act
      agent.interact(userInput);

      // Assert
      List<String> formattedMessages = appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
      assertThat(formattedMessages).isNotEmpty();
      assertThat(formattedMessages).allSatisfy(message -> assertThat(message)
        .doesNotContain("\n")
        .doesNotContain("\r")
        .doesNotContain("super-secret-value"));
      assertThat(formattedMessages).anyMatch(message -> message.contains("api-key=[REDACTED]"));
    } finally {
      logbackLogger.detachAppender(appender);
    }
  }

  private CodeReviewReactAgent agentWith(ChatModel chatModel, ChatOptions chatOptions) {
    return new CodeReviewReactAgent(chatModel, chatOptions, fakeToolCallingManager, properties,
      structuredOutputConverter);
  }

  private static AzureOpenAiChatOptions optionsWithToolCallbacks() {
    return AzureOpenAiChatOptions.builder()
      .deploymentName("test-deployment")
      .toolCallbacks(List.of(fakeToolCallback()))
      .internalToolExecutionEnabled(false)
      .build();
  }

  private static ToolCallback fakeToolCallback() {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
          .name("fakeTool")
          .description(
            "A fake tool callback used only to prove phase 1 vs phase 2 option separation.")
          .inputSchema("{}")
          .build();
      }

      @Override
      public String call(String toolInput) {
        return "unused";
      }
    };
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

  private static String wrappedRealContent(String content) {
    return CodeReviewTools.CODE_SNIPPET_BEGIN_MARKER + content
      + CodeReviewTools.CODE_SNIPPET_END_MARKER;
  }

  /**
   * Hermetic {@link ChatModel} test double purpose-built for exact-call-count control-flow proofs:
   * an ordered queue of canned {@link ChatResponse}s, one per expected {@code call(Prompt)}
   * invocation. Throws {@link IllegalStateException} if the production code under test makes more
   * calls than the test configured, so an unexpected extra call fails loudly and immediately rather
   * than silently reusing a stale response. Scoped as a private nested class (rather than a new
   * top-level test-support file) to stay inside this increment's declared file scope - {@code
   * RecordingChatModel} (Increment 2/3's shared double) only supports plain-text responses and
   * cannot itself simulate a model requesting tool calls.
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

    List<Prompt> recordedPrompts() {
      return List.copyOf(recordedPrompts);
    }
  }
}
