package com.epam.docqachatbot.config;

import com.epam.docqachatbot.DocQaChatbotApplication;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration.RecordingEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
  classes = {DocQaChatbotApplication.class, HermeticRagTestConfiguration.class},
  properties = {"app.rag.retrieval.top-k=2", "app.rag.retrieval.similarity-threshold=0.8"})
class RetrievalPipelineIT {

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private DocumentRetriever documentRetriever;

  @Autowired
  private RecordingEmbeddingModel embeddingModel;

  @Test
  void shouldUseSameVectorStoreEmbeddingPathAndPreserveMetadata_whenRetrievingHits() {
    List<Document> candidates = java.util.stream.IntStream.rangeClosed(1, 3)
      .mapToObj(index -> Document.builder().id("secure-" + index)
        .text("secure")
        .metadata(Map.of("chunkId", "secure-" + index, "documentName", "policy.md"))
        .build())
      .toList();
    vectorStore.accept(candidates);
    int documentCalls = embeddingModel.documentEmbeddingCount();
    embeddingModel.resetCounts();

    List<Document> result = documentRetriever.retrieve(new Query("secure"));
    String dissimilarToken = List.of("alpha", "bravo", "charlie", "delta").stream()
      .filter(token -> Math.floorMod(token.hashCode(), 64)
        != Math.floorMod("secure".hashCode(), 64))
      .findFirst()
      .orElseThrow();
    List<Document> belowThreshold = documentRetriever.retrieve(new Query(dissimilarToken));

    assertThat(documentCalls).isEqualTo(3);
    assertThat(embeddingModel.queryEmbeddingCount()).isEqualTo(2);
    assertThat(result).hasSize(2);
    assertThat(result).allSatisfy(document -> {
      assertThat(document.getScore()).isGreaterThanOrEqualTo(0.8);
      assertThat(document.getMetadata())
        .containsKeys("chunkId", "documentName");
    });
    assertThat(result).extracting(document -> document.getMetadata().get("chunkId"))
      .allMatch(chunkId -> chunkId.toString().startsWith("secure-"));
    assertThat(belowThreshold).isEmpty();
  }
}
