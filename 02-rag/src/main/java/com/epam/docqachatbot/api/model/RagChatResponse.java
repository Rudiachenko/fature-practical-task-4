package com.epam.docqachatbot.api.model;

import java.util.List;

public record RagChatResponse(
  String answer,
  List<String> sources) {
}


