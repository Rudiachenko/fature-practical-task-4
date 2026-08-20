package com.epam.prompting_llm.api.model;

import com.epam.prompting_llm.validation.ValidSamplingParameters;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@ValidSamplingParameters
public record PromptRequest(
  @JsonAlias("input")
  @NotBlank(message = "Message must not be blank")
  String message,

  String conversationId,

  @DecimalMin(value = "0.0", message = "Temperature must be between 0.0 and 2.0")
  @DecimalMax(value = "2.0", message = "Temperature must be between 0.0 and 2.0")
  Double temperature,

  @DecimalMin(value = "0.0", message = "TopP must be between 0.0 and 1.0")
  @DecimalMax(value = "1.0", message = "TopP must be between 0.0 and 1.0")
  Double topP,

  @Positive(message = "MaxTokens must be greater than zero")
  Integer maxTokens
) {

}
