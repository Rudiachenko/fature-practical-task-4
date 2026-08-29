package com.epam.codereviewagent.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "app.code-review")
public class CodeReviewProperties {

  /**
   * System prompt resource location for code review
   */
  private Resource systemPrompt;

  /**
   * Root directory that bounds all repository file/directory access performed by the agent's tools.
   * Relative values are resolved against the process working directory.
   */
  private String repositoryRoot = ".";

  /**
   * Maximum number of ReAct loop iterations before the agent aborts rather than returning a
   * partial/fabricated answer.
   *
   * <p><b>Cross-increment touch (Increment 5 retry 1, code review Medium finding):</b> a value
   * {@code <= 0} makes {@link com.epam.codereviewagent.service.CodeReviewReactAgent#interact(String)}
   * throw {@code AgentIterationLimitExceededException} on every single request without ever calling
   * the model (the loop's own {@code for (iteration = 1; iteration <= maxIterations; ...)} condition
   * is never true) - a real but silent misconfiguration that previously only surfaced per-request, at
   * runtime, instead of failing fast at application startup. {@code @Min(1)} makes Spring Boot's own
   * configuration-properties binding reject such a value immediately on boot.</p>
   */
  @Min(1)
  private int maxIterations = 8;

  /**
   * Maximum number of characters returned from a single file read before the content is truncated.
   */
  private int maxFileChars = 20000;

  /**
   * System prompt resource for {@link com.epam.codereviewagent.service.ExecutiveSummarySubAgent}
   * (Increment 7 / R14) — a separate, simpler, non-agentic sub-agent that summarizes the primary
   * agent's own review/findings, distinct from {@link #systemPrompt} above. Loaded the same way
   * {@link #systemPrompt} already is (see the prompt-loading pattern established in Increment 3:
   * {@code StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8)}), rather than
   * inventing a new loading mechanism for this one additional prompt.
   */
  private Resource executiveSummaryPrompt;

  /**
   * Whether {@link com.epam.codereviewagent.event.ExecutiveSummaryEventListener} automatically
   * generates and prints an executive summary (to standard output) after every successfully completed
   * {@code POST /code-review} request (retry 1, code review Medium finding — "make the summary
   * reachable from a live review").
   *
   * <p>Defaults to {@code true} so the ticket's R14 behavior ("outputs them to the standard output")
   * is observable out of the box from a live request, not only via the separate
   * {@code --executive-summary-input=<path>} CLI trigger ({@code ExecutiveSummaryRunner}). Since every
   * enabled request pays for one additional {@code ChatModel} call and writes to {@code stdout} on
   * every review, an operator who wants neither may set
   * {@code app.code-review.executive-summary-auto-trigger-enabled=false}. This never affects the
   * primary review response either way - see {@code ExecutiveSummaryEventListener}'s own Javadoc for
   * why a disabled or failing summary can never delay, fail, or alter it.
   */
  private boolean executiveSummaryAutoTriggerEnabled = true;
}

