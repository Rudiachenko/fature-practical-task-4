package com.epam.prompting_llm.api.model;

public record TokenUsage(
  Integer promptTokens,
  Integer completionTokens,
  Integer totalTokens
) {

}
