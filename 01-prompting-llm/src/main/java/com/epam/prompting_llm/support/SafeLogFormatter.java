package com.epam.prompting_llm.support;

import java.util.regex.Pattern;

/** Normalizes, redacts, and bounds user-controlled values before they are written to logs. */
public final class SafeLogFormatter {

  private static final int MAX_LOG_VALUE_LENGTH = 500;

  private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n\\t]+");
  private static final Pattern API_KEYS = Pattern.compile(
    "(?i)\\b(api[-_ ]?key)\\b\\s*[:=]\\s*\\S+");
  private static final Pattern AUTHORIZATION = Pattern.compile(
    "(?i)\\bauthorization\\b\\s*[:=]\\s*(?:bearer\\s+)?\\S+");

  private SafeLogFormatter() {
  }

  public static String format(String value) {
    if (value == null) {
      return "<null>";
    }

    String singleLine = LINE_BREAKS.matcher(value).replaceAll(" ");
    String redactedAuthorization = AUTHORIZATION.matcher(singleLine)
      .replaceAll("Authorization=[REDACTED]");
    String redacted = API_KEYS.matcher(redactedAuthorization).replaceAll("$1=[REDACTED]");
    if (redacted.length() <= MAX_LOG_VALUE_LENGTH) {
      return redacted;
    }
    return redacted.substring(0, MAX_LOG_VALUE_LENGTH) + "...";
  }
}
