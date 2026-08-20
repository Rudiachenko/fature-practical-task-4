package com.epam.prompting_llm.exception;

public class InvalidSamplingParametersException extends RuntimeException {

  public InvalidSamplingParametersException() {
    super("Only one of temperature and topP may be provided");
  }

  public InvalidSamplingParametersException(String message) {
    super(message);
  }
}
