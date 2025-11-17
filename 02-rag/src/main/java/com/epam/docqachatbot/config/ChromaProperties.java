package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.vectorstore.chroma")
public class ChromaProperties {

  /**
   * Chroma vector store base URL
   */
  private String baseUrl = "http://localhost:8000";

  /**
   * Chroma API key (optional)
   */
  private String apiKey = "";

  /**
   * Chroma tenant name
   */
  private String tenantName = "default_tenant";

  /**
   * Chroma database name
   */
  private String databaseName = "default_database";

  /**
   * Chroma collection name
   */
  private String collectionName = "doc-qa-collection";
}

