package com.epam.docqachatbot.ingestion;

import java.util.Locale;

public enum DocumentFormat {
  MARKDOWN("markdown"),
  TEXT("text"),
  PDF("pdf");

  private final String metadataValue;

  DocumentFormat(String metadataValue) {
    this.metadataValue = metadataValue;
  }

  public String metadataValue() {
    return metadataValue;
  }

  public static DocumentFormat fromLocation(String location) {
    String normalized = location.toLowerCase(Locale.ROOT);
    if (normalized.endsWith(".md") || normalized.endsWith(".markdown")) {
      return MARKDOWN;
    }
    if (normalized.endsWith(".txt")) {
      return TEXT;
    }
    if (normalized.endsWith(".pdf")) {
      return PDF;
    }
    throw new DocumentIngestionException("UNSUPPORTED_DOCUMENT_TYPE",
      "Document type is not supported");
  }
}
