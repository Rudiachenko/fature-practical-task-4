package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.documents.default")
public class DocumentsProperties {

  /**
   * List of default document resource locations to ingest on startup
   */
  private List<String> resources = List.of("classpath:documents/llm_context_document.pdf");
}

