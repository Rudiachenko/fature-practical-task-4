package com.epam.docqachatbot.api.model;

import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.Optional;

public record ChatRequest(
  @NotBlank String input,
  String conversationId,
  Integer topK,
  Double similarityThreshold) {

  public int effectiveTopK() {
    return Optional.ofNullable(topK)
      .filter(value -> value > 0)
      .orElse(SearchRequest.DEFAULT_TOP_K);
  }

  public double effectiveSimilarityThreshold() {
    return Optional.ofNullable(similarityThreshold)
      .filter(value -> value >= 0)
      .orElse(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL);
  }
}


