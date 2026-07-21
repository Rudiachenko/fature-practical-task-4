package com.epam.docqachatbot.api.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
  @NotBlank String input,
  String conversationId) {

}


