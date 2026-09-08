package com.epam.docqachatbot.api.model;

import java.util.List;

public record RagChatResponse(
  String response,
  List<RagSource> sources) {

  public RagChatResponse {
    sources = sources == null ? List.of() : List.copyOf(sources);
  }
}


