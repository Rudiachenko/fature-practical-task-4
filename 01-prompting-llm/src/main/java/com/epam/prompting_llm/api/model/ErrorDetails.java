package com.epam.prompting_llm.api.model;

import lombok.Builder;

@Builder
public record ErrorDetails(
  String name,
  String reason
) {

}