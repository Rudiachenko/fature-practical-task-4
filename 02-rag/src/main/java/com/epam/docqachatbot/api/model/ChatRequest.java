package com.epam.docqachatbot.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
  @NotBlank(message = "Input must not be blank")
  @Size(max = 4000, message = "Input must contain at most 4000 characters")
  String input,

  @Size(max = 128, message = "ConversationId must contain at most 128 characters")
  String conversationId) {

}


