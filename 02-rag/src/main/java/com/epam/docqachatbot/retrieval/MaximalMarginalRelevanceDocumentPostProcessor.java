package com.epam.docqachatbot.retrieval;

import com.epam.docqachatbot.ingestion.IngestionMetadata;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, model-free re-ranker that narrows an already-retrieved candidate pool down to a
 * smaller, more thematically diverse set.
 *
 * <p><b>This is heading-diversity reranking in the spirit of Maximal Marginal Relevance (MMR),
 * not textbook vector-space MMR.</b> Classic MMR penalizes a candidate for its pairwise vector
 * similarity to already-selected candidates, which requires every candidate's embedding vector to
 * be available at rerank time. A {@link Document} returned by {@code similaritySearch}/{@code
 * VectorStoreDocumentRetriever} carries no embedding (confirmed by decompiling the resolved
 * {@code spring-ai-commons:1.1.2} {@code Document} class: it exposes only {@code getId()}/{@code
 * getText()}/{@code getMetadata()}/{@code getScore()}, no vector accessor), so true vector MMR
 * would require re-embedding every candidate per request — a real, per-request cost this
 * mechanism avoids entirely.
 *
 * <p>The relevance term reuses the candidate's already-populated {@link Document#getScore()}
 * (zero new cost, already load-bearing for the existing {@code similarityThreshold} mechanism).
 * The diversity term is a <b>theme-key</b> match penalty: the candidate's {@link
 * IngestionMetadata#HEADING_PATH} metadata value is split on {@code " > "} and its <em>second</em>
 * segment (falling back to the sole segment when only one exists) is compared against every
 * already-selected candidate's theme key. This corrects an earlier version of this mechanism that
 * penalized only an <em>exact</em> match on the full heading path, which was proven a mechanical
 * no-op against the real corpus this targets: {@code
 * DocumentIngestionIT#shouldIngestRealEpamPolicyWithStableSearchableProvenance_whenReingested}
 * proves the real, ingested {@code headingPath} format is three segments (e.g. {@code "2 SECURE
 * CODING GUIDELINES > 2.1 CORE > 2.1.6 Don't Store Secrets"}), and the real corpus has exactly two
 * level-1 headings — one of which (the document body) parents every one of the six level-2 theme
 * headings the {@code FiveBulletSummary} rubric actually requires distinctness over. Five chunks
 * with five distinct exact heading paths can still share one theme (the same level-2 segment), so
 * exact-match never fired; the second-segment theme key does. <b>Scope/limitation:</b> this rule
 * is verified against this specific document's two-level top structure (level 1 = document-wide
 * sections, level 2 = themes, level 3 = leaf policies); a differently-shaped corpus — a flatter or
 * deeper heading hierarchy — would need this rule re-examined, not assumed to still hold.
 *
 * <p>Selection is a greedy, deterministic process: on each pass, every not-yet-selected candidate
 * is scored as {@code mmrScore(d) = lambda * score(d) - (1 - lambda) * diversityPenalty(d,
 * selected)}, scanning candidates in their original (input) order; the running best is replaced
 * only on a <em>strictly</em> greater score, so the first-encountered maximal candidate always
 * wins a tie — no separate tie-break comparator is needed. Selected documents are returned in
 * selection order.
 *
 * <p>A candidate with a {@code null} {@link Document#getScore()} is treated as {@code 0.0}. A
 * candidate with no {@link IngestionMetadata#HEADING_PATH} metadata value has a {@code null}
 * theme key: it never triggers a diversity penalty against any other candidate (including another
 * candidate that is also missing the metadata), since the absence of the metadata means it is
 * genuinely unknown whether they share a theme — not that they do.
 */
public class MaximalMarginalRelevanceDocumentPostProcessor implements DocumentPostProcessor {

  private final int finalTopK;
  private final double lambda;

  public MaximalMarginalRelevanceDocumentPostProcessor(int finalTopK, double lambda) {
    this.finalTopK = finalTopK;
    this.lambda = lambda;
  }

  @Override
  public List<Document> process(Query query, List<Document> documents) {
    if (documents.isEmpty()) {
      return documents;
    }

    int limit = Math.min(finalTopK, documents.size());
    boolean[] selected = new boolean[documents.size()];
    List<String> selectedThemeKeys = new ArrayList<>(limit);
    List<Document> result = new ArrayList<>(limit);

    for (int pass = 0; pass < limit; pass++) {
      int bestIndex = -1;
      double bestScore = Double.NEGATIVE_INFINITY;
      for (int index = 0; index < documents.size(); index++) {
        if (selected[index]) {
          continue;
        }
        Document candidate = documents.get(index);
        double mmrScore = mmrScore(candidate, selectedThemeKeys);
        if (mmrScore > bestScore) {
          bestScore = mmrScore;
          bestIndex = index;
        }
      }
      selected[bestIndex] = true;
      Document best = documents.get(bestIndex);
      result.add(best);
      selectedThemeKeys.add(themeKey(best));
    }
    return result;
  }

  private double mmrScore(Document candidate, List<String> selectedThemeKeys) {
    double relevance = score(candidate);
    double diversityPenalty = diversityPenalty(candidate, selectedThemeKeys);
    return lambda * relevance - (1 - lambda) * diversityPenalty;
  }

  private double diversityPenalty(Document candidate, List<String> selectedThemeKeys) {
    String candidateThemeKey = themeKey(candidate);
    return candidateThemeKey != null && selectedThemeKeys.contains(candidateThemeKey)
      ? 1.0 : 0.0;
  }

  private static double score(Document document) {
    Double score = document.getScore();
    return score == null ? 0.0 : score;
  }

  private static String headingPath(Document document) {
    Object value = document.getMetadata().get(IngestionMetadata.HEADING_PATH);
    return value == null ? null : value.toString();
  }

  /**
   * Derives the diversity key from a candidate's {@link IngestionMetadata#HEADING_PATH}: the
   * second {@code " > "}-delimited segment (the theme level for this corpus), falling back to the
   * sole segment when only one exists. A {@code null} heading path yields a {@code null} theme
   * key, propagated unchanged.
   */
  private static String themeKey(Document document) {
    String path = headingPath(document);
    if (path == null) {
      return null;
    }
    String[] segments = path.split(" > ");
    return segments.length >= 2 ? segments[1] : segments[0];
  }
}
