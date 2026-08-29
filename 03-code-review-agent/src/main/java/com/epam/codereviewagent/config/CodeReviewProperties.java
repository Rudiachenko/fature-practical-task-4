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
}

