package com.epam.docqachatbot.service;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.epam.docqachatbot.config.DocumentsProperties;
import com.epam.docqachatbot.ingestion.DocumentIngestionException;
import com.epam.docqachatbot.ingestion.DocumentReaderFactory;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import com.epam.docqachatbot.ingestion.IngestionSummary;
import com.epam.docqachatbot.ingestion.ProvenanceDocumentTransformer;
import com.epam.docqachatbot.ingestion.SafeDocumentResourceLoader;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Genuine end-to-end proof of {@link DocumentIngestionService#replaceDocument(String, Map)}
 * against a real, unmodified {@link SimpleVectorStore}, mirroring {@link
 * DocumentDeletionRealStoreTest}'s dependency graph (real {@link SafeDocumentResourceLoader},
 * {@link DocumentReaderFactory}, {@link ProvenanceDocumentTransformer}, and {@link
 * HermeticRagTestConfiguration.RecordingEmbeddingModel}) without booting a Spring context. Every
 * assertion here exercises the exact resolve -&gt; ingest -&gt; delete-only-what-became-stale
 * mechanism shipped to production; there is no test-only decorator.
 */
class DocumentReplacementRealStoreTest {

  @TempDir
  Path temporaryDirectory;

  private DocumentProcessingProperties processingProperties;
  private VectorStore vectorStore;
  private DocumentIngestionService service;

  @BeforeEach
  void setUp() {
    processingProperties = new DocumentProcessingProperties();
    processingProperties.setAllowedFileRoots(List.of(temporaryDirectory.toString()));
    HermeticRagTestConfiguration.RecordingEmbeddingModel embeddingModel =
      new HermeticRagTestConfiguration.RecordingEmbeddingModel();
    vectorStore = SimpleVectorStore.builder(embeddingModel).build();
    SafeDocumentResourceLoader resourceLoader =
      new SafeDocumentResourceLoader(new DefaultResourceLoader(), processingProperties);
    service = new DocumentIngestionService(
      vectorStore,
      processingProperties,
      new DocumentsProperties(),
      resourceLoader,
      new DocumentReaderFactory(),
      new ProvenanceDocumentTransformer(processingProperties));
  }

  @Test
  void shouldContainOnlyNewVersionChunks_whenReplacingWithEditedContent() {
    // Arrange
    ingestClasspath("classpath:documents/delete-workflow-v1/secure-notes.md");
    Set<String> versionOneIds = idsOf(searchByDocumentName("secure-notes.md"));
    assertThat(versionOneIds).isNotEmpty();

    // Act
    service.replaceDocument("classpath:documents/delete-workflow-v2/secure-notes.md", Map.of());

    // Assert: final state has only the new version's chunks -- no stale id, no duplicate.
    List<Document> finalChunks = searchByDocumentName("secure-notes.md");
    Set<String> finalIds = idsOf(finalChunks);
    assertThat(finalIds).doesNotContainAnyElementsOf(versionOneIds);
    assertThat(finalIds).hasSameSizeAs(finalChunks);
    assertThat(finalChunks).hasSize(1);
  }

  @Test
  void shouldSucceedAsPlainCreate_whenDocumentNameIsNotCurrentlyIndexed() {
    // Arrange
    assertThat(searchByDocumentName("secure-notes.md")).isEmpty();

    // Act
    service.replaceDocument("classpath:documents/delete-workflow-v1/secure-notes.md", Map.of());

    // Assert: create-or-replace, not a rejection.
    assertThat(searchByDocumentName("secure-notes.md")).isNotEmpty();
  }

  @Test
  void shouldLeavePriorVersionCompletelyUnchanged_whenReplaceIngestFails() {
    // Arrange
    ingestClasspath("classpath:documents/delete-workflow-v1/secure-notes.md");
    List<Document> before = searchByDocumentName("secure-notes.md");
    Set<String> idsBefore = idsOf(before);
    assertThat(idsBefore).isNotEmpty();

    // Act / Assert: the replacement resource shares the same documentName ("secure-notes.md")
    // but its content is whitespace-only, so ingestSingleResource throws EMPTY_DOCUMENT after
    // resolve has already found the prior version's stale ids -- no delete call is ever reached.
    assertThatExceptionOfType(DocumentIngestionException.class)
      .isThrownBy(() -> service.replaceDocument(
        "classpath:documents/delete-workflow-empty/secure-notes.md", Map.of()))
      .satisfies(exception -> assertThat(exception.code()).isEqualTo("EMPTY_DOCUMENT"));

    // Assert: same ids, same count -- the prior version is completely untouched.
    List<Document> after = searchByDocumentName("secure-notes.md");
    assertThat(idsOf(after)).isEqualTo(idsBefore);
    assertThat(after).hasSameSizeAs(before);
  }

