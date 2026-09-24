package com.epam.codereview.service;

import com.epam.codereview.config.CodeReviewProperties;
import com.epam.codereview.exception.AgentIterationLimitExceededException;
import com.epam.codereview.support.SafeLogFormatter;
import com.epam.codereview.support.TokenUsage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.ai.util.json.JsonParser;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Manually driven, single-phase ReAct loop: repeatedly calls the {@link ChatModel} and, whenever
 * the model requests tool calls, executes them itself via {@link
 * ToolCallingManager#executeToolCalls(Prompt, ChatResponse)} rather than relying on the {@link
 * ChatModel}'s own internal/automatic tool execution (disabled by {@code AgentConfig}'s {@code
 * chatOptions} bean, Increment 3) - so every iteration and every tool invocation is individually
 * observable, loggable, and bounded.
 *
 * <p><b>Single-phase, unlike {@code 03-code-review-agent}'s two-phase design (see
 * {@code context/PLAN.md}'s Architecture Notes).</b> This loop has no separate structured-output
 * call: the model's own final, no-tool-calls response text (returned verbatim, via {@link
 * AssistantMessage#getText()}) <em>is</em> the return value. This module's real deliverable is the
 * side effect of MCP tool calls posting inline comments and one overall summary directly to the
 * GitHub PR being reviewed - not a parseable response body - so there is no structured contract for
 * a second call to satisfy. Accepted consequence: no runtime evidence-fabrication override exists
 * here the way {@code 03-code-review-agent}'s {@code EvidenceTracker}/{@code
 * applyEvidenceOverride} do; the no-fabrication guarantee for this class is system-prompt-only.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CodeReviewReactAgent {

  private final ChatModel chatModel;
  private final ChatOptions chatOptions;
  private final ToolCallingManager toolCallingManager;
  private final CodeReviewProperties codeReviewProperties;

  /**
   * Runs the ReAct workflow for a single PR-review request: an open-ended, tool-calling
   * conversation bounded by {@link CodeReviewProperties#getMaxIterations()}, returning the model's
   * own final answer text the first time it responds with no further tool calls requested.
   *
   * @param userInput the caller-supplied free-text PR reference (a GitHub PR URL, an
   *                   {@code owner/repo#number} slug, or similar) - always treated as untrusted
   *                   input; tool-call arguments derived from the ensuing conversation are never
   *                   logged unredacted (see {@link SafeLogFormatter}). {@code CodeReviewController}
   *                   runs {@code PrReferenceResolver#validatePrReferencePresent} against this
   *                   value before this method is ever called.
   * @return the model's own final response text once it stops requesting tool calls - a completion
   *         summary only; the actual PR review is delivered as the side effect of MCP tool calls
   *         posting inline comments and a summary to GitHub
   * @throws AgentIterationLimitExceededException if the loop exhausts
   *                                               {@link CodeReviewProperties#getMaxIterations()}
   *                                               while the model is still requesting tool calls
   */
  public String interact(String userInput) {
    log.info("Received PR review request: userInput={}", SafeLogFormatter.format(userInput));

    String systemPrompt = loadSystemPrompt();
    List<Message> initialMessages =
      List.of(new SystemMessage(systemPrompt), new UserMessage(userInput));
    Prompt prompt = new Prompt(initialMessages, chatOptions);

    int maxIterations = codeReviewProperties.getMaxIterations();
    TokenUsageTracker tokenUsageTracker = new TokenUsageTracker();

    for (int iteration = 1; iteration <= maxIterations; iteration++) {
      long callStartNanos = System.nanoTime();
      ChatResponse response = chatModel.call(prompt);
      long callDurationMs = elapsedMillis(callStartNanos);
      TokenUsage callUsage = TokenUsage.from(response);
      tokenUsageTracker.add(callUsage);

      AssistantMessage assistantMessage = response.getResult().getOutput();
      boolean requestsToolCalls = response.hasToolCalls();
      int toolCallCount = requestsToolCalls ? assistantMessage.getToolCalls().size() : 0;
      int responseTextLength = textLength(assistantMessage);
      log.info("ReAct iteration {}/{}: chatModel call completed in {} ms, requestedToolCalls={}, "
          + "responseTextLength={}, promptTokens={}, completionTokens={}, totalTokens={}",
        iteration, maxIterations, callDurationMs, toolCallCount, responseTextLength,
        callUsage.promptTokens(), callUsage.completionTokens(), callUsage.totalTokens());

      if (!requestsToolCalls) {
        // Natural exit: the model's own final answer text is returned as-is - no second,
        // structured-output call exists in this single-phase loop (see class-level Javadoc).
        log.info("ReAct loop completed: iterationsUsed={}, responseTextLength={}, {}", iteration,
          responseTextLength, tokenUsageTracker.summary());
        return assistantMessage.getText();
      }

      ToolExecutionResult toolExecutionResult =
        executeAndLogToolCalls(prompt, response, assistantMessage);
      prompt = new Prompt(toolExecutionResult.conversationHistory(), chatOptions);
    }

    log.error("ReAct loop exhausted {} iterations while tool calls were still being requested; "
        + "aborting rather than returning an incomplete answer. {}", maxIterations,
      tokenUsageTracker.summary());
    throw new AgentIterationLimitExceededException(
      "Code review agent exceeded the maximum of " + maxIterations
        + " ReAct loop iterations while the model was still requesting tool calls.");
  }

  private String loadSystemPrompt() {
    try {
      return StreamUtils.copyToString(codeReviewProperties.getSystemPrompt().getInputStream(),
        StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(
        "Failed to load system prompt from: " + codeReviewProperties.getSystemPrompt(), e);
    }
  }

  /**
   * Executes every tool call requested by {@code assistantMessage} in one batch (via {@link
   * ToolCallingManager#executeToolCalls(Prompt, ChatResponse)}) and logs each individual tool
   * call's name, a bounded/redacted argument summary, the batch's execution duration, and the raw
   * result length.
   *
   * <p><b>Honest logging caveat</b>: {@link ToolCallingManager#executeToolCalls(Prompt,
   * ChatResponse)} executes every tool call requested in one model turn as a single batch with no
   * per-call timing hook exposed by its public API ({@code 03-code-review-agent}'s own
   * {@code CodeReviewReactAgent} documents this, decompilation-verified, for the identical
   * {@code DefaultToolCallingManager} this module also relies on); the {@code batchDurationMs}
   * logged for each tool call in a multi-tool-call turn is therefore the whole batch's duration,
   * not that individual call's own duration. This is logged explicitly as a batch duration rather
   * than silently presented as a more precise per-call measurement it cannot be.
   */
  private ToolExecutionResult executeAndLogToolCalls(Prompt prompt, ChatResponse response,
                                                       AssistantMessage assistantMessage) {
    long batchStartNanos = System.nanoTime();
    ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, response);
    long batchDurationMs = elapsedMillis(batchStartNanos);

    List<Message> conversationHistory = toolExecutionResult.conversationHistory();
    ToolResponseMessage toolResponseMessage = (ToolResponseMessage) conversationHistory.getLast();

    Map<String, String> argumentsByToolCallId = assistantMessage.getToolCalls().stream()
      .collect(Collectors.toMap(AssistantMessage.ToolCall::id, AssistantMessage.ToolCall::arguments,
        (first, second) -> first));

    for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
      String arguments = argumentsByToolCallId.getOrDefault(toolResponse.id(), "");
      String resultData = decodeToolResult(toolResponse.responseData());
      log.info("Tool call executed: name={}, arguments={}, batchDurationMs={}, resultLength={}",
        toolResponse.name(), SafeLogFormatter.format(arguments), batchDurationMs,
        resultData.length());
    }

    return toolExecutionResult;
  }

  /**
   * Recovers the text a tool call's result actually carried from its tool-response payload.
   * Spring AI's {@code DefaultToolCallResultConverter} JSON-encodes a {@code String} tool result
   * unless it is already valid JSON, so a local {@link CodeReviewTools} result (and possibly an
   * MCP tool result, depending on the remote server's own response shape) may arrive quoted and
   * escaped; decoding first keeps the logged {@code resultLength} an accurate reflection of the
   * actual content rather than of its incidental JSON-quoted encoding.
   */
  private static String decodeToolResult(String responseData) {
    if (responseData == null) {
      return "";
    }
    try {
      JsonNode payload = JsonParser.getObjectMapper().readTree(responseData);
      return payload.isTextual() ? payload.textValue() : responseData;
    } catch (JsonProcessingException e) {
      return responseData;
    }
  }

  private static int textLength(AssistantMessage assistantMessage) {
    String text = assistantMessage.getText();
    return text == null ? 0 : text.length();
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /**
   * Sums the provider-reported usage of every model call this class makes for one request. Summing
   * does not double-count: with internal tool execution disabled, {@code AzureOpenAiChatModel}
   * 1.1.2 reports each response's usage for that single call only. LLM calls made inside local
   * tools ({@code CodeReviewTools.callSubModel}) are logged by that class, not counted here.
   */
  private static final class TokenUsageTracker {

    private int modelCalls;
    private TokenUsage total = TokenUsage.ZERO;

    void add(TokenUsage callUsage) {
      modelCalls++;
      total = total.plus(callUsage);
    }

    String summary() {
      return ("agentModelCalls=%d, agentPromptTokens=%d, agentCompletionTokens=%d, "
        + "agentTotalTokens=%d").formatted(modelCalls, total.promptTokens(),
        total.completionTokens(), total.totalTokens());
    }
  }
}
