package com.epam.codereview.exception;

import com.epam.codereview.api.model.ApiError;
import com.epam.codereview.api.model.ApiViolation;
import com.epam.codereview.support.SafeLogFormatter;
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
 * status and a machine-readable {@link ApiError} body, per {@code context/PLAN.md} Increment 5's
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
 *     <td>{@link PrReferenceNotFoundException}</td><td>400</td>
 *     <td>{@code PR_REFERENCE_NOT_FOUND}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link AgentIterationLimitExceededException}</td><td>500</td>
 *     <td>{@code AGENT_ITERATION_LIMIT_EXCEEDED}</td>
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
 * <p><b>The three framework-dispatch rows above exist because a catch-all
 * {@code @ExceptionHandler(Exception.class)} intercepts <em>every</em> unmapped exception,
 * including Spring's own framework dispatch exceptions.</b> {@code ExceptionHandlerExceptionResolver}
 * (the resolver backing {@code @ExceptionHandler} methods) runs at precedence order {@code 0},
 * strictly before Spring's own {@code DefaultHandlerExceptionResolver} (which would otherwise map
 * these three exception types to 400/405/415 itself, with no {@code @RestControllerAdvice} involved
 * at all) - so once any {@code @ExceptionHandler(Exception.class)} method exists on this class, it
 * wins for these three framework exceptions too, unless each is given its own, more specific
 * handler here. Without the three rows below, a malformed JSON body or a wrong HTTP method/
 * {@code Content-Type} would silently fall through to {@link #handleUnexpectedException(Exception)}
 * and be misreported as a 500 {@code INTERNAL_ERROR}, logged at {@code ERROR} with a full stack
 * trace, as though a pure client mistake were a server fault - directly contradicting this class's
 * own R8 principle (see the {@code MethodArgumentNotValidException}/
 * {@code PrReferenceNotFoundException} rows above, both logged below {@code ERROR} for the same
 * reason). This is a Spring MVC dispatch fact independent of this module's own code - ported as-is
 * from {@code 03-code-review-agent}'s identically-purposed {@code CodeReviewExceptionHandler}, whose
 * own Javadoc documents the same finding.
 *
 * <p><b>No dedicated "MCP unavailable" row exists in the table above - deliberately, not by
 * omission.</b> A per-request MCP tool failure ({@code McpError}/{@code McpTransportException}, both
 * extend {@code RuntimeException} directly) is caught inside Spring AI's own
 * {@code SyncMcpToolCallback}, rethrown as {@code ToolExecutionException}, and - since this
 * application never sets {@code spring.ai.tools.throw-exception-on-error=true} - absorbed by Spring
 * AI's own {@code DefaultToolExecutionExceptionProcessor} into a text tool-result fed back to the
 * model, rather than ever propagating out of {@code CodeReviewReactAgent#interact(String)} to reach
 * this class at all (see {@code context/PLAN.md}'s Architecture Notes for the full
 * decompilation-traced call chain). There is consequently no exception type an MCP-unavailability
 * row here could ever catch; the one failure mode an unreachable/misconfigured MCP endpoint actually
 * produces is an application-startup failure (a bad endpoint fails context refresh before any
 * request is ever accepted), which is an ops/startup concern documented in {@code RUNBOOK.md}, not
 * an HTTP status this class could ever return.
 *
 * <p><b>No response body ever echoes a raw exception's own {@code getMessage()} text.</b> This
 * handler uses static, predefined response text for every mapped exception - even though
 * {@link PrReferenceNotFoundException}'s own message never actually echoes the caller's raw
 * {@code userInput} today (see that class's Javadoc) - matching the established in-repo precedent
 * ({@code 01-prompting-llm}'s {@code ChatExceptionHandler}, {@code 02-rag}'s
 * {@code ApiExceptionHandler}, {@code 03-code-review-agent}'s own {@code CodeReviewExceptionHandler}:
 * none of them ever surfaces {@code exception.getMessage()} in a response body). This is a
 * deliberately conservative, categorical rule that cannot be silently defeated by a future change to
 * an exception's message text. Full exception detail (including the exception's own message) is
 * logged server-side only, via {@link SafeLogFormatter} wherever the logged text could itself carry
 * caller-controlled content (see {@link #handlePrReferenceNotFound(PrReferenceNotFoundException)}),
 * so log lines cannot be forged by a crafted {@code userInput} (R8).
 */
@Slf4j
@RestControllerAdvice
public class CodeReviewExceptionHandler {

  static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  static final String PR_REFERENCE_NOT_FOUND = "PR_REFERENCE_NOT_FOUND";
  static final String AGENT_ITERATION_LIMIT_EXCEEDED = "AGENT_ITERATION_LIMIT_EXCEEDED";
  static final String AI_PROVIDER_FAILURE = "AI_PROVIDER_FAILURE";
  static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
  static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
  static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
  static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  static final String VALIDATION_FAILED_MESSAGE = "Request validation failed";

  static final String PR_REFERENCE_NOT_FOUND_MESSAGE =
    "The request does not identify a GitHub pull request to review: include a PR URL, an "
      + "owner/repo reference, or a #<number> token.";

  static final String AGENT_ITERATION_LIMIT_EXCEEDED_MESSAGE =
    "The PR review could not be completed within the configured iteration limit.";

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
    // A client mistake, not a server fault: logged at DEBUG, matching 02-rag's/03-code-review-agent's
    // ApiExceptionHandler/CodeReviewExceptionHandler precedent for the identical exception type.
    // Violation field/message text originates from the request DTO's own Bean Validation
    // annotations (e.g., "must not be blank"), never from the rejected value itself, so no
    // SafeLogFormatter wrapping is needed here.
    log.debug("PR review request validation failed, violationCount={}", violations.size());
    return ResponseEntity.badRequest()
      .body(new ApiError(VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE, violations));
  }

  /** Maps {@link PrReferenceNotFoundException} (no PR-shaped signal in {@code userInput}) to 400. */
  @ExceptionHandler(PrReferenceNotFoundException.class)
  public ResponseEntity<ApiError> handlePrReferenceNotFound(PrReferenceNotFoundException exception) {
    // A client mistake, not a server fault: logged at DEBUG. The exception's own message could in
    // principle reflect caller-influenced content (userInput is unbounded free text), so it is
    // routed through SafeLogFormatter before being logged, even though PrReferenceResolver's
    // current message text never actually echoes the raw userInput (R8) - mirrors
    // 03-code-review-agent's identical treatment of PathSecurityViolationException's message.
    log.debug("PR reference not found in request: {}", SafeLogFormatter.format(exception.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(new ApiError(PR_REFERENCE_NOT_FOUND, PR_REFERENCE_NOT_FOUND_MESSAGE, List.of()));
  }

  /** Maps {@link AgentIterationLimitExceededException} (ReAct loop exhausted) to 500. */
  @ExceptionHandler(AgentIterationLimitExceededException.class)
  public ResponseEntity<ApiError> handleIterationLimitExceeded(
    AgentIterationLimitExceededException exception) {
    // A server-side condition the caller cannot fix by changing input: logged at ERROR with the
    // exception passed as the trailing SLF4J argument so the framework's own formatter renders the
    // full stack trace, matching CodeReviewReactAgent's own established ERROR-logging convention
    // for this exact failure mode.
    log.error("PR review agent exhausted its iteration limit", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(new ApiError(AGENT_ITERATION_LIMIT_EXCEEDED, AGENT_ITERATION_LIMIT_EXCEEDED_MESSAGE,
        List.of()));
  }

  /** Maps {@link TransientAiException}/{@link NonTransientAiException} (AI provider) to 502. */
  @ExceptionHandler({TransientAiException.class, NonTransientAiException.class})
  public ResponseEntity<ApiError> handleAiProviderFailure(RuntimeException exception) {
    // Spring AI's TransientAiException/NonTransientAiException (org.springframework.ai.retry) each
    // extend RuntimeException directly with no narrower common supertype (confirmed by
    // decompilation in this module's own Increment 2 - see CodeReviewTools's identical
    // catch-clause comment), matching the exact pairing named in the plan's mapping table. An
    // upstream AI-provider failure, not a client mistake: logged at ERROR, mirroring
    // 01-prompting-llm's ChatExceptionHandler.handleAiException.
    log.error("AI provider request failed, type={}", exception.getClass().getSimpleName(),
      exception);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
      .body(new ApiError(AI_PROVIDER_FAILURE, AI_PROVIDER_FAILURE_MESSAGE, List.of()));
  }

  /** Maps {@link HttpMessageNotReadableException} (unparseable JSON body) to 400. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleMalformedRequest(
    HttpMessageNotReadableException exception) {
    // A malformed/unparseable JSON request body is a pure client mistake, not a server fault (R8) -
    // see this class's own Javadoc for why this dedicated row exists
    // (ExceptionHandlerExceptionResolver precedence). Logged at DEBUG; the raw parser message is
    // not caller-controlled free text in the log-forgery sense (it originates from Jackson's own
    // parser, not from a value this module echoes back), so no SafeLogFormatter wrapping is needed
    // here.
    log.debug("Malformed PR review request body rejected, type={}",
      exception.getClass().getName());
    return ResponseEntity.badRequest()
      .body(new ApiError(MALFORMED_REQUEST, MALFORMED_REQUEST_MESSAGE, List.of()));
  }

  /** Maps {@link HttpRequestMethodNotSupportedException} (wrong HTTP method) to 405. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiError> handleMethodNotSupported(
    HttpRequestMethodNotSupportedException exception) {
    // Same catch-all-swallows-framework-dispatch-exceptions risk as handleMalformedRequest above:
    // an unsupported HTTP method (e.g., GET /code-review) is a pure client mistake - 405, not 500 -
    // logged at DEBUG.
    log.debug("Unsupported HTTP method rejected for PR review request, method={}",
      exception.getMethod());
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
      .body(new ApiError(METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_MESSAGE, List.of()));
  }

  /** Maps {@link HttpMediaTypeNotSupportedException} (unsupported Content-Type) to 415. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiError> handleUnsupportedMediaType(
    HttpMediaTypeNotSupportedException exception) {
    // Same category as the two rows above, added alongside them rather than left as a residual
    // gap: CodeReviewApi.processUserQuery declares consumes = APPLICATION_JSON_VALUE, so a request
    // with a different (or missing) Content-Type throws this exact framework exception before the
    // body is ever read - exactly as much a pure client mistake as malformed JSON or a wrong HTTP
    // method, and subject to the identical ExceptionHandlerExceptionResolver-precedence risk.
    // Logged at DEBUG.
    log.debug("Unsupported media type rejected for PR review request, contentType={}",
      exception.getContentType());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
      .body(new ApiError(UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_MESSAGE, List.of()));
  }

  /** Maps any other {@link Exception} (unanticipated failure) to 500. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
    log.error("Unexpected PR review request failure, type={}", exception.getClass().getName(),
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
