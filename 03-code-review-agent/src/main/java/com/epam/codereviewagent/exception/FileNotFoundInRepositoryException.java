package com.epam.codereviewagent.exception;

/**
 * Thrown when a repository-relative path passes the security boundary (see
 * {@link PathSecurityViolationException}) but does not resolve to an existing file or directory of
 * the expected kind within the repository root.
 */
public class FileNotFoundInRepositoryException extends RuntimeException {

  public FileNotFoundInRepositoryException(String message) {
    super(message);
  }

  public FileNotFoundInRepositoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
