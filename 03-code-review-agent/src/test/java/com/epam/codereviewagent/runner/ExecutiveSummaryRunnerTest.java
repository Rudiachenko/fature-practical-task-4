package com.epam.codereviewagent.runner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.config.CodeReviewProperties;
import com.epam.codereviewagent.service.ExecutiveSummarySubAgent;
import com.epam.codereviewagent.support.RecordingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hermetic tests for {@link ExecutiveSummaryRunner#run(org.springframework.boot.ApplicationArguments)},
 * per this increment's own Test Strategy: {@link PrintStream} substitution (via this class's
 * package-private test constructor) rather than {@code System.setOut(...)}, which is global mutable
 * state that would break parallel test execution.
 *
 * <p>Two different test-double strategies are used deliberately, matching two different things each
 * group of tests needs to prove:
 * <ul>
 *   <li>The "no matching argument" tests wire a <em>real</em> {@link ExecutiveSummarySubAgent} to a
 *       Mockito-mocked {@link ChatModel}, so "zero interactions" is proved transitively, all the way
 *       down to the one collaborator that would actually contact a model provider - not merely "the
 *       sub-agent's own {@code summarize} method was never called" one layer up.</li>
 *   <li>The "argument present" tests mock {@link ExecutiveSummarySubAgent} directly, since their job
 *       is to prove this runner's own file-reading/deserialization/error-handling wiring, not to
 *       re-exercise {@code ExecutiveSummarySubAgent}'s own internals (covered by
 *       {@code ExecutiveSummarySubAgentTest}).</li>
 * </ul>
 * One additional, genuinely end-to-end test (real sub-agent, real fixture file, {@link
 * RecordingChatModel}) proves the whole pipeline actually composes, closing the gap either
 * test-double strategy alone would leave.
 */
class ExecutiveSummaryRunnerTest {

  private static final String SAMPLE_FINDINGS_FIXTURE = "src/test/resources/fixtures/sample-findings.json";

  private final ByteArrayOutputStream capturedOutBytes = new ByteArrayOutputStream();
  private final PrintStream capturedOut = new PrintStream(capturedOutBytes, true, StandardCharsets.UTF_8);

