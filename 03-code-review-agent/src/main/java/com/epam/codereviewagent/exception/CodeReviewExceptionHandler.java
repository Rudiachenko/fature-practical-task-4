package com.epam.codereviewagent.exception;

import com.epam.codereviewagent.api.model.ApiError;
import com.epam.codereviewagent.api.model.ApiViolation;
import com.epam.codereviewagent.support.SafeLogFormatter;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every failure mode {@code CodeReviewController}'s request path can throw to a stable HTTP
 * status and a machine-readable {@link ApiError} body, per {@code context/PLAN.md} Increment 6's
 * exact mapping table:
 *
 * <table>
 *   <caption>Exception-to-response mapping</caption>
 *   <tr><th>Exception</th><th>HTTP status</th><th>{@code ApiError.code}</th></tr>
 *   <tr>
 *     <td>{@link MethodArgumentNotValidException}</td><td>400</td>
 *     <td>{@code VALIDATION_FAILED}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link PathSecurityViolationException}</td><td>400</td>
 *     <td>{@code PATH_SECURITY_VIOLATION}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link FileNotFoundInRepositoryException}</td><td>404</td><td>{@code FILE_NOT_FOUND}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link AgentIterationLimitExceededException}</td><td>500</td>
 *     <td>{@code AGENT_ITERATION_LIMIT_EXCEEDED}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link AgentOutputParsingException}</td><td>502</td><td>{@code AGENT_OUTPUT_INVALID}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link TransientAiException}/{@link NonTransientAiException}</td><td>502</td>
 *     <td>{@code AI_PROVIDER_FAILURE}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link HttpMessageNotReadableException}</td><td>400</td>
 *     <td>{@code MALFORMED_REQUEST}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link HttpRequestMethodNotSupportedException}</td><td>405</td>
 *     <td>{@code METHOD_NOT_ALLOWED}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link HttpMediaTypeNotSupportedException}</td><td>415</td>
 *     <td>{@code UNSUPPORTED_MEDIA_TYPE}</td>
 *   </tr>
 *   <tr><td>any other {@link Exception}</td><td>500</td><td>{@code INTERNAL_ERROR}</td></tr>
 * </table>
 *
 * <p><b>The three framework-dispatch rows above (retry 1, code review High finding) exist
 * because a catch-all {@code @ExceptionHandler(Exception.class)} intercepts <em>every</em>
 * unmapped exception, including Spring's own framework dispatch exceptions.</b>
 * {@code ExceptionHandlerExceptionResolver} (the resolver backing {@code @ExceptionHandler}
 * methods) runs at precedence order {@code 0}, strictly before Spring's own
 * {@code DefaultHandlerExceptionResolver} (which would otherwise map these three exception
 * types to 400/405/415 itself, with no {@code @RestControllerAdvice} involved at all) - so once
 * any {@code @ExceptionHandler(Exception.class)} method exists on this class, it wins for these
 * three framework exceptions too, unless each is given its own, more specific handler here.
 * Without the three rows below, a malformed JSON body or a wrong HTTP method/
 * {@code Content-Type} would silently fall through to
 * {@link #handleUnexpectedException(Exception)} and be misreported as a 500
 * {@code INTERNAL_ERROR}, logged at {@code ERROR} with a full stack trace, as though a pure
 * client mistake were a server fault - directly contradicting this class's own R8 principle (see
 * the {@code MethodArgumentNotValidException}/{@code PathSecurityViolationException}/
 * {@code FileNotFoundInRepositoryException} rows above, all logged below {@code ERROR} for the
 * same reason). All three are logged at {@code DEBUG}, matching {@code 02-rag}'s
 * {@code ApiExceptionHandler} precedent for the identical {@link HttpMessageNotReadableException}
 * case.
 *
 * <p><b>No response body ever echoes a raw exception's own {@code getMessage()} text.</b> Although
 * {@link PathSecurityViolationException}/{@link FileNotFoundInRepositoryException}'s own messages
 * were verified (by reading {@code RepositoryPathResolver}'s source directly) to carry only the
 * caller's own submitted relative-path text - never the configured repository root or any
 * internally-resolved absolute path - this handler still uses static, predefined response text for
 * every mapped exception, matching the established in-repo precedent
 * ({@code 01-prompting-llm}'s {@code ChatExceptionHandler}, {@code 02-rag}'s
 * {@code ApiExceptionHandler}: neither ever surfaces {@code exception.getMessage()} in a response
 * body). This is a deliberately more conservative choice than the minimum the acceptance criteria
 * require (which only forbid echoing {@code getMessage()} for 500/502 responses): a categorical
 * rule cannot be silently defeated by a future change to an exception's message text, and stays
 * consistent with how every other module in this repository already behaves. Full exception detail
 * (including the exception's own message) is logged server-side only, via {@link SafeLogFormatter}
 * wherever the logged text could itself carry caller-controlled content, so log lines cannot be
 * forged by a crafted path/argument (R8).
 *
 * <p><b>400 vs 404 does not leak information about files outside the repository root.</b>
 * {@code RepositoryPathResolver}'s security checks (blank/NUL input,
 * absolute/drive-qualified input,
 * normalized-path containment) always run and reject <em>before</em> any filesystem-existence check
 * (Architecture Note A1, Increment 1). Consequently a {@link PathSecurityViolationException} is
 * thrown purely from the <em>shape</em> of the input - never from whether a file actually exists at
 * the target location - so an out-of-root-shaped input always yields 400 regardless of whether
 * anything exists there. A {@link FileNotFoundInRepositoryException} (404) can only ever occur for
 * input that already resolves strictly within the repository root, so it only ever reveals
 * non-existence within the portion of the filesystem the caller is already permitted to explore,
 * not anything about what lies outside the root.
 */
@Slf4j
@RestControllerAdvice
public class CodeReviewExceptionHandler {

  static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  static final String PATH_SECURITY_VIOLATION = "PATH_SECURITY_VIOLATION";
  static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
  static final String AGENT_ITERATION_LIMIT_EXCEEDED = "AGENT_ITERATION_LIMIT_EXCEEDED";
  static final String AGENT_OUTPUT_INVALID = "AGENT_OUTPUT_INVALID";
  static final String AI_PROVIDER_FAILURE = "AI_PROVIDER_FAILURE";
  static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
  static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
  static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
  static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  static final String VALIDATION_FAILED_MESSAGE = "Request validation failed";

  static final String PATH_SECURITY_VIOLATION_MESSAGE =
    "The requested path is not permitted: it must be a relative path within the repository root.";

  static final String FILE_NOT_FOUND_MESSAGE =
    "The requested file or directory does not exist within the repository.";

  static final String AGENT_ITERATION_LIMIT_EXCEEDED_MESSAGE =
    "The code review could not be completed within the configured iteration limit.";

  static final String AGENT_OUTPUT_INVALID_MESSAGE =
    "The AI model produced a response that could not be parsed into a valid code review.";

  static final String AI_PROVIDER_FAILURE_MESSAGE =
    "The AI provider failed to process the request.";

  static final String MALFORMED_REQUEST_MESSAGE =
    "The request body could not be parsed as valid JSON.";

  static final String METHOD_NOT_ALLOWED_MESSAGE =
    "The HTTP method used is not supported for this endpoint.";

  static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE =
    "The request's Content-Type is not supported for this endpoint.";

  static final String INTERNAL_ERROR_MESSAGE =
    "An unexpected error occurred while processing the request.";

  /** Maps {@link MethodArgumentNotValidException} (blank/invalid request fields) to 400. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidationException(
    MethodArgumentNotValidException exception) {
    List<ApiViolation> violations = exception.getBindingResult().getAllErrors().stream()
      .map(this::toViolation)
      .sorted(Comparator.comparing(ApiViolation::field).thenComparing(ApiViolation::message))
      .toList();
    // A client mistake, not a server fault: logged at DEBUG, matching 02-rag's ApiExceptionHandler
    // precedent for the identical exception type. Violation field/message text originates from the
    // request DTO's own Bean Validation annotations (e.g., "must not be blank"), never from the
    // rejected value itself, so no SafeLogFormatter wrapping is needed here.
    log.debug("Code review request validation failed, violationCount={}", violations.size());
    return ResponseEntity.badRequest()
      .body(new ApiError(VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE, violations));
  }

  /** Maps {@link PathSecurityViolationException} (out-of-root/malformed path) to 400. */
  @ExceptionHandler(PathSecurityViolationException.class)
  public ResponseEntity<ApiError> handlePathSecurityViolation(
    PathSecurityViolationException exception) {
    // A security-relevant rejection of caller-supplied input, not a server fault: logged at WARN
    // (matching CodeReviewTools's own "rejected on security grounds" logging convention), with
    // the exception's own message - which carries only the caller's submitted path text - routed
    // through SafeLogFormatter since it is still caller-controlled free text that must not be
    // able to forge a log record (R8).
    log.warn("Path security violation rejected: {}",
      SafeLogFormatter.format(exception.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(new ApiError(PATH_SECURITY_VIOLATION, PATH_SECURITY_VIOLATION_MESSAGE, List.of()));
  }

  /** Maps {@link FileNotFoundInRepositoryException} (valid but missing path) to 404. */
  @ExceptionHandler(FileNotFoundInRepositoryException.class)
  public ResponseEntity<ApiError> handleFileNotFound(FileNotFoundInRepositoryException exception) {
    // A genuinely missing file is a normal client-facing outcome, not a security concern and not a
    // server fault: logged at DEBUG, one level below the WARN used for a security violation.
    log.debug("Requested path not found in repository: {}",
      SafeLogFormatter.format(exception.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
      .body(new ApiError(FILE_NOT_FOUND, FILE_NOT_FOUND_MESSAGE, List.of()));
  }

  /** Maps {@link AgentIterationLimitExceededException} (ReAct loop exhausted) to 500. */
  @ExceptionHandler(AgentIterationLimitExceededException.class)
  public ResponseEntity<ApiError> handleIterationLimitExceeded(
    AgentIterationLimitExceededException exception) {
    // A server-side condition the caller cannot fix by changing input (Experiment #7's guard
    // tripped): logged at ERROR with the exception passed as the trailing SLF4J argument so the
    // framework's own formatter renders the full stack trace, matching CodeReviewReactAgent's own
    // established ERROR-logging convention for this exact failure mode.
    log.error("Code review agent exhausted its iteration limit", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(new ApiError(AGENT_ITERATION_LIMIT_EXCEEDED, AGENT_ITERATION_LIMIT_EXCEEDED_MESSAGE,
        List.of()));
  }

  /** Maps {@link AgentOutputParsingException} (model output failed to parse) to 502. */
  @ExceptionHandler(AgentOutputParsingException.class)
  public ResponseEntity<ApiError> handleAgentOutputParsing(AgentOutputParsingException exception) {
    // A server-side condition (the model's structured-output contract was violated after
    // CodeReviewReactAgent's own retry-once policy was exhausted) the caller cannot fix by changing
    // input: logged at ERROR with the full exception for debugging, same convention as above.
    log.error("Code review agent produced output that could not be parsed into a valid response",
      exception);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
      .body(new ApiError(AGENT_OUTPUT_INVALID, AGENT_OUTPUT_INVALID_MESSAGE, List.of()));
  }

  /** Maps {@link TransientAiException}/{@link NonTransientAiException} (AI provider) to 502. */
  @ExceptionHandler({TransientAiException.class, NonTransientAiException.class})
  public ResponseEntity<ApiError> handleAiProviderFailure(RuntimeException exception) {
    // Spring AI's TransientAiException/NonTransientAiException (org.springframework.ai.retry)
    // each extend RuntimeException directly with no narrower common supertype (confirmed by
    // decompilation in Increment 2 - see CodeReviewTools's own identical catch-clause comment),
    // matching the exact pairing named in the plan's mapping table. An upstream AI-provider
    // failure, not a client mistake: logged at ERROR, mirroring 01-prompting-llm's
    // ChatExceptionHandler.handleAiException.
    log.error("AI provider request failed, type={}", exception.getClass().getSimpleName(),
      exception);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
      .body(new ApiError(AI_PROVIDER_FAILURE, AI_PROVIDER_FAILURE_MESSAGE, List.of()));
  }

  /** Maps {@link HttpMessageNotReadableException} (unparseable JSON body) to 400. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleMalformedRequest(
    HttpMessageNotReadableException exception) {
    // A malformed/unparseable JSON request body is a pure client mistake, not a server fault
    // (R8) - see this class's own Javadoc for why this dedicated row exists
    // (ExceptionHandlerExceptionResolver precedence). Logged at DEBUG, matching 02-rag's
    // ApiExceptionHandler precedent for the identical exception type; the raw parser message is
    // not caller-controlled free text in the log-forgery sense (it originates from Jackson's own
    // parser, not from a value RepositoryPathResolver echoes back), so no SafeLogFormatter
    // wrapping is needed here.
    log.debug("Malformed code review request body rejected, type={}",
      exception.getClass().getName());
    return ResponseEntity.badRequest()
      .body(new ApiError(MALFORMED_REQUEST, MALFORMED_REQUEST_MESSAGE, List.of()));
  }

  /** Maps {@link HttpRequestMethodNotSupportedException} (wrong HTTP method) to 405. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiError> handleMethodNotSupported(
    HttpRequestMethodNotSupportedException exception) {
    // Same catch-all-swallows-framework-dispatch-exceptions risk as handleMalformedRequest
    // above: an unsupported HTTP method (e.g., GET /code-review) is a pure client mistake - 405,
    // not 500 - logged at DEBUG.
    log.debug("Unsupported HTTP method rejected for code review request, method={}",
      exception.getMethod());
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
      .body(new ApiError(METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_MESSAGE, List.of()));
  }

  /** Maps {@link HttpMediaTypeNotSupportedException} (unsupported Content-Type) to 415. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiError> handleUnsupportedMediaType(
    HttpMediaTypeNotSupportedException exception) {
    // Same category as the two rows above, added alongside them rather than left as a residual
    // gap: CodeReviewApi.processUserQuery declares consumes = APPLICATION_JSON_VALUE, so a
    // request with a different (or missing) Content-Type throws this exact framework exception
    // before the body is ever read - exactly as much a pure client mistake as malformed JSON or a
    // wrong HTTP method, and subject to the identical ExceptionHandlerExceptionResolver-precedence
    // risk. Logged at DEBUG.
    log.debug("Unsupported media type rejected for code review request, contentType={}",
      exception.getContentType());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
      .body(new ApiError(UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_MESSAGE, List.of()));
  }

  /** Maps any other {@link Exception} (unanticipated failure) to 500. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
    log.error("Unexpected code review request failure, type={}", exception.getClass().getName(),
      exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(new ApiError(INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, List.of()));
  }

  private ApiViolation toViolation(ObjectError error) {
    String field = error instanceof FieldError fieldError
      ? fieldError.getField()
      : error.getObjectName();
    return new ApiViolation(field, error.getDefaultMessage());
  }
}
