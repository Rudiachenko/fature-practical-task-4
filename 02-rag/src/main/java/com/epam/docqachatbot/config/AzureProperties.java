package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.azure")
public class AzureProperties {

  /**
   * Azure OpenAI endpoint URL
   */
  private String endpoint = "";

  /**
   * Azure OpenAI API key
   */
  private String apiKey = "";
}