  private Logger logbackLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUpLogCapture() {
    logbackLogger = (Logger) LoggerFactory.getLogger(ExecutiveSummaryRunner.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logbackLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDownLogCapture() {
    logbackLogger.detachAppender(logAppender);
  }

  private String capturedOutput() {
    return capturedOutBytes.toString(StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------------------------
  // No matching argument: a true no-op, proved transitively down to the ChatModel itself.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldMakeZeroChatModelInteractionsAndPrintNothing_whenNoArgumentsArePresentAtAll() {
    // Arrange
    ChatModel chatModelMock = mock(ChatModel.class);
    CodeReviewProperties properties = new CodeReviewProperties();
    ExecutiveSummarySubAgent realSubAgent = new ExecutiveSummarySubAgent(chatModelMock, properties);
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(realSubAgent, capturedOut);

    // Act
    runner.run(new DefaultApplicationArguments());

    // Assert
    verifyNoInteractions(chatModelMock);
    assertThat(capturedOutput()).isEmpty();
  }

  @Test
  void shouldMakeZeroChatModelInteractionsAndPrintNothing_whenAnUnrelatedArgumentIsPresent() {
    // Arrange
    ChatModel chatModelMock = mock(ChatModel.class);
    CodeReviewProperties properties = new CodeReviewProperties();
    ExecutiveSummarySubAgent realSubAgent = new ExecutiveSummarySubAgent(chatModelMock, properties);
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(realSubAgent, capturedOut);

    // Act
    runner.run(new DefaultApplicationArguments("--server.port=8080"));

    // Assert
    verifyNoInteractions(chatModelMock);
    assertThat(capturedOutput()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Matching argument present, with a value: file read, deserialized, summarized, printed.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldPrintTheSubAgentsReturnedText_whenInputArgumentPointsAtAReadableFixtureFile() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    when(subAgentMock.summarize(any())).thenReturn("Mocked executive summary text.");
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(subAgentMock, capturedOut);

    // Act
    runner.run(new DefaultApplicationArguments("--executive-summary-input=" + SAMPLE_FINDINGS_FIXTURE));

    // Assert: no HTTP call/response is involved anywhere in this test - the runner only reads a
    // local file and calls the (mocked) sub-agent directly.
    assertThat(capturedOutput()).contains("Mocked executive summary text.");
    verify(subAgentMock, times(1)).summarize(any(CodeReviewResponse.class));
  }

  @Test
  void shouldDeserializeTheFixtureFileIntoTheExactCodeReviewResponseThreeFindingShape_beforeSummarizing() {
    // Arrange: proves the runner's own JSON deserialization wiring against the real fixture content,
    // not just that "some CodeReviewResponse" was passed.
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    when(subAgentMock.summarize(any())).thenReturn("summary");
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(subAgentMock, capturedOut);

    // Act
    runner.run(new DefaultApplicationArguments("--executive-summary-input=" + SAMPLE_FINDINGS_FIXTURE));

    // Assert
    ArgumentCaptor<CodeReviewResponse> captor = ArgumentCaptor.forClass(CodeReviewResponse.class);
    verify(subAgentMock).summarize(captor.capture());
    CodeReviewResponse deserialized = captor.getValue();
    assertThat(deserialized.findings()).hasSize(3);
    assertThat(deserialized.truncated()).isFalse();
    assertThat(deserialized.review()).contains("magic number");
  }

  // ---------------------------------------------------------------------------------------------
  // Malformed / missing input file (Task 3): caught, logged at ERROR, a clear message printed -
  // never an uncaught exception out of run(...).
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldPrintAClearErrorMessageAndLogAtError_whenInputPathDoesNotExist(@TempDir Path tempDir) {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(subAgentMock, capturedOut);
    Path missingFile = tempDir.resolve("does-not-exist.json");

    // Act
    runner.run(new DefaultApplicationArguments("--executive-summary-input=" + missingFile));

    // Assert: no uncaught exception reached this point at all (the call above would have thrown out
    // of the test if it had) - a clear message was printed instead of a raw stack trace, and an
    // ERROR was logged.
    assertThat(capturedOutput()).startsWith("ERROR:").doesNotContain("\tat ");
    assertThat(logAppender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    verifyNoInteractions(subAgentMock);
  }

  @Test
  void shouldPrintAClearErrorMessageAndLogAtError_whenInputFileContainsMalformedJson(@TempDir Path tempDir)
    throws Exception {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(subAgentMock, capturedOut);
    Path malformedFile = tempDir.resolve("malformed.json");
    Files.writeString(malformedFile, "{ this is not valid json", StandardCharsets.UTF_8);

    // Act
    runner.run(new DefaultApplicationArguments("--executive-summary-input=" + malformedFile));

    // Assert
    assertThat(capturedOutput()).startsWith("ERROR:").doesNotContain("\tat ");
    assertThat(logAppender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    verifyNoInteractions(subAgentMock);
  }

  @Test
  void shouldPrintAClearErrorMessageAndLogAtError_whenTheSubAgentItselfFails() {
    // Arrange: proves a failure inside the sub-agent (e.g. an AI-provider outage, or a blank model
    // response) still never escapes run(...) uncaught - the same containment guarantee as the
    // file-reading failure modes above, covering the "chatModel/summarize failure" branch of the
    // shared catch block too, not only the file-I/O branch.
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    when(subAgentMock.summarize(any())).thenThrow(new IllegalStateException("simulated blank LLM response"));
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(subAgentMock, capturedOut);

    // Act
    runner.run(new DefaultApplicationArguments("--executive-summary-input=" + SAMPLE_FINDINGS_FIXTURE));

    // Assert
    assertThat(capturedOutput()).startsWith("ERROR:").doesNotContain("\tat ");
    assertThat(logAppender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
  }

  // ---------------------------------------------------------------------------------------------
  // Argument present but with no value at all (empirically verified against the resolved
  // spring-boot-4.0.2 DefaultApplicationArguments: containsOption("...") is true and
  // getOptionValues("...") returns an empty list, not null, for a bare "--executive-summary-input"
  // with no "=value" - probed directly rather than assumed, per this module's own standing rule on
  // verifying third-party runtime behavior).
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldPrintAUsageErrorMessageAndNeverCallTheSubAgent_whenTheArgumentIsPresentWithNoValue() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(subAgentMock, capturedOut);

    // Act
    runner.run(new DefaultApplicationArguments("--executive-summary-input"));

    // Assert
    assertThat(capturedOutput()).startsWith("ERROR:").contains("requires a file path");
    verifyNoInteractions(subAgentMock);
  }

  // ---------------------------------------------------------------------------------------------
  // Production @Autowired constructor (retry 1, code review High finding): the single-arg constructor
  // Spring actually selects (`this(executiveSummarySubAgent, System.out);`) was previously never
  // exercised by any test - every other test in this class uses the package-private two-arg
  // constructor with a captured sink instead, so this one line/branch was invisible to JaCoCo. Proved
  // here via direct construction plus a field assertion (per this increment's own instruction:
  // "do not use System.setOut").
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldDefaultTheProductionSinkToSystemOut_whenConstructedViaTheAutowiredConstructor() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);

    // Act
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(subAgentMock);

    // Assert
    PrintStream sink = (PrintStream) ReflectionTestUtils.getField(runner, "out");
    assertThat(sink).isSameAs(System.out);
  }

  // ---------------------------------------------------------------------------------------------
  // Genuinely end-to-end: real ExecutiveSummarySubAgent, real fixture file, hermetic RecordingChatModel
  // - proves the whole pipeline actually composes, not merely that each piece works in isolation.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldPrintARealSubAgentProducedSummary_whenWiredEndToEndAgainstTheRealFixtureFile() {
    // Arrange
    RecordingChatModel recordingChatModel = new RecordingChatModel();
    recordingChatModel.setResponse("End-to-end executive summary text.");
    CodeReviewProperties properties = new CodeReviewProperties();
    properties.setExecutiveSummaryPrompt(
      new ByteArrayResource("test system prompt".getBytes(StandardCharsets.UTF_8)));
    ExecutiveSummarySubAgent realSubAgent = new ExecutiveSummarySubAgent(recordingChatModel, properties);
    ExecutiveSummaryRunner runner = new ExecutiveSummaryRunner(realSubAgent, capturedOut);

    // Act
    runner.run(new DefaultApplicationArguments("--executive-summary-input=" + SAMPLE_FINDINGS_FIXTURE));

    // Assert
    assertThat(capturedOutput()).isEqualTo("End-to-end executive summary text." + System.lineSeparator());
    assertThat(recordingChatModel.prompts()).hasSize(1);
  }
}
