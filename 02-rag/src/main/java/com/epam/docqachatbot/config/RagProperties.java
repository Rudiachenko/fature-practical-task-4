package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Data
@Validated
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

  private String systemPrompt = "classpath:prompts/rag_system_prompt.st";
  private int maxMemoryMessages = 20;

  @Valid private QuestionAnswer questionAnswer = new QuestionAnswer();
  @Valid private QueryTransformation queryTransformation = new QueryTransformation();
  @Valid private QueryExpansion queryExpansion = new QueryExpansion();
  @Valid private Retrieval retrieval = new Retrieval();

  @Data
  public static class QuestionAnswer {
    private boolean enabled = true;
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
    @Min(1)
    @Max(10)
    private int numberOfQueries = 3;
    private boolean includeOriginal = true;
  }

  @Data
  public static class Retrieval {
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double similarityThreshold = 0.7;
    @Min(1)
    @Max(100)
    private int topK = 5;
    private String filterExpression = "";
    private boolean mmrEnabled = false;
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double mmrLambda = 0.5;
    @Min(1)
    @Max(100)
    private int mmrFinalTopK = 5;
  }
}

