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
}

