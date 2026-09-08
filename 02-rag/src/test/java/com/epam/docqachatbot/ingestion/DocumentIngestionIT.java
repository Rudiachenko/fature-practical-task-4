package com.epam.docqachatbot.ingestion;

import com.epam.docqachatbot.DocQaChatbotApplication;
import com.epam.docqachatbot.service.DocumentIngestionService;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration.RecordingEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
  classes = {DocQaChatbotApplication.class, HermeticRagTestConfiguration.class},
  webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentIngestionIT {

  @Autowired
  private DocumentIngestionService ingestionService;

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private RecordingEmbeddingModel embeddingModel;

  @Test
  void shouldIngestRealEpamPolicyWithStableSearchableProvenance_whenReingested() {
    // Arrange
    Path policy = Path.of("EPAM_JavaSecureCodingGD.md").toAbsolutePath().normalize();
    String location = policy.toUri().toString();
    embeddingModel.resetCounts();

    // Act
    IngestionSummary first = ingestionService.ingestResources(
      List.of(location), Map.of("sourceType", "epam-policy"));
    List<Document> firstMatches = search(
      "cryptographic keys passwords hostile JVM secrets", 5);
    List<String> firstIds = firstMatches.stream().map(Document::getId).toList();
    IngestionSummary second = ingestionService.ingestResources(
      List.of(location), Map.of("sourceType", "epam-policy"));
    List<Document> secondMatches = search(
      "cryptographic keys passwords hostile JVM secrets", 5);

    // Assert
    assertThat(first.resourcesProcessed()).isEqualTo(1);
    assertThat(first.resourcesFailed()).isZero();
    assertThat(first.chunksWritten()).isPositive().isEqualTo(second.chunksWritten());
    assertThat(embeddingModel.documentEmbeddingCount())
      .isEqualTo(first.chunksWritten() + second.chunksWritten());
    assertThat(embeddingModel.queryEmbeddingCount()).isGreaterThanOrEqualTo(2);
    assertThat(firstMatches).isNotEmpty();
    assertThat(firstMatches).anySatisfy(document -> {
      assertThat(document.getText())
        .contains("Don't Store Secrets", "cryptographic keys", "passwords");
      assertThat(document.getMetadata())
        .containsEntry(IngestionMetadata.DOCUMENT_NAME, "EPAM_JavaSecureCodingGD.md")
        .containsEntry(IngestionMetadata.SOURCE, location)
        .containsEntry(IngestionMetadata.DOCUMENT_TYPE, "markdown")
        .containsEntry(IngestionMetadata.HEADING_PATH,
          "2 SECURE CODING GUIDELINES > 2.1 CORE > 2.1.6 Don't Store Secrets")
        .containsEntry(IngestionMetadata.EMBEDDING_MODEL, "deterministic-test-embedding");
      assertThat(document.getMetadata().get(IngestionMetadata.CHUNK_ID))
        .isEqualTo(document.getId());
    });
    assertThat(secondMatches).extracting(Document::getId).containsExactlyElementsOf(firstIds);
  }

  @Test
  void shouldIngestRepresentativeTextAndPdf_whenSupportedReadersAreSelected() {
    // Act
    IngestionSummary summary = ingestionService.ingestResources(List.of(
      "classpath:documents/ingestion-sample.txt",
      "classpath:documents/llm_context_document.pdf"
    ), Map.of("sourceType", "reader-fixture"));

    // Assert
    assertThat(summary.resourcesProcessed()).isEqualTo(2);
    assertThat(summary.resourcesFailed()).isZero();
    assertThat(summary.chunksWritten()).isGreaterThanOrEqualTo(2);
    assertThat(search("Validate untrusted input security sensitive operations", 3))
      .anySatisfy(document -> assertThat(document.getMetadata())
        .containsEntry(IngestionMetadata.DOCUMENT_NAME, "ingestion-sample.txt")
        .containsEntry(IngestionMetadata.DOCUMENT_TYPE, "text"));
  }

  private List<Document> search(String query, int topK) {
    return vectorStore.similaritySearch(SearchRequest.builder()
      .query(query)
      .topK(topK)
      .similarityThreshold(0.0)
      .build());
  }
}
