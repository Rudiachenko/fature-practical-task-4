package com.epam.prompting_llm.api.model;


import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PromptRequest(
  @NotBlank(message = "Field cannot be blank")
  String input,

  String conversationId,

  @NotNull
  @DecimalMin(value = "0.0", message = "Temperature must be between 0.0 and 1.0")
  @DecimalMax(value = "1.0", message = "Temperature must be between 0.0 and 1.0")
  Double temperature
) {

}
