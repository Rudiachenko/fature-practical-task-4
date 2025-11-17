package com.epam.prompting_llm.api.model;

public record PromptResponse(
  String response,
  MessageTone tone
) {

}
