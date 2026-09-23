package com.epam.codereviewagent.support;

import java.util.regex.Pattern;

/**
 * Normalizes, redacts, and bounds user-controlled values before they are written to logs.
 *
 * <p>Originally ported verbatim (package renamed only) from
 * {@code 01-prompting-llm/src/main/java/com/epam/prompting_llm/support/SafeLogFormatter.java}, the
 * in-repo precedent for R8's "user input and file content must not be able to forge log lines"
 * requirement. {@code CodeReviewReactAgent} applies this at every log call site that could carry
 * caller-supplied or repository-derived text: the incoming request, per-tool-call argument
 * summaries, and any model-derived text (e.g., a parse-failure reason) that might itself echo
 * untrusted content back.
 *
 * <p><b>Diverged from the {@code 01-prompting-llm} original in Increment 5 retry 1 (code review,
 * Medium finding) - deliberately, not by accident; {@code 01-prompting-llm} itself is not
 * modified.</b> The original port stripped only {@code \r\n\t} and redacted {@code api-key}/
 * {@code Authorization} substrings, which was enough for {@code 01-prompting-llm} because the
 * only attacker-influenceable input reaching it was end-user chat text. In this module,
 * {@link com.epam.codereviewagent.service.CodeReviewTools#readFile(String)}/
 * {@link com.epam.codereviewagent.service.CodeReviewTools#exploreRepository(String)}
 * return real, attacker-influenceable repository file content that this class's caller
 * ({@code CodeReviewReactAgent}) can pass straight into a tool-call argument summary or,
 * indirectly (via {@code retrieveCodeLanguage}/{@code getCodebaseContext}'s
 * {@code codeSnippet} argument), into further logged text - so a reviewed file containing a raw
 * ANSI/terminal escape sequence (the ESC code point U+001B followed by a control-sequence body,
 * e.g., {@code "[31m"}) in a comment could forge colored or cursor-moving output in a live log
 * stream once written by any ANSI-aware terminal/log viewer. This class now also strips the full
 * C0 control-character range (including the ESC code point, U+001B, that begins every ANSI escape
 * sequence) and the two Unicode line-separator code points some log viewers honor as line breaks
 * (U+2028 LINE SEPARATOR, U+0085 NEXT LINE/NEL) - neither of which the original
 * {@code \r\n\t}-only pattern touched. (Deliberately described here by code point rather than
 * embedded as literal characters in this Javadoc, to avoid the same authoring-tool/source-encoding
 * fragility already recorded for a literal NUL character in Increment 1's
 * {@code context/PROGRESS.md} entry.)
 */
public final class SafeLogFormatter {

  private static final int MAX_LOG_VALUE_LENGTH = 500;

  /**
   * Matches the full C0 control-character range (U+0000-U+001F, which includes {@code \r},
   * {@code \n}, {@code \t}, and the ESC code point U+001B that begins every ANSI/terminal escape
   * sequence), plus the two Unicode line-separator code points (U+2028 LINE SEPARATOR, U+0085 NEXT
   * LINE/NEL) some log viewers honor as line breaks even though they are not {@code \r}/{@code \n}.
   * Collapsing every contiguous run to a single space both preserves the original port's
   * log-line-forging protection ({@code \r\n\t} still become spaces, so a value can never inject a
   * raw newline into a log line) and closes the ANSI-escape/Unicode-line-separator gap described in
   * this class's own Javadoc.
   */
  private static final Pattern UNSAFE_CONTROL_CHARACTERS = Pattern.compile(
    "[\\x00-\\x1F\\u2028\\u0085]+");
  private static final Pattern API_KEYS = Pattern.compile(
    "(?i)\\b(api[-_ ]?key)\\b\\s*[:=]\\s*\\S+");
  private static final Pattern AUTHORIZATION = Pattern.compile(
    "(?i)\\bauthorization\\b\\s*[:=]\\s*(?:bearer\\s+)?\\S+");

  private SafeLogFormatter() {
  }

  /**
   * @param value the caller-supplied or repository-derived text to make log-safe; {@code null}
   *              is accepted and rendered as the literal {@code "<null>"}
   * @return {@code value} with unsafe control/line-separator characters collapsed to spaces,
   *         {@code api-key}/{@code Authorization} values redacted, and length bounded to
   *         {@link #MAX_LOG_VALUE_LENGTH} characters
   */
  public static String format(String value) {
    if (value == null) {
      return "<null>";
    }

    String singleLine = UNSAFE_CONTROL_CHARACTERS.matcher(value).replaceAll(" ");
    String redactedAuthorization = AUTHORIZATION.matcher(singleLine)
      .replaceAll("Authorization=[REDACTED]");
    String redacted = API_KEYS.matcher(redactedAuthorization).replaceAll("$1=[REDACTED]");
    if (redacted.length() <= MAX_LOG_VALUE_LENGTH) {
      return redacted;
    }
    return redacted.substring(0, MAX_LOG_VALUE_LENGTH) + "...";
  }
}
