package com.epam.docqachatbot.api.model;

import com.epam.docqachatbot.api.validation.ValidMetadata;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record DocumentIngestionRequest(
  @NotEmpty(message = "ResourceLocations must not be empty")
  @Size(max = 20, message = "ResourceLocations must contain at most 20 entries")
  List<
    @NotBlank(message = "Resource location must not be blank")
    @Size(max = 512, message = "Resource location must contain at most 512 characters")
    String> resourceLocations,

  @Size(max = 32, message = "Metadata must contain at most 32 entries")
  @ValidMetadata
  Map<
    @NotBlank(message = "Metadata key must not be blank")
    @Size(max = 64, message = "Metadata key must contain at most 64 characters")
    String, Object> metadata) {

  public DocumentIngestionRequest {
    metadata = metadata == null ? Map.of() : metadata;
  }
}


