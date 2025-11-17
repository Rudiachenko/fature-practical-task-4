package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

  private String systemPrompt = "classpath:prompts/rag_system_prompt.st";
  private int maxMemoryMessages = 20;

  private QuestionAnswer questionAnswer = new QuestionAnswer();
  private QueryTransformation queryTransformation = new QueryTransformation();
  private QueryExpansion queryExpansion = new QueryExpansion();
  private Retrieval retrieval = new Retrieval();

  @Data
  public static class QuestionAnswer {
    private boolean enabled = true;
    private double similarityThreshold = 0.7;
    private int topK = 5;
    private boolean allowEmptyContext = false;
  }

  @Data
  public static class QueryTransformation {
    private boolean compressionEnabled = false;
    private boolean rewriteEnabled = false;
  }

  @Data
  public static class QueryExpansion {
    private boolean enabled = false;
    private int numberOfQueries = 3;
    private boolean includeOriginal = true;
  }

  @Data
  public static class Retrieval {
    private double similarityThreshold = 0.7;
    private int topK = 5;
    private String filterExpression = "";
  }
}

