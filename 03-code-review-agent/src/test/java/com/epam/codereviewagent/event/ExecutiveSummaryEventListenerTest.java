package com.epam.codereviewagent.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.config.CodeReviewProperties;
import com.epam.codereviewagent.service.ExecutiveSummarySubAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hermetic tests for {@link ExecutiveSummaryEventListener#onCodeReviewCompleted(CodeReviewCompletedEvent)},
 * per this increment's own requirement to assert ordering guarantees explicitly rather than relying on
 * timing/sleeps: most tests here substitute a directly observable {@link Executor} test double (via
 * this class's package-private constructor) that captures the submitted task without running it, so
 * "the listener returns before the summary work runs" is proved by inspecting captured state, not by
 * waiting. A separate small group proves the *production* {@code @Autowired} constructor's real
 * defaults (real {@link System#out}, a real asynchronous {@link Executor}) using a {@link
 * CountDownLatch} - a non-sleep synchronization primitive - to await eventual completion.
 */
class ExecutiveSummaryEventListenerTest {

  private final ByteArrayOutputStream capturedOutBytes = new ByteArrayOutputStream();
  private final PrintStream capturedOut = new PrintStream(capturedOutBytes, true, StandardCharsets.UTF_8);

  private Logger logbackLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUpLogCapture() {
    logbackLogger = (Logger) LoggerFactory.getLogger(ExecutiveSummaryEventListener.class);
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

  private static CodeReviewProperties enabledProperties() {
    CodeReviewProperties properties = new CodeReviewProperties();
    properties.setExecutiveSummaryAutoTriggerEnabled(true);
    return properties;
  }

  // ---------------------------------------------------------------------------------------------
  // Scheduling / ordering: the listener submits work to the Executor and returns without running it
  // synchronously - proved by capturing the submitted task and confirming it has NOT run yet.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldSubmitToTheExecutorWithoutRunningTheSubAgentSynchronously_whenAutoTriggerIsEnabled() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    Executor executorMock = mock(Executor.class);
    ExecutiveSummaryEventListener listener =
      new ExecutiveSummaryEventListener(subAgentMock, enabledProperties(), executorMock, capturedOut);
    CodeReviewResponse response = new CodeReviewResponse("No issues found.", List.of(), false);

    // Act
    listener.onCodeReviewCompleted(new CodeReviewCompletedEvent(response));

    // Assert: the listener method itself returned having only *scheduled* the work - the sub-agent
    // was never invoked on this (the publishing) thread.
    verify(executorMock, times(1)).execute(any(Runnable.class));
    verifyNoInteractions(subAgentMock);
    assertThat(capturedOutput()).isEmpty();
  }

  @Test
  void shouldInvokeTheSubAgentAndPrintItsSummary_whenTheSubmittedTaskIsLaterExecuted() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    when(subAgentMock.summarize(any())).thenReturn("Executive summary text.");
    AtomicReference<Runnable> capturedTask = new AtomicReference<>();
    Executor capturingExecutor = capturedTask::set;
    ExecutiveSummaryEventListener listener =
      new ExecutiveSummaryEventListener(subAgentMock, enabledProperties(), capturingExecutor, capturedOut);
    CodeReviewResponse response = new CodeReviewResponse("No issues found.", List.of(), false);

    // Act
    listener.onCodeReviewCompleted(new CodeReviewCompletedEvent(response));

    // Assert: nothing has run yet, proving the scheduling call itself did no summarization work.
    assertThat(capturedTask.get()).isNotNull();
    verifyNoInteractions(subAgentMock);
    assertThat(capturedOutput()).isEmpty();

    // Act again: only now, once the "executor" actually runs the captured task, does the summary
    // work happen.
    capturedTask.get().run();

    // Assert
    verify(subAgentMock, times(1)).summarize(response);
    assertThat(capturedOutput()).contains("Executive summary text.");
  }

  // ---------------------------------------------------------------------------------------------
  // Toggle (retry 1 requirement): default-on behavior is covered above; this proves the off state.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldNeverInteractWithTheExecutorOrSubAgent_whenAutoTriggerIsDisabled() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    Executor executorMock = mock(Executor.class);
    CodeReviewProperties disabledProperties = new CodeReviewProperties();
    disabledProperties.setExecutiveSummaryAutoTriggerEnabled(false);
    ExecutiveSummaryEventListener listener =
      new ExecutiveSummaryEventListener(subAgentMock, disabledProperties, executorMock, capturedOut);
    CodeReviewResponse response = new CodeReviewResponse("No issues found.", List.of(), false);

    // Act
    listener.onCodeReviewCompleted(new CodeReviewCompletedEvent(response));

    // Assert
    verifyNoInteractions(executorMock);
    verifyNoInteractions(subAgentMock);
    assertThat(capturedOutput()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Failure containment (retry 1 requirement): "a summary failure must never fail, delay, or alter
  // the primary HTTP response" - proved here at the listener level; CodeReviewControllerTest proves it
  // again end-to-end through the real controller/publisher/listener wiring.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldSwallowTheExceptionAndLogAtError_whenTheSubAgentThrowsInsideTheSubmittedTask() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    when(subAgentMock.summarize(any())).thenThrow(new IllegalStateException("simulated blank LLM response"));
    AtomicReference<Runnable> capturedTask = new AtomicReference<>();
    Executor capturingExecutor = capturedTask::set;
    ExecutiveSummaryEventListener listener =
      new ExecutiveSummaryEventListener(subAgentMock, enabledProperties(), capturingExecutor, capturedOut);
    CodeReviewResponse response = new CodeReviewResponse("No issues found.", List.of(), false);
    listener.onCodeReviewCompleted(new CodeReviewCompletedEvent(response));

    // Act: running the submitted task off the (simulated) worker thread must not throw out to the
    // caller of run() - the same containment guarantee as ExecutiveSummaryRunner's own error handling.
    capturedTask.get().run();

    // Assert
    assertThat(capturedOutput()).isEmpty();
    assertThat(logAppender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
  }

  @Test
  void shouldSwallowTheExceptionAndLogAtError_whenTheExecutorRejectsScheduling() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    Executor rejectingExecutor = task -> {
      throw new RejectedExecutionException("simulated executor saturation");
    };
    ExecutiveSummaryEventListener listener =
      new ExecutiveSummaryEventListener(subAgentMock, enabledProperties(), rejectingExecutor, capturedOut);
    CodeReviewResponse response = new CodeReviewResponse("No issues found.", List.of(), false);

    // Act: must not propagate out of the listener's own event-handling method.
    listener.onCodeReviewCompleted(new CodeReviewCompletedEvent(response));

    // Assert
    verifyNoInteractions(subAgentMock);
    assertThat(logAppender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
  }

  // ---------------------------------------------------------------------------------------------
  // Production @Autowired constructor: real System.out sink, real asynchronous Executor.
  // ---------------------------------------------------------------------------------------------

  @Test
  void shouldDefaultTheSinkToSystemOut_whenConstructedViaTheAutowiredConstructor() {
    // Arrange
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);

    // Act
    ExecutiveSummaryEventListener listener =
      new ExecutiveSummaryEventListener(subAgentMock, enabledProperties());

    // Assert
    PrintStream sink = (PrintStream) ReflectionTestUtils.getField(listener, "out");
    assertThat(sink).isSameAs(System.out);
    Executor executor = (Executor) ReflectionTestUtils.getField(listener, "executor");
    assertThat(executor).isNotNull();
  }

  @Test
  void shouldEventuallyInvokeTheSubAgent_whenSchedulingThroughTheRealProductionExecutor() throws InterruptedException {
    // Arrange: a CountDownLatch (not a sleep) proves the real executor genuinely ran the task
    // asynchronously and lets the test await completion deterministically.
    CountDownLatch summarizedLatch = new CountDownLatch(1);
    ExecutiveSummarySubAgent subAgentMock = mock(ExecutiveSummarySubAgent.class);
    when(subAgentMock.summarize(any())).thenAnswer(invocation -> {
      summarizedLatch.countDown();
      return "async summary";
    });
    ExecutiveSummaryEventListener listener =
      new ExecutiveSummaryEventListener(subAgentMock, enabledProperties());
    CodeReviewResponse response = new CodeReviewResponse("No issues found.", List.of(), false);

    // Act
    listener.onCodeReviewCompleted(new CodeReviewCompletedEvent(response));

    // Assert
    assertThat(summarizedLatch.await(5, TimeUnit.SECONDS))
      .as("expected the production Executor to eventually run the submitted summary task")
      .isTrue();
    verify(subAgentMock, times(1)).summarize(response);
  }
}
