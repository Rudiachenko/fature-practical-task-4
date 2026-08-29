package com.epam.codereviewagent.event;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.config.CodeReviewProperties;
import com.epam.codereviewagent.service.ExecutiveSummarySubAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The second, live entry point that can trigger {@link ExecutiveSummarySubAgent} (retry 1, code
 * review Medium finding — "make the summary reachable from a live review"), alongside the original
 * CLI {@code ExecutiveSummaryRunner}. Listens for {@link CodeReviewCompletedEvent}, published by
 * {@code CodeReviewController} exactly once per successfully completed {@code POST /code-review}
 * request, and — unless disabled via {@link CodeReviewProperties#isExecutiveSummaryAutoTriggerEnabled()}
 * — schedules an executive-summary generation for that same response, printed to standard output.
 *
 * <p><b>Isolation is structural, not just try/catch.</b> {@code CodeReviewController} and {@code
 * CodeReviewReactAgent} hold no reference to this class or to {@link ExecutiveSummarySubAgent}
 * anywhere (grep-verifiable) — the only link is {@link CodeReviewCompletedEvent}, published through
 * the generic {@link org.springframework.context.ApplicationEventPublisher}. This class's own {@link
 * #onCodeReviewCompleted(CodeReviewCompletedEvent)} method never lets an exception escape (both the
 * scheduling step and the summarization-and-print step are individually wrapped), so even a
 * synchronous {@code ApplicationEventPublisher.publishEvent(...)} call from the request thread can
 * never fail, and — because a completed summary failure is logged, never rethrown — can never corrupt
 * the primary HTTP response that already left {@code CodeReviewController} by the time this listener
 * runs.
 *
 * <p><b>Never delays the request thread.</b> The actual summarization work (one more {@code
 * ChatModel} call) is handed off to {@link #executor} rather than run inline inside {@link
 * #onCodeReviewCompleted(CodeReviewCompletedEvent)}, which is itself invoked synchronously by {@code
 * ApplicationEventPublisher.publishEvent(...)} on the calling (request) thread. Handing the work off
 * to an {@link Executor} keeps that synchronous portion to a cheap, non-blocking submission, so the
 * caller of {@code publishEvent(...)} — {@code CodeReviewController}, after it has already built the
 * HTTP response — is never blocked waiting for a second LLM round-trip. The production default {@link
 * Executor} is {@link Executors#newVirtualThreadPerTaskExecutor()} (lightweight, unbounded, one
 * virtual thread per submitted task); tests substitute a directly observable {@link Executor} via this
 * class's package-private constructor, mirroring the exact {@code PrintStream}-injection convention
 * {@code ExecutiveSummaryRunner} already established in Increment 7, so ordering guarantees can be
 * asserted directly rather than relying on timing/sleeps.
 */
@Component
@Slf4j
public class ExecutiveSummaryEventListener {

  private final ExecutiveSummarySubAgent executiveSummarySubAgent;
  private final CodeReviewProperties codeReviewProperties;
  private final Executor executor;
  private final PrintStream out;

  @Autowired
  public ExecutiveSummaryEventListener(ExecutiveSummarySubAgent executiveSummarySubAgent,
                                        CodeReviewProperties codeReviewProperties) {
    this(executiveSummarySubAgent, codeReviewProperties, Executors.newVirtualThreadPerTaskExecutor(),
      System.out);
  }

  /**
   * Test-only constructor: substitutes an explicit, directly observable {@link Executor} and {@link
   * PrintStream} instead of the production defaults, so tests can assert scheduling/ordering directly
   * (e.g. by capturing the submitted task without running it) and capture printed output without
   * {@code System.setOut(...)}.
   */
  ExecutiveSummaryEventListener(ExecutiveSummarySubAgent executiveSummarySubAgent,
                                 CodeReviewProperties codeReviewProperties, Executor executor, PrintStream out) {
    this.executiveSummarySubAgent = executiveSummarySubAgent;
    this.codeReviewProperties = codeReviewProperties;
    this.executor = executor;
    this.out = out;
  }

  @EventListener
  public void onCodeReviewCompleted(CodeReviewCompletedEvent event) {
    if (!codeReviewProperties.isExecutiveSummaryAutoTriggerEnabled()) {
      log.debug("Automatic executive-summary trigger is disabled via configuration "
        + "(app.code-review.executive-summary-auto-trigger-enabled=false); skipping.");
      return;
    }

    try {
      executor.execute(() -> summarizeAndPrint(event.response()));
    } catch (Exception e) {
      log.error("Failed to schedule automatic executive-summary generation: exceptionType={}",
        e.getClass().getSimpleName(), e);
    }
  }

  private void summarizeAndPrint(CodeReviewResponse response) {
    try {
      String summary = executiveSummarySubAgent.summarize(response);
      out.println(summary);
    } catch (Exception e) {
      log.error("Automatic executive-summary generation failed for a completed code review: "
        + "exceptionType={}", e.getClass().getSimpleName(), e);
    }
  }
}
