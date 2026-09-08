package com.epam.docqachatbot.ingestion;

public class DocumentIngestionException extends RuntimeException {

  private final String code;

  public DocumentIngestionException(String code, String message) {
    super(message);
    this.code = code;
  }

  public DocumentIngestionException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
