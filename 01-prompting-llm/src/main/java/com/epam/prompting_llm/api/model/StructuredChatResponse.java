package com.epam.prompting_llm.api.model;

public record StructuredChatResponse(
  String response,
  MessageTone tone
) {

}
