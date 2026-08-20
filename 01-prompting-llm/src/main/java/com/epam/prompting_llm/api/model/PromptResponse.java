package com.epam.prompting_llm.api.model;

public record PromptResponse(
  String conversationId,
  String response,
  MessageTone tone,
  TokenUsage usage
) {

}
