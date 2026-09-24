package com.epam.codereview.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the PR review agent, bound under the {@code app.code-review}
 * prefix: the system prompt resource and the ReAct loop's iteration bound.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.code-review")
public class CodeReviewProperties {

  /**
   * System prompt resource location for code review
   */
  private Resource systemPrompt;

  /**
   * Maximum number of ReAct loop iterations before the agent aborts rather than returning a
   * partial/fabricated answer. Higher than {@code 03-code-review-agent}'s default of 8: a PR
   * review plausibly needs more round trips - PR metadata, changed files/diffs, per-file
   * language/convention lookups, then one or more comment-posting calls - before a final answer
   * is reached.
   *
   * <p>{@code @Min(1)} makes Spring Boot's own configuration-properties binding reject a
   * misconfigured value immediately on boot, rather than the agent's own loop silently throwing
   * an iteration-limit exception on every single request at runtime instead (same reasoning as
   * {@code 03-code-review-agent}'s equivalent field).</p>
   */
  @Min(1)
  private int maxIterations = 15;
}
