package com.epam.prompting_llm.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/** Holds validated application defaults and provider capabilities for chat processing. */
@Data
@Validated
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

  /**
   * Azure OpenAI model/deployment name
   */
  @NotBlank
  private String modelName;

  /**
   * System prompt resource location
   */
  @NotNull
  private Resource systemPrompt;

  /**
   * Maximum number of messages to keep in memory
   */
  @Min(1)
  private int maxMemoryMessages = 20;

  @DecimalMin("0.0")
  @DecimalMax("2.0")
  private double defaultTemperature = 0.7;

  /**
   * Whether the configured provider/deployment accepts topP directly
   */
  private boolean topPSupported = true;

  /**
   * Temperature value used when topP was requested but is unsupported by the provider
   */
  @DecimalMin("0.0")
  @DecimalMax("2.0")
  private double topPFallbackTemperature = 0.7;
}

