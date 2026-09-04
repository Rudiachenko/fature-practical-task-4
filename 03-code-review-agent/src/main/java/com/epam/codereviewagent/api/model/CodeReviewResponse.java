package com.epam.codereviewagent.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;

/**
 * Dual-output response of a code review request.
 * <p>
 * {@code review} is kept at its original name/position for backward compatibility with the
 * ticket's own documented example response ({@code {"review": "..."}}) — see
 * {@code 03-code-review-agent/README.md}'s "Response example" section, which this type must
 * remain a valid superset of (R16). {@code findings}/{@code truncated} are additive: JSON that
 * only carries {@code review} (the README's own example) still deserializes successfully, with
 * {@code findings} defaulting to an empty, never-null list and {@code truncated} defaulting to
 * {@code false}.
 *
 * @param review     human-readable review summary (R9)
 * @param findings   machine-readable structured findings (R10); never {@code null} — a missing
 *                   or explicit {@code null} JSON value normalizes to an empty list
 * @param truncated  {@code true} if any evidence gathered during the review was truncated
 *                   (e.g. a file exceeded the configured character limit), so a consumer of this
 *                   response can tell a review is based on partial evidence
 */
public record CodeReviewResponse(
  String review,
  List<Finding> findings,
  boolean truncated
) {

  public CodeReviewResponse {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /**
   * Convenience constructor kept for backward compatibility with call sites that only have a
   * review string to report (e.g. an honest "no evidence gathered" override); defaults
   * {@code findings} to empty and {@code truncated} to {@code false}.
   * <p>
   * <b>{@code @JsonCreator(mode = DISABLED)} is load-bearing, not decorative.</b> Jackson's
   * record support auto-detects a single-{@code String}-argument constructor like this one as an
   * implicit <i>delegating</i> creator, meaning any bare top-level JSON string (e.g.
   * {@code "just a string"}) reaching
   * {@code com.epam.codereviewagent.service.CodeReviewStructuredOutputConverter#convert(String)}
   * would otherwise silently deserialize into a "successful" {@code CodeReviewResponse} with
   * {@code review} set to that raw string and {@code findings} left empty — indistinguishable
   * from a genuine "no issues found" result. Explicitly disabling this constructor as a creator
   * forces Jackson back onto the three-argument canonical constructor's property-based creator
   * for all JSON deserialization, so a bare JSON scalar (string or number) at the top level
   * correctly fails with a parse error instead of being silently accepted. This constructor
   * remains fully usable from ordinary Java code (e.g. Increment 5's evidence-enforcement
   * override) — only its use as a Jackson creator is disabled. See
   * {@code CodeReviewStructuredOutputConverterTest}'s
   * {@code shouldThrowAgentOutputParsingException_whenTopLevelJsonIsABareString}/
   * {@code ...ABareNumber} for the regression tests proving this.
   */
  @JsonCreator(mode = JsonCreator.Mode.DISABLED)
  public CodeReviewResponse(String review) {
    this(review, List.of(), false);
  }
}
