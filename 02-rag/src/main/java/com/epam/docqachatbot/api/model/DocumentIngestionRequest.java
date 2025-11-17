package com.epam.docqachatbot.api.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record DocumentIngestionRequest(
  @NotEmpty List<String> resourceLocations,
  Map<String, Object> metadata) {

  public DocumentIngestionRequest {
    metadata = metadata == null ? Map.of() : metadata;
  }
}


