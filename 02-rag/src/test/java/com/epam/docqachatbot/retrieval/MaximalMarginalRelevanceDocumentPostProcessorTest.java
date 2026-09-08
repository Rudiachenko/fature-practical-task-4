package com.epam.docqachatbot.retrieval;

import com.epam.docqachatbot.ingestion.IngestionMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MaximalMarginalRelevanceDocumentPostProcessorTest {

  private static final Query QUERY = new Query("2.1.6 Don't Store Secrets");

  /** The shipped {@code app.rag.retrieval} narrowing, mirrored from {@code application.yml}. */
  private static final int SHIPPED_FINAL_TOP_K = 5;
  private static final double SHIPPED_LAMBDA = 0.5;

  private static Document document(String id, double score, String headingPath) {
    return Document.builder()
      .id(id)
      .text(id)
      .score(score)
      .metadata(Map.of(IngestionMetadata.HEADING_PATH, headingPath))
      .build();
  }

  @Test
  void shouldSelectExactWorkedExampleOrder_whenCandidatesSpanThreeThemes() {
    // Arrange: the literal d1..d6 worked example from context/PLAN.md's Architecture Notes,
    // corrected to the real 3-segment headingPath format proven by
    // DocumentIngestionIT#shouldIngestRealEpamPolicyWithStableSearchableProvenance_whenReingested.
    Document d1 = document("d1", 0.90,
      "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.6 Don't Store Secrets");
    Document d2 = document("d2", 0.85,
      "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.6 Don't Store Secrets");
    Document d3 = document("d3", 0.80,
      "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.6 Don't Store Secrets");
    Document d4 = document("d4", 0.75,
      "2 SECURE CODING GUIDELINES > 2.2 OBJECTS > 2.2.4 Make Everything Final");
    Document d5 = document("d5", 0.70,
      "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.3 Restrict Privileges");
    Document d6 = document("d6", 0.65,
      "2 SECURE CODING GUIDELINES > 2.3 INPUT AND OUTPUT > 2.3.1 Validate Inputs");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(3, 0.5);

    // Act
    List<Document> selected = processor.process(QUERY, List.of(d1, d2, d3, d4, d5, d6));

    // Assert: pass 1 picks d1 (0.90, no penalty yet; theme selected = {CORE}). Pass 2, Selected=
    // {d1}: d2/d3/d5 are theme-penalized (same theme, CORE, as d1) to negative scores; d4 scores
    // 0.375 (theme OBJECTS, unpenalized) and wins over d6's 0.325 (theme INPUT AND OUTPUT, also
    // unpenalized but lower raw score) -> picks d4 (themes selected = {CORE, OBJECTS}). Pass 3,
    // Selected={d1,d4}: d2/d3/d5 remain CORE-penalized; d6 (0.325, theme INPUT AND OUTPUT) is now
    // the only positive-scoring candidate -> picks d6. Final: [d1, d4, d6] - three distinct
    // *themes* (CORE, OBJECTS, INPUT AND OUTPUT). Under the old exact-headingPath-match rule, d5
    // (a different leaf heading under the same CORE theme as d1) was never penalized and beat d6,
    // so the old selection {d1, d4, d5} covered only 2 distinct themes - exactly the
    // granularity-mismatch bug this theme-key correction fixes.
    assertThat(selected).containsExactly(d1, d4, d6);
  }

  @Test
  void shouldTreatBareSectionHeaderAndItsLeafChildAsSameTheme_whenHeadingPathsShareSecondSegment() {
    // Arrange: candidate A is a bare 2-segment section header, candidate B is a 3-segment leaf
    // child of the same theme (CORE) - both must resolve to the same theme key despite differing
    // segment counts, per the cross-segment-count equivalence this fix depends on.
    Document a = document("a", 0.90, "2 SECURE CODING GUIDELINES > 2.1 CORE");
    Document b = document("b", 0.85,
      "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.6 Don't Store Secrets");
    Document c = document("c", 0.80,
      "2 SECURE CODING GUIDELINES > 2.2 OBJECTS > 2.2.4 Make Everything Final");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(2, 0.5);

    // Act: pass 1 picks a (0.90). Pass 2, Selected={a}: b is theme-penalized (same theme, CORE,
    // as a) to 0.5*0.85-0.5=-0.075, while c is unpenalized at 0.5*0.80=0.40 and wins.
    List<Document> selected = processor.process(QUERY, List.of(a, b, c));

    // Assert: b is excluded despite a different segment count than a, because both resolve to
    // theme "2.1 CORE".
    assertThat(selected).containsExactly(a, c);
  }

  @Test
  void shouldTreatSingleSegmentHeadingPathAsItsOwnTheme_whenHeadingPathHasNoSecondSegment() {
    // Arrange: two real single-segment (level-1-only) heading paths, each with no second segment.
    Document introduction = document("introduction", 0.90, "1 INTRODUCTION");
    Document secureCoding = document("secure-coding", 0.85, "2 SECURE CODING GUIDELINES");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(2, 0.5);

    // Act
    List<Document> selected =
      processor.process(QUERY, List.of(introduction, secureCoding));

    // Assert: the fallback path (theme key = the sole segment) does not throw and does not
    // wrongly collapse the two distinct single-segment paths together.
    assertThat(selected).containsExactly(introduction, secureCoding);
  }

  @Test
  void shouldReturnSameEmptyList_whenCandidatesAreEmpty() {
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(3, 0.5);

    List<Document> selected = processor.process(QUERY, List.of());

    assertThat(selected).isEmpty();
  }

  @Test
  void shouldEqualPlainTopKByScore_whenAllCandidatesShareOneHeadingPath() {
    Document a = document("a", 0.90, "same heading");
    Document b = document("b", 0.85, "same heading");
    Document c = document("c", 0.80, "same heading");
    Document d = document("d", 0.75, "same heading");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(3, 0.5);

    List<Document> selected = processor.process(QUERY, List.of(a, b, c, d));

    assertThat(selected).containsExactly(a, b, c);
  }

  @Test
  void shouldEqualPlainTopKByScore_whenAllCandidatesHaveDistinctHeadingPaths() {
    Document a = document("a", 0.90, "heading a");
    Document b = document("b", 0.85, "heading b");
    Document c = document("c", 0.80, "heading c");
    Document d = document("d", 0.75, "heading d");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(3, 0.5);

    List<Document> selected = processor.process(QUERY, List.of(a, b, c, d));

    assertThat(selected).containsExactly(a, b, c);
  }

  @Test
  void shouldReturnAllCandidates_whenFewerCandidatesThanFinalTopK() {
    Document a = document("a", 0.90, "heading a");
    Document b = document("b", 0.85, "heading b");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(5, 0.5);

    List<Document> selected = processor.process(QUERY, List.of(a, b));

    assertThat(selected).containsExactly(a, b);
  }

  @Test
  void shouldTreatNullScoreAsZero_whenCandidateScoreIsMissing() {
    Document nullScored = Document.builder()
      .id("null-scored")
      .text("null-scored")
      .metadata(Map.of(IngestionMetadata.HEADING_PATH, "heading a"))
      .build();
    Document scored = document("scored", 0.10, "heading b");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(2, 0.5);

    List<Document> selected = processor.process(QUERY, List.of(nullScored, scored));

    assertThat(nullScored.getScore()).isNull();
    assertThat(selected).containsExactly(scored, nullScored);
  }

  @Test
  void shouldNeverPenalizeEachOther_whenMultipleCandidatesAreMissingHeadingPath() {
    Document first = Document.builder().id("first").text("first").score(0.5).build();
    Document second = Document.builder().id("second").text("second").score(0.4).build();
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(2, 0.5);

    List<Document> selected = processor.process(QUERY, List.of(first, second));

    assertThat(selected).containsExactly(first, second);
  }

  @Test
  void shouldSelectFirstEncounteredCandidate_whenMmrScoresAreExactlyTied() {
    Document first = document("first", 0.5, "heading a");
    Document second = document("second", 0.5, "heading a");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(1, 0.5);

    List<Document> selected = processor.process(QUERY, List.of(first, second));

    assertThat(selected).containsExactly(first);
  }

  @Test
  void shouldSelectFirstEncounteredCandidate_whenMmrScoresTieAfterDiversityPenalty() {
    Document selectedFirst = document("selected-first", 0.9, "heading a");
    Document tiedA = document("tied-a", 0.6, "heading a");
    Document tiedB = document("tied-b", 0.6, "heading a");
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(2, 0.5);

    List<Document> selected =
      processor.process(QUERY, List.of(selectedFirst, tiedA, tiedB));

    assertThat(selected).containsExactly(selectedFirst, tiedA);
  }

  @Test
  void shouldSpanFiveDistinctThemes_whenShippedDefaultsNarrowASectionSkewedPool() {
    // Arrange: a candidate pool shaped like the real top-20 for a whole-document question - the
    // highest-scoring chunks all sit in one theme (2.1 CORE) and the other themes trail behind.
    // A plain top-5 by score would return five 2.1 CORE chunks and no other theme, which is the
    // single-theme collapse that makes a five-distinct-topic summary impossible to ground.
    List<Document> pool = List.of(
      document("core-1", 0.94, "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.1 Obvious Flaws"),
      document("core-2", 0.93, "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.2 Design APIs"),
      document("core-3", 0.92, "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.5 Document Security"),
      document("core-4", 0.91, "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.6 Don't Store Secrets"),
      document("core-5", 0.90, "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.3 Restrict Privileges"),
      document("objects-1", 0.70, "2 SECURE CODING GUIDELINES > 2.2 OBJECTS > 2.2.4 Make Final"),
      document("io-1", 0.65, "2 SECURE CODING GUIDELINES > 2.3 INPUT AND OUTPUT > 2.3.1 Validate"),
      document("access-1", 0.60,
        "2 SECURE CODING GUIDELINES > 2.4 ACCESSIBILITY > 2.4.1 Limit Accessibility"),
      document("serial-1", 0.55,
        "2 SECURE CODING GUIDELINES > 2.5 SERIALIZATION > 2.5.2 Guard Sensitive Data"));
    MaximalMarginalRelevanceDocumentPostProcessor processor =
      new MaximalMarginalRelevanceDocumentPostProcessor(SHIPPED_FINAL_TOP_K, SHIPPED_LAMBDA);

    // Act
    List<Document> selected = processor.process(QUERY, pool);

    // Assert: the shipped narrowing still returns exactly the number of chunks a request cites,
    // and every one of them contributes a different theme, so a five-distinct-topic answer is
    // groundable without reaching for document metadata.
    assertThat(selected).hasSize(SHIPPED_FINAL_TOP_K);
    assertThat(selected).extracting(document -> document.getMetadata()
        .get(IngestionMetadata.HEADING_PATH).toString().split(" > ")[1])
      .doesNotHaveDuplicates()
      .hasSize(SHIPPED_FINAL_TOP_K);

    // Assert: the highest-scoring candidate is still selected first - diversity narrows the tail,
    // it does not override relevance.
    assertThat(selected.get(0).getId()).isEqualTo("core-1");
  }
}
