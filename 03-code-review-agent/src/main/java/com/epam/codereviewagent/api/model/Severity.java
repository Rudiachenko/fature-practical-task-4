package com.epam.codereviewagent.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Severity of a single {@link Finding}, exactly the five values named by the ticket
 * ("blocker/high/medium/low/info"). <b>Serialization</b> always emits exactly the lowercase JSON
 * strings {@code "blocker"}, {@code "high"}, {@code "medium"}, {@code "low"}, {@code "info"} — the
 * ticket's own verbatim values, never changed regardless of how deserialization is relaxed below.
 * <p>
 * <b>Deserialization is case-insensitive (code review, retry 1, Medium finding).</b> Real models
 * routinely emit capitalized severities (e.g. {@code "HIGH"}, {@code "High"}); rejecting the whole
 * response over a mere casing difference was judged too costly once combined with this contract's
 * all-or-nothing parsing (one bad {@code severity} discards the entire review and every other,
 * otherwise-valid finding — see {@code context/PROGRESS.md}'s Increment 4 retry 1 entry for the
 * Increment 5 hand-off note on that separate, deliberately-unchanged behavior). {@link
 * #fromJsonValue(String)} therefore matches case-insensitively (via {@link String#equalsIgnoreCase}) and
 * additionally trims leading/trailing whitespace before matching (e.g. {@code " high "} is
 * accepted) — a deliberate leniency decision, documented here rather than left implicit, on the
 * theory that whitespace padding is a formatting artifact rather than a signal the model meant
 * something else. A wholly unrelated value (e.g. {@code "critical"}), an empty/blank string, and
 * {@code null} remain rejected with an {@link IllegalArgumentException}: only the five named
 * severities, under any casing/surrounding whitespace, are accepted. The caller
 * ({@code CodeReviewStructuredOutputConverter}) turns that rejection into a well-defined
 * {@code AgentOutputParsingException} rather than letting it surface as a raw, uncaught exception.
 */
public enum Severity {

  BLOCKER("blocker"),
  HIGH("high"),
  MEDIUM("medium"),
  LOW("low"),
  INFO("info");

  private final String jsonValue;

  Severity(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String jsonValue() {
    return jsonValue;
  }

  @JsonCreator
  public static Severity fromJsonValue(String jsonValue) {
    if (jsonValue != null) {
      String normalized = jsonValue.trim();
      for (Severity severity : values()) {
        if (severity.jsonValue.equalsIgnoreCase(normalized)) {
          return severity;
        }
      }
    }
    throw new IllegalArgumentException(
      "Unknown severity value: '" + jsonValue
        + "'. Allowed values are (case-insensitively, ignoring surrounding whitespace): "
        + "blocker, high, medium, low, info.");
  }
}
