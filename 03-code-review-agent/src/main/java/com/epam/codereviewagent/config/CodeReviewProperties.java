package com.epam.codereviewagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@Data
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
   */
  private int maxIterations = 8;

  /**
   * Maximum number of characters returned from a single file read before the content is truncated.
   */
  private int maxFileChars = 20000;
}

