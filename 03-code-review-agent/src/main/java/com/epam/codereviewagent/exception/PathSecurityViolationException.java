package com.epam.codereviewagent.exception;

/**
 * Thrown when a requested repository-relative path fails the repository-root security boundary:
 * blank/NUL input, an absolute or drive-qualified path, path-traversal that escapes the configured
 * repository root, or a symlink that resolves outside the repository root.
 * <p>
 * This exception is always thrown before any filesystem-existence check is performed, so a rejected
 * request never leaks whether the underlying path exists.
 */
public class PathSecurityViolationException extends RuntimeException {

  public PathSecurityViolationException(String message) {
    super(message);
  }

  public PathSecurityViolationException(String message, Throwable cause) {
    super(message, cause);
  }
}
