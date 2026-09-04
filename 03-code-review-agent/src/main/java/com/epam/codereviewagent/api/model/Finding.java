package com.epam.codereviewagent.api.model;

/**
 * A single, structured code-review finding. Per the ticket's own wording ("Each finding MAY
 * include..."), every field beyond structural line-number sanity is optional; {@code file},
 * {@code rule}, {@code severity}, {@code explanation} and {@code recommendation} may all be
 * {@code null} if the model has nothing to report for that slot.
 * <p>
 * {@code startLine}/{@code endLine} are nullable (a finding may not reference a specific line
 * range at all), but if present they must be internally consistent: this record's compact
 * canonical constructor rejects a non-positive line number and an {@code endLine} that is less
 * than {@code startLine}, regardless of whether the instance is built directly in Java code or
 * produced by Jackson while deserializing a model's JSON response. This guards R7's
 * evidence-based guarantee against impossible line references (see
 * {@code CodeReviewStructuredOutputConverterTest} for the adversarial cases proving this).
 * <p>
 * <b>Recorded, explicitly out-of-scope limitation</b>: this record has no knowledge of the
 * actual file content the finding refers to, so it cannot validate that {@code startLine}/
 * {@code endLine} actually exist within that file (e.g. a finding claiming line 9999 of a
 * 40-line file is not rejected here). That check requires cross-referencing against the file
 * content retrieved by the {@code readFile} tool during the ReAct loop, which only exists from
 * Increment 5 onward — see {@code context/PROGRESS.md}'s Increment 4 entry for the explicit
 * hand-off note.
 */
public record Finding(
  String file,
  Integer startLine,
  Integer endLine,
  String rule,
  Severity severity,
  String explanation,
  String recommendation
) {

  public Finding {
    if (startLine != null && startLine <= 0) {
      throw new IllegalArgumentException(
        "Finding.startLine must be a positive (>= 1) line number, but was: " + startLine);
    }
    if (endLine != null && endLine <= 0) {
      throw new IllegalArgumentException(
        "Finding.endLine must be a positive (>= 1) line number, but was: " + endLine);
    }
    if (startLine != null && endLine != null && endLine < startLine) {
      throw new IllegalArgumentException(
        "Finding.endLine (" + endLine + ") must not be less than Finding.startLine ("
          + startLine + ")");
    }
  }
}