  @Test
  void shouldNotEmptyDocument_whenReplaceContentIsByteIdentical() {
    // Arrange
    ingestClasspath("classpath:documents/delete-workflow-v1/secure-notes.md");
    Set<String> idsBefore = idsOf(searchByDocumentName("secure-notes.md"));
    assertThat(idsBefore).isNotEmpty();

    // Act: the sharpest correctness risk this increment exists to guard against -- a
    // byte-identical re-put makes the resolved "stale" id set and the newly-written "new" id
    // set fully overlap (chunkId is content-fingerprint-derived).
    service.replaceDocument("classpath:documents/delete-workflow-v1/secure-notes.md", Map.of());

    // Assert: the document is not emptied; the exact same chunk set survives.
    List<Document> after = searchByDocumentName("secure-notes.md");
    assertThat(idsOf(after)).isEqualTo(idsBefore);
    assertThat(after).isNotEmpty();
  }

  @Test
  void shouldLeaveOtherDocumentsUntouched_whenReplacingOneDocument() {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");
    ingestClasspath("classpath:documents/delete-workflow-v1/secure-notes.md");
    Set<String> survivorIds = idsOf(searchByDocumentName("doc-a.txt"));
    assertThat(survivorIds).isNotEmpty();

    // Act
    service.replaceDocument("classpath:documents/delete-workflow-v2/secure-notes.md", Map.of());

    // Assert: the unrelated document is completely unaffected.
    assertThat(idsOf(searchByDocumentName("doc-a.txt"))).isEqualTo(survivorIds);
  }

  @Test
  void shouldThrowInvalidReplaceSelector_whenDerivedDocumentNameExceedsConfiguredLength() {
    // Arrange
    processingProperties.setMaxDocumentNameLength(5);

    // Act / Assert
    assertThatThrownBy(() -> service.replaceDocument(
      "classpath:documents/delete-workflow-v1/secure-notes.md", Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_REPLACE_SELECTOR");
    assertThat(searchByDocumentName("secure-notes.md")).isEmpty();
  }

  @Test
  void shouldTreatAsSameDocumentGroup_whenTwoDifferentLocationsShareTheSameSanitizedBasename() {
    // Arrange: two different resource locations sanitize to the identical documentName
    // ("doc-a.txt"). Replace's cleanup is scoped by documentName -- the same scope
    // deleteByDocumentName already uses -- so that a same-name replace staged at a different
    // location (a re-uploaded new version) is recognised as the previous version and cleaned up.
    // The accepted consequence, matching deleteByDocumentName's own documented limitation, is that
    // two genuinely unrelated documents which happen to collide on sanitized basename share this
    // same cleanup scope: replacing one also removes the other's chunks.
    String firstLocation = ingestFileAt(
      Path.of("first", "doc-a.txt"), "First document about network segmentation policy alpha.");
    ingestFileAt(
      Path.of("second", "doc-a.txt"), "Second document about access control policy beta.");
    assertThat(searchByDocumentName("doc-a.txt")).hasSize(2);

    // Act
    service.replaceDocument(firstLocation, Map.of());

    // Assert: only the newly-ingested version's single chunk remains under the shared name.
    assertThat(searchByDocumentName("doc-a.txt")).hasSize(1);
  }

  private String ingestFileAt(Path relativePath, String content) {
    Path resolved = temporaryDirectory.resolve(relativePath);
    Path written;
    try {
      Files.createDirectories(resolved.getParent());
      written = Files.writeString(resolved, content);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
    String location = written.toUri().toString();
    IngestionSummary summary = service.ingestResources(List.of(location), Map.of());
    assertThat(summary.resourcesFailed()).isZero();
    return location;
  }

  private void ingestClasspath(String location) {
    IngestionSummary summary = service.ingestResources(List.of(location), Map.of());
    assertThat(summary.resourcesFailed()).isZero();
  }

  private void ingestFile(String fileName, String content) {
    Path written;
    try {
      written = Files.writeString(temporaryDirectory.resolve(fileName), content);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
    IngestionSummary summary = service.ingestResources(
      List.of(written.toUri().toString()), Map.of());
    assertThat(summary.resourcesFailed()).isZero();
  }

  private List<Document> searchByDocumentName(String documentName) {
    Filter.Expression filterExpression = new FilterExpressionBuilder()
      .eq(IngestionMetadata.DOCUMENT_NAME, documentName)
      .build();
    return vectorStore.similaritySearch(SearchRequest.builder()
      .query(documentName)
      .topK(1000)
      .similarityThreshold(0.0)
      .filterExpression(filterExpression)
      .build());
  }

  private Set<String> idsOf(List<Document> documents) {
    return documents.stream().map(Document::getId).collect(Collectors.toSet());
  }
}
