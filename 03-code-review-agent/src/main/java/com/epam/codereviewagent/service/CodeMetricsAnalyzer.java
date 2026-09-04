package com.epam.codereviewagent.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Computes objective, deterministic, language-agnostic code metrics for a code snippet without any
 * LLM call or real parser: total line count, the longest brace-delimited block's approximate line
 * span (used as a proxy for "longest method"), and the maximum brace-nesting depth.
 *
 * <p>This is a deliberately lightweight heuristic, not a compiler-grade parser - but it does run
 * a small state-machine lexer pass (not a full AST) that recognizes Java-like string literals
 * ({@code "..."}), character literals ({@code '...'}), text blocks ({@code """..."""}), line
 * comments ({@code //...}) and traditional block comments (slash-asterisk through the matching
 * closing asterisk-slash marker) and skips any {@code '{'}/{@code '}'}
 * found inside them. This fixes a real, empirically reproduced defect: without this lexer pass, a
 * brace character inside an ordinary string literal (e.g. {@code String regexLike = "{";}), a
 * line/block comment, or a text block was previously popped/pushed onto the same LIFO brace stack
 * as real structural braces, corrupting {@code maxNestingDepth}/{@code longestMethodLineSpan}
 * bookkeeping for the rest of the snippet - not just for the line containing the phantom brace.
 * Escaped quotes ({@code \"}, {@code \\}) inside string/character/text-block literals are handled
 * so an escaped quote does not prematurely end the literal.</p>
 *
 * <p><strong>Recorded limitation</strong>: this lexer recognizes Java-like
 * string/comment/text-block conventions specifically. Other languages with materially different
 * conventions (e.g. Python's triple-quoted strings using {@code '''}, shell here-docs, Perl regex
 * delimiters) are not specially recognized and may still be miscounted if their brace-containing
 * constructs don't happen to also be valid Java string/comment syntax. This remains an
 * approximate heuristic, not a full multi-language AST/static-analysis engine, per
 * {@code context/TICKET.md}'s Out of Scope section - and is disclosed directly in
 * {@code CodeReviewTools#analyzeCodeMetrics}'s {@code @Tool} description, since that description
 * (not this Javadoc) is the only text ever visible to the model calling this tool.</p>
 */
public final class CodeMetricsAnalyzer {

  private CodeMetricsAnalyzer() {
  }

  /**
   * Analyzes {@code code} and returns its line count, longest brace-delimited block line span,
   * and maximum brace-nesting depth. Braces occurring inside a recognized string/character
   * literal, text block, line comment, or block comment are skipped entirely (never pushed/popped
   * on the brace stack), so they cannot corrupt the depth bookkeeping of real structural braces
   * elsewhere in the snippet - see the class Javadoc for the exact defect this fixes.
   *
   * <p>The longest-block span is computed as {@code closingLine - openingLine + 1} for every
   * matched {@code '{'}/{@code '}'} pair found anywhere in the snippet (at any nesting depth),
   * taking the maximum across all matched pairs; a brace that is never closed contributes to
   * {@code maxNestingDepth} but not to any span, and an unmatched closing brace (no corresponding
   * open brace on the stack) is ignored rather than throwing.</p>
   *
   * @param code the source code snippet to analyze; must not be {@code null} (an empty string is
   *             valid and yields all-zero metrics)
   * @return the computed {@link CodeMetrics}
   * @throws IllegalArgumentException if {@code code} is {@code null}
   */
  static CodeMetrics analyze(String code) {
    if (code == null) {
      throw new IllegalArgumentException("code must not be null");
    }

    int lineCount = (int) code.lines().count();
    int depth = 0;
    int maxNestingDepth = 0;
    int currentLine = 1;
    int longestMethodLineSpan = 0;
    Deque<Integer> openBraceLines = new ArrayDeque<>();
    LexState state = LexState.NORMAL;
    int length = code.length();
    int index = 0;

    while (index < length) {
      char character = code.charAt(index);

      switch (state) {
        case NORMAL -> {
          if (character == '\n') {
            currentLine++;
            index++;
          } else if (startsWith(code, index, "\"\"\"")) {
            state = LexState.TEXT_BLOCK;
            index += 3;
          } else if (character == '"') {
            state = LexState.STRING_LITERAL;
            index++;
          } else if (character == '\'') {
            state = LexState.CHAR_LITERAL;
            index++;
          } else if (startsWith(code, index, "//")) {
            state = LexState.LINE_COMMENT;
            index += 2;
          } else if (startsWith(code, index, "/*")) {
            state = LexState.BLOCK_COMMENT;
            index += 2;
          } else if (character == '{') {
            depth++;
            maxNestingDepth = Math.max(maxNestingDepth, depth);
            openBraceLines.push(currentLine);
            index++;
          } else if (character == '}' && !openBraceLines.isEmpty()) {
            int openedAtLine = openBraceLines.pop();
            longestMethodLineSpan = Math.max(longestMethodLineSpan, currentLine - openedAtLine + 1);
            depth = Math.max(depth - 1, 0);
            index++;
          } else {
            index++;
          }
        }
        case LINE_COMMENT -> {
          if (character == '\n') {
            state = LexState.NORMAL;
            currentLine++;
          }
          index++;
        }
        case BLOCK_COMMENT -> {
          if (character == '\n') {
            currentLine++;
            index++;
          } else if (startsWith(code, index, "*/")) {
            state = LexState.NORMAL;
            index += 2;
          } else {
            index++;
          }
        }
        case STRING_LITERAL -> {
          if (character == '\\' && index + 1 < length) {
            if (code.charAt(index + 1) == '\n') {
              currentLine++;
            }
            index += 2;
          } else if (character == '\n') {
            // Defensive recovery: an unescaped newline inside a "..." literal is not valid Java,
            // but this is a heuristic lexer over arbitrary/pasted snippets, not a compiler - treat
            // it as an implicit close so a malformed/truncated snippet cannot leave the rest of
            // the file stuck in STRING_LITERAL state forever.
            state = LexState.NORMAL;
            currentLine++;
            index++;
          } else if (character == '"') {
            state = LexState.NORMAL;
            index++;
          } else {
            index++;
          }
        }
        case CHAR_LITERAL -> {
          if (character == '\\' && index + 1 < length) {
            if (code.charAt(index + 1) == '\n') {
              currentLine++;
            }
            index += 2;
          } else if (character == '\n') {
            state = LexState.NORMAL;
            currentLine++;
            index++;
          } else if (character == '\'') {
            state = LexState.NORMAL;
            index++;
          } else {
            index++;
          }
        }
        case TEXT_BLOCK -> {
          if (character == '\\' && index + 1 < length) {
            if (code.charAt(index + 1) == '\n') {
              currentLine++;
            }
            index += 2;
          } else if (character == '\n') {
            currentLine++;
            index++;
          } else if (startsWith(code, index, "\"\"\"")) {
            state = LexState.NORMAL;
            index += 3;
          } else {
            index++;
          }
        }
      }
    }

    return new CodeMetrics(lineCount, longestMethodLineSpan, maxNestingDepth);
  }

  /**
   * @return {@code true} if {@code code} contains {@code token} starting exactly at {@code index}
   *         (bounds-safe: {@code false} if {@code token} would run past the end of {@code code})
   */
  private static boolean startsWith(String code, int index, String token) {
    return code.regionMatches(index, token, 0, token.length());
  }

  /**
   * @param lineCount              total number of lines in the analyzed snippet
   * @param longestMethodLineSpan  the largest {@code closingLine - openingLine + 1} span among
   *                                every matched brace pair found in the snippet; {@code 0} if no
   *                                brace pair was ever closed
   * @param maxNestingDepth        the deepest brace-nesting level reached while scanning the
   *                                snippet
   */
  record CodeMetrics(int lineCount, int longestMethodLineSpan, int maxNestingDepth) {
  }

  /**
   * Lexer states used while scanning {@link #analyze(String)}'s input. Only {@link #NORMAL} treats
   * {@code '{'}/{@code '}'} as structural braces; every other state exists purely to know when a
   * literal/comment region ends so structural brace-counting can safely resume.
   */
  private enum LexState {
    NORMAL, LINE_COMMENT, BLOCK_COMMENT, STRING_LITERAL, CHAR_LITERAL, TEXT_BLOCK
  }
}
