package com.epam.prompting_llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@Data
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

  /**
   * Azure OpenAI model/deployment name
   */
  private String modelName;

  /**
   * System prompt resource location
   */
  private Resource systemPrompt = null;

  /**
   * Maximum number of messages to keep in memory
   */
  private int maxMemoryMessages = 20;
}

