package com.epam.codereviewagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeMetricsAnalyzerTest {

  // Hand-authored fixture 1: 10 flat lines, no braces at all.
  // Hand-computed: lineCount=10, longestMethodLineSpan=0 (no brace pair ever closes),
  // maxNestingDepth=0 (no brace ever opens).
  private static final String FLAT_TEN_LINE_SNIPPET = String.join("\n",
    "// line 1: header comment",
    "// line 2: header comment",
    "// line 3: header comment",
    "// line 4: header comment",
    "// line 5: header comment",
    "// line 6: header comment",
    "// line 7: header comment",
    "// line 8: header comment",
    "// line 9: header comment",
    "// line 10: header comment");

  // Hand-authored fixture 2: one 4-level-nested block.
  // Line-by-line brace trace (1-indexed):
  //   line1 '{' -> depth 1 (max=1)
  //   line2 '{' -> depth 2 (max=2)
  //   line3 '{' -> depth 3 (max=3)
  //   line4 '{' -> depth 4 (max=4)
  //   line6 '}' closes line4's brace -> span = 6-4+1 = 3
  //   line7 '}' closes line3's brace -> span = 7-3+1 = 5
  //   line8 '}' closes line2's brace -> span = 8-2+1 = 7
  //   line9 '}' closes line1's brace -> span = 9-1+1 = 9 (largest)
  // Hand-computed: lineCount=9, longestMethodLineSpan=9, maxNestingDepth=4.
  private static final String FOUR_LEVEL_NESTED_SNIPPET = String.join("\n",
    "public class Foo {",
    "  public void bar() {",
    "    if (true) {",
    "      for (int i = 0; i < 1; i++) {",
    "        System.out.println(i);",
    "      }",
    "    }",
    "  }",
    "}");

  // Hand-authored fixture 3: one 40-line method (1 opening line + 38 body lines + 1 closing line).
  // Hand-computed: lineCount=40, longestMethodLineSpan=40-1+1=40, maxNestingDepth=1.
  private static final String FORTY_LINE_METHOD_SNIPPET = buildLongMethodSnippet();

  private static String buildLongMethodSnippet() {
    StringBuilder builder = new StringBuilder("public void longMethod() {\n");
    for (int i = 1; i <= 38; i++) {
      builder.append("  System.out.println(\"line ").append(i).append("\");\n");
    }
    builder.append("}");
    return builder.toString();
  }

  @Test
  void shouldComputeZeroSpanAndZeroNestingDepth_whenSnippetIsFlatTenLineFile() {
    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(FLAT_TEN_LINE_SNIPPET);

    assertThat(metrics.lineCount()).isEqualTo(10);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(0);
    assertThat(metrics.maxNestingDepth()).isEqualTo(0);
  }

  @Test
  void shouldComputeMaxNestingDepthOfFour_whenSnippetHasFourLevelNestedBlock() {
    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(FOUR_LEVEL_NESTED_SNIPPET);

    assertThat(metrics.lineCount()).isEqualTo(9);
    assertThat(metrics.maxNestingDepth()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(9);
  }

  @Test
  void shouldComputeLongestMethodSpanOfForty_whenSnippetHasFortyLineMethod() {
    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(FORTY_LINE_METHOD_SNIPPET);

    assertThat(metrics.lineCount()).isEqualTo(40);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(40);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldReturnAllZeroMetrics_whenSnippetIsEmptyString() {
    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze("");

    assertThat(metrics.lineCount()).isZero();
    assertThat(metrics.longestMethodLineSpan()).isZero();
    assertThat(metrics.maxNestingDepth()).isZero();
  }

  @Test
  void shouldThrowIllegalArgumentException_whenCodeIsNull() {
    assertThatThrownBy(() -> CodeMetricsAnalyzer.analyze(null))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldIgnoreUnmatchedClosingBrace_whenSnippetHasClosingBraceWithoutAnyOpener() {
    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze("}\nfoo();");

    assertThat(metrics.lineCount()).isEqualTo(2);
    assertThat(metrics.maxNestingDepth()).isZero();
    assertThat(metrics.longestMethodLineSpan()).isZero();
  }

  @Test
  void shouldNotCompleteASpanOrLowerMaxDepth_whenOpeningBraceIsNeverClosed() {
    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze("if (true) {\nfoo();");

    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
    assertThat(metrics.longestMethodLineSpan()).isZero();
  }

  // --- Medium 2 code-review fix: brace inside a string/char/comment/text-block literal must never be
  // counted as structural, and must never corrupt the LIFO brace stack for the rest of the snippet. ---

  // The reviewer's exact reproducer. Ground truth (hand-computed, verified by the reviewer against the
  // real production method before the fix): lineCount=6, longestMethodLineSpan=6, maxNestingDepth=1.
  // Before the lexer fix, the phantom '{' inside the string literal on line 2 was pushed onto the same
  // brace stack as the real method brace; the real closing '}' on line 6 then popped that phantom entry
  // instead (LIFO), reporting longestMethodLineSpan=5/maxNestingDepth=2 - both wrong.
  private static final String STRING_LITERAL_WITH_BRACE_SNIPPET = String.join("\n",
    "public void realMethod() {",
    "  String regexLike = \"{\";",
    "  doWork();",
    "  doMoreWork();",
    "  finalStep();",
    "}");

  @Test
  void shouldNotCountBraceInsideStringLiteralAsStructural_whenSnippetIsTheReviewersExactReproducer() {
    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(STRING_LITERAL_WITH_BRACE_SNIPPET);

    assertThat(metrics.lineCount()).isEqualTo(6);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(6);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldNotCountBraceInsideLineComment_whenSnippetHasAnOpenBraceMentionedInATrailingLineComment() {
    String snippet = String.join("\n",
      "public void foo() {",
      "  // note: uses { for something",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(4);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldNotCountBraceInsideBlockComment_whenSnippetHasABraceMentionedInsideASlashStarComment() {
    String snippet = String.join("\n",
      "public void foo() {",
      "  /* block comment with a brace { inside */",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(4);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldNotCountBracesInsideTextBlock_whenSnippetEmbedsBracesInsideATripleQuotedTextBlock() {
    String snippet = String.join("\n",
      "public void foo() {",
      "  String s = \"\"\"",
      "      { not a real brace }",
      "      \"\"\";",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(6);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(6);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldNotCountBraceInsideCharLiteral_whenSnippetContainsAnOpenBraceCharLiteral() {
    String snippet = String.join("\n",
      "public void foo() {",
      "  char c = '{';",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(4);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldTreatEscapedQuoteAsNotClosingTheStringLiteral_whenABraceImmediatelyFollowsAnEscapedQuote() {
    // Raw analyzed content of line 2 is exactly:   String s = "a\"{b";
    // The \" is an escaped quote that must NOT close the string literal, so the '{' right after it
    // remains inside the (still-open) string and must not be counted as structural.
    String snippet = String.join("\n",
      "public void foo() {",
      "  String s = \"a\\\"{b\";",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(4);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldNotLeakCorruptedDepthIntoALaterUnrelatedMethod_whenAnEarlierMethodContainsAStringLiteralBrace() {
    // Directly proves the reviewer's broader claim: a phantom brace inside a string literal must not
    // corrupt bookkeeping for the REST of the file, not just the line/method it appears on. Before the
    // fix, the phantom '{' on line 2 was popped by the real '}' on line 3 (LIFO), leaving line 1's real
    // brace "open" and leaking one extra level of depth into the second, otherwise-unrelated method -
    // inflating maxNestingDepth to 3 instead of the correct 2.
    String snippet = String.join("\n",
      "public void first() {",
      "  String regexLike = \"{\";",
      "}",
      "public void second() {",
      "  if (true) {",
      "    doWork();",
      "  }",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(8);
    assertThat(metrics.maxNestingDepth()).isEqualTo(2);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(5);
  }

  // --- Lexer edge-case coverage: multi-line comments and malformed/continued literals ------------

  @Test
  void shouldTrackLineNumbersAcrossAMultiLineBlockComment_whenCommentSpansSeveralLines() {
    String snippet = String.join("\n",
      "public void foo() {",
      "  /* this is a",
      "     multi-line comment with { and }",
      "     braces embedded */",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(6);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(6);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldRecoverToNormalStateWithoutCorruptingLaterBraces_whenStringLiteralIsNeverClosedBeforeANewline() {
    // Defensive recovery: this is not valid Java (an unescaped raw newline inside "..." is a compile
    // error), but this is a heuristic lexer over arbitrary/pasted snippets, not a compiler - a
    // malformed/truncated string must not leave the rest of the snippet stuck in STRING_LITERAL state.
    String snippet = String.join("\n",
      "public void foo() {",
      "  String s = \"unterminated",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(4);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldRecoverToNormalStateWithoutCorruptingLaterBraces_whenCharLiteralIsNeverClosedBeforeANewline() {
    String snippet = String.join("\n",
      "public void foo() {",
      "  char c = 'x",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(4);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldTreatBackslashNewlineAsAnEscapedContinuation_insideAStringLiteral() {
    // Raw analyzed content is: String s = "a\<newline>  b";  - the trailing backslash on line 2
    // escapes the following newline character itself, so the string literal remains open across the
    // line break rather than being closed/reset by it.
    String snippet = String.join("\n",
      "public void foo() {",
      "  String s = \"a\\",
      "  b\";",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(5);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(5);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldTreatBackslashNewlineAsAnEscapedContinuation_insideACharLiteral() {
    String snippet = String.join("\n",
      "public void foo() {",
      "  char c = '\\",
      "  x';",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(5);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(5);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldHandleBackslashNewlineLineContinuation_insideATextBlock() {
    // Unlike the string/char-literal cases above, a trailing backslash suppressing the following
    // newline is a real, documented Java 15+ text-block feature (not merely a defensive fallback).
    String snippet = String.join("\n",
      "public void foo() {",
      "  String s = \"\"\"",
      "      line one \\",
      "      line two { }",
      "      \"\"\";",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(7);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(7);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  // --- Re-review branch-coverage correction: 5 of the 6 branches JaCoCo reports as missed in
  // analyze()'s lexer are genuine untested paths (not the enum-switch synthetic default at line 83,
  // the only one that is). Each degrades safely (no corruption, crash, or hang) but was previously
  // untested. See context/PROGRESS.md for the corrected per-branch accounting. -----------------------

  @Test
  void shouldNotThrow_whenStringLiteralEndsWithATrailingBackslashAsTheLastCharacterOfTheEntireInput() {
    // Exercises the false side of STRING_LITERAL's `index + 1 < length` guard: a trailing, unescaped
    // backslash that is literally the last character of the whole input has no following character to
    // peek at. The guard exists specifically so this does not throw StringIndexOutOfBoundsException.
    String snippet = "String s = \"abc\\";

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(1);
    assertThat(metrics.longestMethodLineSpan()).isZero();
    assertThat(metrics.maxNestingDepth()).isZero();
  }

  @Test
  void shouldNotThrow_whenCharLiteralEndsWithATrailingBackslashAsTheLastCharacterOfTheEntireInput() {
    // Same guard as above, for CHAR_LITERAL's own `index + 1 < length` check - a separate branch in
    // the compiled bytecode from STRING_LITERAL's, even though the condition reads identically.
    String snippet = "char c = 'a\\";

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(1);
    assertThat(metrics.longestMethodLineSpan()).isZero();
    assertThat(metrics.maxNestingDepth()).isZero();
  }

  @Test
  void shouldNotThrow_whenTextBlockEndsWithATrailingBackslashAsTheLastCharacterOfTheEntireInput() {
    // Same guard as above, for TEXT_BLOCK's own `index + 1 < length` check.
    String snippet = "String s = \"\"\"\nabc\\";

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(2);
    assertThat(metrics.longestMethodLineSpan()).isZero();
    assertThat(metrics.maxNestingDepth()).isZero();
  }

  @Test
  void shouldTreatEscapedNonNewlineCharacterAsAnOrdinaryEscape_insideACharLiteral() {
    // CHAR_LITERAL's inner `code.charAt(index + 1) == '\n'` check already has its true side covered by
    // shouldTreatBackslashNewlineAsAnEscapedContinuation_insideACharLiteral above; this covers the
    // false side - a backslash escaping an ordinary (non-newline) character, here an escaped quote.
    String snippet = String.join("\n",
      "public void foo() {",
      "  char c = '\\'';",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(4);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(4);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }

  @Test
  void shouldTreatEscapedNonNewlineCharacterAsAnOrdinaryEscape_insideATextBlock() {
    // Same false-side coverage as above, for TEXT_BLOCK's own inner newline check: a backslash
    // escaping an ordinary (non-newline) character, here an escaped quote, inside a text block.
    String snippet = String.join("\n",
      "public void foo() {",
      "  String s = \"\"\"",
      "      line with an escaped quote \\\" here",
      "      \"\"\";",
      "  bar();",
      "}");

    CodeMetricsAnalyzer.CodeMetrics metrics = CodeMetricsAnalyzer.analyze(snippet);

    assertThat(metrics.lineCount()).isEqualTo(6);
    assertThat(metrics.longestMethodLineSpan()).isEqualTo(6);
    assertThat(metrics.maxNestingDepth()).isEqualTo(1);
  }
}
