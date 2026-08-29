package com.epam.codereviewagent.service;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.config.CodeReviewProperties;
import com.epam.codereviewagent.exception.AgentIterationLimitExceededException;
import com.epam.codereviewagent.exception.AgentOutputParsingException;
import com.epam.codereviewagent.support.SafeLogFormatter;
import com.epam.codereviewagent.util.FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manually-driven ReAct loop: repeatedly calls the {@link ChatModel} and, whenever the model
 * requests tool calls, executes them itself via {@link ToolCallingManager#executeToolCalls(Prompt,
 * ChatResponse)} rather than relying on the {@link ChatModel}'s own internal/automatic tool
 * execution (disabled by {@code AgentConfig}'s {@code chatOptions} bean, Increment 3) — so every
 * iteration and every tool invocation is individually observable, loggable, and bounded.
 *
 * <p><b>Two-phase call structure (Architecture Note A5).</b> Phase 1 (this class's main {@code for}
 * loop) is an open-ended, tool-calling conversation with no {@code responseFormat} attached: it runs
 * until the model stops requesting tool calls, or the configured {@link
 * CodeReviewProperties#getMaxIterations() max-iterations} budget is exhausted (Experiment #7).
 * Phase 2 is exactly one further, <em>separate</em> {@link ChatModel#call(Prompt)} using the
 * accumulated message history, with a freshly-derived {@link ChatOptions} variant that carries no
 * tool callbacks and a {@code strict(true)} JSON-schema {@code responseFormat} (from {@link
 * CodeReviewStructuredOutputConverter}), so the model's actual final answer is always produced under
 * a structured-output contract, never under a request that combines both mechanisms in one call.
 *
 * <p><b>Runtime evidence enforcement (Architecture Note A4).</b> The loop tracks, purely from
 * observed tool I/O, whether any {@code readFile} tool call ever returned real content (as opposed
 * to the Increment-2 error sentinel) and whether any tool result carried a visible truncation
 * marker. If the model's own final structured answer reports findings despite no {@code readFile}
 * call ever having succeeded, that answer is forcibly replaced with an honest, evidence-free result —
 * this is a runtime guard, not merely a system-prompt instruction, so a model that ignores the prompt
 * still cannot make fabricated findings reach a caller (Experiment #4). Likewise, an observed
 * truncation is always reflected in the final {@link CodeReviewResponse#truncated()} flag regardless
 * of what the model itself claims (Experiment #6) — an objectively observable fact from tool I/O is
 * never left solely to the model's own self-report.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CodeReviewReactAgent {

  /**
   * The exact {@code review} text substituted by the evidence-enforcement override (Architecture
   * Note A4) when the model's final structured answer reports one or more findings despite no
   * {@code readFile} tool call ever having returned real content during this request. Package-private
   * so {@code CodeReviewReactAgentTest} can assert against it directly (effect-verified, not merely
   * "some non-blank text").
   */
  static final String NO_EVIDENCE_REVIEW_TEXT =
    "No evidence could be gathered for this request: no file was successfully read during this "
      + "review, so no findings can be honestly reported. The model's final answer reported one or "
      + "more findings despite this, which is not supported by any tool-retrieved evidence in this "
      + "conversation; those findings have been discarded and replaced with this honest, "
      + "evidence-free result.";

  private final ChatModel chatModel;
  private final ChatOptions chatOptions;
  private final ToolCallingManager toolCallingManager;
  private final CodeReviewProperties codeReviewProperties;
  private final CodeReviewStructuredOutputConverter structuredOutputConverter;

  private String loadSystemPrompt() {
    try {
      return StreamUtils.copyToString(codeReviewProperties.getSystemPrompt().getInputStream(),
        StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load system prompt from: " + codeReviewProperties.getSystemPrompt(),
        e);
    }
  }

  /**
   * Runs the full ReAct workflow for a single code review request: tool-calling phase 1, bounded by
   * {@link CodeReviewProperties#getMaxIterations()}, followed by exactly one structured-output phase
   * 2 call, with runtime evidence/truncation enforcement applied to whatever phase 2 returns.
   *
   * @param userInput the caller-supplied review target: a relative file or repository path, within
   *                   the configured repository root — always treated as untrusted input, never
   *                   logged unredacted (see {@link SafeLogFormatter}). {@code CodeReviewController}
   *                   (Increment 6) runs {@code RepositoryPathResolver#validateSecurityBoundary}
   *                   against this value before this method is ever called, so free text that is not
   *                   a syntactically valid relative path (e.g. anything absolute or drive-qualified)
   *                   never reaches this method in practice — see that increment's own hand-off note
   *                   for why this Javadoc no longer describes "or free text describing the review
   *                   request" as a supported form
   * @return a populated {@link CodeReviewResponse}
   * @throws AgentIterationLimitExceededException if phase 1 exhausts {@code maxIterations} while the
   *                                               model is still requesting tool calls
   * @throws AgentOutputParsingException          if phase 2's structured-output call still fails to
   *                                               parse after one retry (see this class's own
   *                                               Javadoc/{@code context/PROGRESS.md} for the
   *                                               retry-once hand-off decision)
   */
  public CodeReviewResponse interact(String userInput) {
    log.info("Received code review request: userInput={}", SafeLogFormatter.format(userInput));

    String systemPrompt = loadSystemPrompt();
    List<Message> initialMessages = List.of(new SystemMessage(systemPrompt), new UserMessage(userInput));
    Prompt prompt = new Prompt(initialMessages, chatOptions);

    int maxIterations = codeReviewProperties.getMaxIterations();
    EvidenceTracker evidenceTracker = new EvidenceTracker();

    for (int iteration = 1; iteration <= maxIterations; iteration++) {
      long callStartNanos = System.nanoTime();
      ChatResponse response = chatModel.call(prompt);
      long callDurationMs = elapsedMillis(callStartNanos);

      AssistantMessage assistantMessage = response.getResult().getOutput();
      boolean requestsToolCalls = response.hasToolCalls();
      int toolCallCount = requestsToolCalls ? assistantMessage.getToolCalls().size() : 0;
      int responseTextLength = textLength(assistantMessage);
      log.info("ReAct iteration {}/{}: chatModel call completed in {} ms, requestedToolCalls={}, "
          + "responseTextLength={}", iteration, maxIterations, callDurationMs, toolCallCount,
        responseTextLength);

      if (!requestsToolCalls) {
        // Natural phase-1 exit: the model's own final phase-1 text (if any) is discarded, per
        // Architecture Note A5 - phase 2 below always regenerates the actual final answer under a
        // structured-output contract, using the accumulated history up to (but not including) this
        // no-tool-calls response.
        return finalizeReview(prompt, evidenceTracker, iteration);
      }

      ToolExecutionResult toolExecutionResult = executeAndLogToolCalls(prompt, response, assistantMessage,
        evidenceTracker);
      prompt = new Prompt(toolExecutionResult.conversationHistory(), chatOptions);
    }

    log.error("ReAct loop exhausted {} iterations while tool calls were still being requested; aborting "
      + "rather than returning a partial/fabricated answer.", maxIterations);
    throw new AgentIterationLimitExceededException(
      "Code review agent exceeded the maximum of " + maxIterations
        + " ReAct loop iterations while the model was still requesting tool calls.");
  }

  /**
   * Executes every tool call requested by {@code assistantMessage} in one batch (via {@link
   * ToolCallingManager#executeToolCalls(Prompt, ChatResponse)}), logs each individual tool call's
   * name, a bounded/redacted argument summary, the batch's execution duration, and the raw result
   * length, and updates {@code evidenceTracker} from the observed results.
   *
   * <p><b>Honest logging caveat</b>: {@link ToolCallingManager#executeToolCalls(Prompt, ChatResponse)}
   * executes every tool call requested in one model turn as a single batch with no per-call timing
   * hook exposed by its public API (confirmed by decompiling {@code DefaultToolCallingManager} -
   * see {@code context/PROGRESS.md}'s Increment 5 entry); the {@code batchDurationMs} logged for each
   * tool call in a multi-tool-call turn is therefore the whole batch's duration, not that individual
   * call's own duration. This is logged explicitly as a batch duration rather than silently presented
   * as a more precise per-call measurement it cannot actually be.
   */
  private ToolExecutionResult executeAndLogToolCalls(Prompt prompt, ChatResponse response,
                                                       AssistantMessage assistantMessage,
                                                       EvidenceTracker evidenceTracker) {
    long batchStartNanos = System.nanoTime();
    ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, response);
    long batchDurationMs = elapsedMillis(batchStartNanos);

    List<Message> conversationHistory = toolExecutionResult.conversationHistory();
    ToolResponseMessage toolResponseMessage =
      (ToolResponseMessage) conversationHistory.get(conversationHistory.size() - 1);

    Map<String, String> argumentsByToolCallId = assistantMessage.getToolCalls().stream()
      .collect(Collectors.toMap(AssistantMessage.ToolCall::id, AssistantMessage.ToolCall::arguments,
        (first, second) -> first));

    for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
      String arguments = argumentsByToolCallId.getOrDefault(toolResponse.id(), "");
      String resultData = toolResponse.responseData() == null ? "" : toolResponse.responseData();
      log.info("Tool call executed: name={}, arguments={}, batchDurationMs={}, resultLength={}",
        toolResponse.name(), SafeLogFormatter.format(arguments), batchDurationMs, resultData.length());
      evidenceTracker.recordToolResult(toolResponse.name(), resultData);
    }

    return toolExecutionResult;
  }

  /**
   * Phase 2 (Architecture Note A5): issues exactly one additional, tools-disabled,
   * {@code responseFormat}-attached {@link ChatModel#call(Prompt)} using {@code accumulatedPrompt}'s
   * message history, parses it via {@link CodeReviewStructuredOutputConverter}, applies the
   * evidence-enforcement override (Architecture Note A4) and the observed-truncation OR-merge, and
   * logs the final outcome.
   *
   * <p><b>Hand-off decision (Increment 4 retry 1's Medium finding — recorded in
   * {@code context/PROGRESS.md}'s Increment 5 entry): retry-once, not per-finding recovery.</b> If
   * the first phase-2 attempt's JSON fails to parse ({@link AgentOutputParsingException}), this
   * method retries with a fresh phase-2 call exactly once before giving up. Dropping only the
   * offending {@code Finding} instead was considered but would require re-implementing lenient,
   * per-element JSON parsing either here or inside {@code CodeReviewStructuredOutputConverter}
   * (Increment 4's file, out of this increment's declared scope) - retry-once is fully self-contained
   * in this class and directly satisfies the hand-off note's "(a)" option.
   */
  private CodeReviewResponse finalizeReview(Prompt accumulatedPrompt, EvidenceTracker evidenceTracker,
                                             int iterationsUsed) {
    ChatOptions phaseTwoOptions = buildPhaseTwoOptions();
    Prompt phaseTwoPrompt = new Prompt(accumulatedPrompt.getInstructions(), phaseTwoOptions);

    CodeReviewResponse parsed;
    try {
      parsed = callAndParseStructuredOutput(phaseTwoPrompt);
    } catch (AgentOutputParsingException firstFailure) {
      log.warn("Phase-2 structured-output parsing failed on the first attempt; retrying exactly once. "
        + "reason={}", SafeLogFormatter.format(firstFailure.getMessage()));
      try {
        parsed = callAndParseStructuredOutput(phaseTwoPrompt);
      } catch (AgentOutputParsingException secondFailure) {
        log.error("Phase-2 structured-output parsing failed again after one retry; exceptionType={}",
          secondFailure.getClass().getSimpleName(), secondFailure);
        throw secondFailure;
      }
    }

    CodeReviewResponse evidenceChecked = applyEvidenceOverride(parsed, evidenceTracker);
    boolean finalTruncated = evidenceTracker.truncated() || evidenceChecked.truncated();
    CodeReviewResponse finalResponse =
      new CodeReviewResponse(evidenceChecked.review(), evidenceChecked.findings(), finalTruncated);

    log.info("Code review completed: iterationsUsed={}, findingsCount={}, truncated={}, evidenceGathered={}",
      iterationsUsed, finalResponse.findings().size(), finalResponse.truncated(),
      evidenceTracker.evidenceGathered());
    return finalResponse;
  }

  private CodeReviewResponse callAndParseStructuredOutput(Prompt phaseTwoPrompt) {
    long callStartNanos = System.nanoTime();
    ChatResponse response = chatModel.call(phaseTwoPrompt);
    long callDurationMs = elapsedMillis(callStartNanos);

    AssistantMessage assistantMessage = response.getResult().getOutput();
    String text = assistantMessage.getText();
    log.info("Phase-2 structured-output call completed in {} ms, responseTextLength={}", callDurationMs,
      textLength(assistantMessage));

    return structuredOutputConverter.convert(text);
  }

  /**
   * Derives a tools-disabled, {@code responseFormat}-attached variant of the injected {@code
   * chatOptions} bean for phase 2, without ever mutating the shared bean itself.
   *
   * <p><b>Real, empirically-discovered aliasing risk avoided (Architecture Note A2's decompilation
   * requirement) - not theoretical.</b> Decompiling {@code AzureOpenAiChatOptions.Builder}'s
   * {@code Builder(AzureOpenAiChatOptions)} constructor (via {@code javap -p -c} against the
   * resolved {@code spring-ai-azure-openai-1.1.2.jar}) shows it stores the exact instance passed to
   * it ({@code this.options = options;}, no defensive copy), and every builder setter mutates that
   * same instance's fields directly (e.g. {@code toolCallbacks(...)} compiles to {@code
   * options.setToolCallbacks(...)}). Wrapping the shared, singleton {@code chatOptions} bean directly
   * in {@code new AzureOpenAiChatOptions.Builder(chatOptions)} and then calling {@code
   * .toolCallbacks(List.of())} would therefore silently strip every tool callback from the
   * <em>production, request-scoped-forever</em> {@code chatOptions} bean itself, breaking every
   * subsequent request's phase-1 tool-calling ability. This method instead calls {@code
   * AzureOpenAiChatOptions.copy()} first (decompiled to confirm it delegates to the static {@code
   * fromOptions(...)}, which builds a brand-new instance field-by-field via a fresh {@code builder()}
   * - not an alias) and only wraps/mutates that independent copy.
   *
   * @throws IllegalStateException if the injected {@code chatOptions} bean is not an {@link
   *                                AzureOpenAiChatOptions} instance (this module only supports Azure
   *                                OpenAI - see {@code AgentConfig} - so this indicates a wiring
   *                                defect, not a normal runtime condition)
   */
  private ChatOptions buildPhaseTwoOptions() {
    if (!(chatOptions instanceof AzureOpenAiChatOptions azureOpenAiChatOptions)) {
      throw new IllegalStateException(
        "Expected the injected chatOptions bean to be an AzureOpenAiChatOptions instance, but was: "
          + (chatOptions == null ? "null" : chatOptions.getClass().getName()));
    }
    return new AzureOpenAiChatOptions.Builder(azureOpenAiChatOptions.copy())
      .toolCallbacks(List.of())
      .toolNames(Set.of())
      .internalToolExecutionEnabled(false)
      .responseFormat(structuredOutputConverter.responseFormat())
      .build();
  }

  /**
   * Architecture Note A4's runtime evidence-enforcement override. Only overrides when the parsed
   * response actually reports findings despite no evidence - an honest, already-empty-findings
   * response is left untouched, since there is nothing fabricated to correct in that case.
   */
  private CodeReviewResponse applyEvidenceOverride(CodeReviewResponse parsed, EvidenceTracker evidenceTracker) {
    if (!evidenceTracker.evidenceGathered() && !parsed.findings().isEmpty()) {
      log.warn("Evidence-enforcement override triggered: the model reported {} finding(s) despite no "
          + "readFile tool call ever returning real content during this review; the response has "
          + "been replaced with an honest no-evidence result.",
        parsed.findings().size());
      return new CodeReviewResponse(NO_EVIDENCE_REVIEW_TEXT);
    }
    return parsed;
  }

  private static int textLength(AssistantMessage assistantMessage) {
    String text = assistantMessage.getText();
    return text == null ? 0 : text.length();
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /**
   * Tracks, purely from observed tool-call results across the whole request, whether any {@code
   * readFile} call ever returned real content and whether any tool result carried a visible
   * truncation marker - the two objective, tool-I/O-derived signals Architecture Note A4/Task 6 use
   * to override or correct whatever the model's own final answer claims.
   *
   * <p><b>Fixed in Increment 5 retry 1 (code review, High finding) - the empty-file sentinel no
   * longer counts as evidence.</b> A {@code readFile} call that succeeds against a real, in-root,
   * existing-but-empty file returns {@code CodeReviewTools}'s dedicated "exists but is empty" message,
   * which is deliberately <em>not</em> {@link FileUtils#READ_ERROR_PREFIX}-prefixed (see Increment 2's
   * own design decision, so the model can tell "not permitted" apart from "exists but empty"). An
   * earlier version of this class's {@code evidenceGathered} rule keyed purely on the absence of that
   * prefix, so this specific sentinel silently counted as real evidence - exactly the {@code
   * context/TICKET.md} Experiment #4 trigger ("make {@code readFile} return empty"), and the runtime
   * override this class exists to provide did not fire for it. Fixed by also excluding any result
   * ending with {@link CodeReviewTools#EMPTY_FILE_MESSAGE_SUFFIX} - a shared constant with {@code
   * CodeReviewTools}'s own message template (see that constant's Javadoc), not a duplicated string
   * literal, so a future rewording of Increment 2's message text cannot silently reopen this gap.
   */
  private static final class EvidenceTracker {

    private boolean evidenceGathered;
    private boolean truncated;

    void recordToolResult(String toolName, String resultData) {
      if ("readFile".equals(toolName)
        && !resultData.startsWith(FileUtils.READ_ERROR_PREFIX)
        && !resultData.endsWith(CodeReviewTools.EMPTY_FILE_MESSAGE_SUFFIX)) {
        evidenceGathered = true;
      }
      if (resultData.contains(FileUtils.TRUNCATION_MARKER)) {
        if (!truncated) {
          log.warn("Truncated tool result observed for tool '{}': content exceeded the configured "
            + "character limit.", toolName);
        }
        truncated = true;
      }
    }

    boolean evidenceGathered() {
      return evidenceGathered;
    }

    boolean truncated() {
      return truncated;
    }
  }
}
