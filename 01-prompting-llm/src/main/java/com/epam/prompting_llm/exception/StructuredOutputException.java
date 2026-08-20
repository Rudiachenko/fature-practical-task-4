package com.epam.prompting_llm.exception;

public class StructuredOutputException extends RuntimeException {

  public StructuredOutputException(Throwable cause) {
    super("Model returned an invalid structured response", cause);
  }

  public StructuredOutputException() {
    super("Model returned an invalid structured response");
  }
}
