package com.epam.docqachatbot.ingestion;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

public record LoadedDocumentResource(
  String location,
  String documentName,
  DocumentFormat format,
  byte[] content,
  String fingerprint) {

  public LoadedDocumentResource {
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  public Resource asResource() {
    return new ByteArrayResource(content, location) {
      @Override
      public String getFilename() {
        return documentName;
      }
    };
  }

  /**
   * Stable per-document identity, derived from {@code location} alone (not content), so it does
   * not change across content edits and stays collision-free across different valid paths.
   */
  public String documentId() {
    return Hashing.sha256(location);
  }
}
