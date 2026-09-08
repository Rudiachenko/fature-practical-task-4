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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Genuine end-to-end proof of {@link DocumentIngestionService#deleteDocuments(List, String)}
 * against a real, unmodified {@link SimpleVectorStore}, mirroring {@code DocumentIngestionIT}'s
 * dependency graph (real {@link SafeDocumentResourceLoader}, {@link DocumentReaderFactory},
 * {@link ProvenanceDocumentTransformer}, and {@link HermeticRagTestConfiguration
 * .RecordingEmbeddingModel}) without booting a Spring context. Every assertion here exercises the
 * exact {@code similaritySearch(filterExpression)} + {@code delete(List)} mechanism shipped to
 * production; there is no test-only decorator.
 */
class DocumentDeletionRealStoreTest {

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
  void shouldDeleteOnlySpecifiedIds_andLeaveSurvivorsSearchable() throws IOException {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");
    ingestFile("doc-b.txt", "Doc b password hashing algorithm choices for bravo review.");
    String idToDelete = onlyId(searchByDocumentName("doc-a.txt"));
    String survivorId = onlyId(searchByDocumentName("doc-b.txt"));

    // Act
    service.deleteDocuments(List.of(idToDelete), null);

    // Assert
    assertThat(searchByDocumentName("doc-a.txt")).isEmpty();
    assertThat(searchByDocumentName("doc-b.txt"))
      .extracting(Document::getId)
      .containsExactly(survivorId);
  }

  @Test
  void shouldRemoveAllChunksOfDocument_andLeaveOtherDocumentIntact_whenDeletingByDocumentName()
    throws IOException {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");
    ingestFile("doc-b.txt", "Doc b password hashing algorithm choices for bravo review.");
    assertThat(searchByDocumentName("doc-a.txt")).isNotEmpty();
    assertThat(searchByDocumentName("doc-b.txt")).isNotEmpty();

    // Act
    service.deleteDocuments(null, "doc-a.txt");

    // Assert
    assertThat(searchByDocumentName("doc-a.txt")).isEmpty();
    assertThat(searchByDocumentName("doc-b.txt")).isNotEmpty();
  }

  @Test
  void shouldResolveAllChunks_whenDocumentNameIsTopicallyUnrelatedToOwnContent()
    throws IOException {
    // Arrange: content shares no theme with its own filename/documentName.
    ingestFile("zzz-unrelated-marker-file.txt",
      "The quick brown fox jumps over the lazy dog repeatedly for demonstration purposes only.");
    assertThat(searchByDocumentName("zzz-unrelated-marker-file.txt")).isNotEmpty();

    // Act
    service.deleteDocuments(null, "zzz-unrelated-marker-file.txt");

    // Assert
    assertThat(searchByDocumentName("zzz-unrelated-marker-file.txt")).isEmpty();
  }

  @Test
  void shouldRemoveBothContentVersions_whenSameDocumentNameHasTwoContentVersions() {
    // Arrange
    IngestionSummary first = service.ingestResources(
      List.of("classpath:documents/delete-workflow-v1/secure-notes.md"), Map.of());
    IngestionSummary second = service.ingestResources(
      List.of("classpath:documents/delete-workflow-v2/secure-notes.md"), Map.of());
    assertThat(first.resourcesFailed()).isZero();
    assertThat(second.resourcesFailed()).isZero();
    assertThat(searchByDocumentName("secure-notes.md")).hasSize(2);

    // Act
    service.deleteDocuments(null, "secure-notes.md");

    // Assert
    assertThat(searchByDocumentName("secure-notes.md")).isEmpty();
  }

  @Test
  void shouldLeaveOnlyCurrentVersionChunks_whenReingestWorkflowIsFollowed() {
    // Arrange
    service.ingestResources(
      List.of("classpath:documents/delete-workflow-v1/secure-notes.md"), Map.of());
    Set<String> versionOneIds = idsOf(searchByDocumentName("secure-notes.md"));
    assertThat(versionOneIds).isNotEmpty();

    // Act: documented two-call re-ingest workflow
    service.deleteDocuments(null, "secure-notes.md");
    service.ingestResources(
      List.of("classpath:documents/delete-workflow-v2/secure-notes.md"), Map.of());

    // Assert: final state has only the new version, no stale chunk, no duplicate
    List<Document> finalChunks = searchByDocumentName("secure-notes.md");
    Set<String> finalIds = idsOf(finalChunks);
    assertThat(finalIds).doesNotContainAnyElementsOf(versionOneIds);
    assertThat(finalIds).hasSameSizeAs(finalChunks);
  }

  @Test
  void shouldBeIdempotent_whenDeletingNonMatchingDocumentNameSelector() throws IOException {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");

    // Act
    service.deleteDocuments(null, "never-ingested-document.txt");

    // Assert
    assertThat(searchByDocumentName("doc-a.txt")).isNotEmpty();
  }

  @Test
  void shouldBeIdempotent_whenDeletingNonMatchingIdsSelector() throws IOException {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");

    // Act
    service.deleteDocuments(List.of("never-existed-chunk-id"), null);

    // Assert
    assertThat(searchByDocumentName("doc-a.txt")).isNotEmpty();
  }

  @Test
  void shouldBeIdempotent_whenRepeatingSuccessfulDocumentNameDelete() throws IOException {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");
    service.deleteDocuments(null, "doc-a.txt");
    assertThat(searchByDocumentName("doc-a.txt")).isEmpty();

    // Act / Assert
    assertThatCode(() -> service.deleteDocuments(null, "doc-a.txt")).doesNotThrowAnyException();
    assertThat(searchByDocumentName("doc-a.txt")).isEmpty();
  }

  @Test
  void shouldBeIdempotent_whenRepeatingSuccessfulIdsDelete() throws IOException {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");
    String id = onlyId(searchByDocumentName("doc-a.txt"));
    service.deleteDocuments(List.of(id), null);
    assertThat(searchByDocumentName("doc-a.txt")).isEmpty();

    // Act / Assert
    assertThatCode(() -> service.deleteDocuments(List.of(id), null)).doesNotThrowAnyException();
    assertThat(searchByDocumentName("doc-a.txt")).isEmpty();
  }

  @Test
  void shouldNotMatchOrThrow_whenDocumentNameContainsFunctionCallSyntax() throws IOException {
    // Arrange
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");

    // Act / Assert: FilterExpressionBuilder carries the value as a structured EQ operand, so a
    // SpEL type-reference/function-call payload is never executed and matches nothing.
    assertThatCode(() -> service.deleteDocuments(null, "T(java.lang.Runtime).getRuntime()"))
      .doesNotThrowAnyException();
    assertThat(searchByDocumentName("doc-a.txt")).isNotEmpty();
  }

  @Test
  void shouldFailLoudRatherThanSilentlyMatch_whenDocumentNameContainsUnescapedQuoteSyntax()
    throws IOException {
    // Arrange: retry-1 correction. The originally hypothesized behaviour (matches nothing, does
    // not throw) was disproven: Spring AI 1.1.2's SimpleVectorStoreFilterExpressionConverter
    // does not escape an embedded single quote inside the generated SpEL literal for a
    // Filter.Value, so an unvalidated documentName value containing an unescaped quote used to
    // produce malformed SpEL and an uncaught SpelEvaluationException mapped to an opaque HTTP
    // 500 -- an ordinary-client-input case (e.g. "O'Brien_report.md"), not merely an adversarial
    // one. Production's ChromaVectorStore has the analogous unescaped-double-quote weakness in
    // its inherited AbstractFilterExpressionConverter#doSingleValue. deleteByDocumentName now
    // rejects both quote characters up front, before any VectorStore call, so this case never
    // reaches the store-specific converter on either store; see context/PLAN.md Increment 6 for
    // the full residual-risk note.
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");

    // Act
    assertThatExceptionOfType(DocumentIngestionException.class)
      .isThrownBy(() -> service.deleteDocuments(null, "' OR '1'='1"))
      .satisfies(exception -> {
        assertThat(exception.code()).isEqualTo("INVALID_DELETE_SELECTOR");
        assertThat(exception.getMessage()).doesNotContain("' OR '1'='1");
      });

    // Assert: rejected as a typed client error, not an opaque store exception; message does not
    // echo the rejected value; the store was never reached, so the survivor is untouched.
    assertThat(searchByDocumentName("doc-a.txt")).isNotEmpty();
  }

  @Test
  void shouldFailSafely_whenDocumentNameContainsUnescapedDoubleQuoteSyntax() throws IOException {
    // Arrange: SimpleVectorStore's own converter is single-quote based, so it cannot itself
    // reproduce production Chroma's unescaped-double-quote weakness. Up-front, store-independent
    // validation is what makes both quote characters provably rejected identically on every
    // VectorStore implementation, including this hermetic one.
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");

    // Act
    assertThatExceptionOfType(DocumentIngestionException.class)
      .isThrownBy(() -> service.deleteDocuments(null, "user\"s guide.pdf"))
      .satisfies(exception -> {
        assertThat(exception.code()).isEqualTo("INVALID_DELETE_SELECTOR");
        assertThat(exception.getMessage()).doesNotContain("user\"s guide.pdf");
      });

    // Assert
    assertThat(searchByDocumentName("doc-a.txt")).isNotEmpty();
  }

  @Test
  void shouldFailSafely_whenDocumentNameContainsBackslashEscapeIntroducer() throws IOException {
    // Arrange: retry-2 correction. Backslash is inert on SimpleVectorStore's SpEL path (a value
    // containing one parses fine here, so this hermetic store cannot itself reproduce the
    // Chroma/JSON failure), but it is the JSON escape-introducer on production's Chroma/JSON
    // path (ChromaVectorStore -> AbstractFilterExpressionConverter#doSingleValue -> a stock
    // Jackson ObjectMapper). Rejecting it up front, store-independently, is what makes the
    // guarantee provable here even though the underlying failure mode itself is Chroma-specific
    // and was established out-of-band by decompilation and a converter probe, not by a live or
    // hermetic Chroma test. "notes\path.md" is a realistic Windows-style relative path value,
    // not merely an adversarial payload.
    ingestFile("doc-a.txt", "Doc a network segmentation policy details for alpha review.");

    // Act
    assertThatExceptionOfType(DocumentIngestionException.class)
      .isThrownBy(() -> service.deleteDocuments(null, "notes\\path.md"))
      .satisfies(exception -> {
        assertThat(exception.code()).isEqualTo("INVALID_DELETE_SELECTOR");
        assertThat(exception.getMessage()).doesNotContain("notes\\path.md");
      });

    // Assert: rejected as a typed client error; message does not echo the rejected value; the
    // store was never reached, so the survivor is untouched.
    assertThat(searchByDocumentName("doc-a.txt")).isNotEmpty();
  }

  @Test
  void shouldDeleteEveryChunk_whenDocumentNameWasSanitizedFromAnApostropheContainingFileName()
    throws IOException {
    // Arrange: a legitimately-ingested file whose original name would have been permanently
    // undeletable by name pre-normalization ("O'Brien_report.md" contains a delete-time-hostile
    // character). Ingestion-time sanitization (IngestionMetadata.sanitizeDocumentName, applied by
    // the real SafeDocumentResourceLoader used by this test's own service) is what makes this
    // proof possible against the real, unmodified DocumentIngestionService.deleteDocuments code
    // path -- no delete-time logic is touched or bypassed.
    ingestFile("O'Brien_report.md",
      "O'Brien network segmentation policy details for delete-normalization proof.");
    assertThat(searchByDocumentName("O_Brien_report.md")).isNotEmpty();

    // Act: the resulting normalized documentName, not the original raw filename, is what a
    // caller must now use -- proving the document is no longer permanently undeletable by name.
    service.deleteDocuments(null, "O_Brien_report.md");

    // Assert: every chunk of the previously-undeletable document is gone.
    assertThat(searchByDocumentName("O_Brien_report.md")).isEmpty();
  }

  @Test
  void shouldDeleteAcrossMultiplePages_whenChunksAccumulateBeyondOnePage() throws IOException {
    // Arrange: page size (maxNumChunks) is 1, so 3 accumulated chunks span 3 pages.
    processingProperties.getChunking().setMaxNumChunks(1);
    processingProperties.setMaxDeleteResolutionPasses(4);
    ingestVersionedFile("v1", "multi-page-doc.txt",
      "First page chunk multi page content about secure defaults.");
    ingestVersionedFile("v2", "multi-page-doc.txt",
      "Second page chunk multi page content about secure defaults.");
    ingestVersionedFile("v3", "multi-page-doc.txt",
      "Third page chunk multi page content about secure defaults.");
    assertThat(searchByDocumentName("multi-page-doc.txt")).hasSize(3);

    // Act
    service.deleteDocuments(null, "multi-page-doc.txt");

    // Assert
    assertThat(searchByDocumentName("multi-page-doc.txt")).isEmpty();
  }

  @Test
  void shouldFailSafely_whenResolutionPassCapIsExhausted() throws IOException {
    // Arrange: 3 accumulated chunks need 4 passes (3 deletes + 1 empty check); cap is only 2.
    processingProperties.getChunking().setMaxNumChunks(1);
    processingProperties.setMaxDeleteResolutionPasses(2);
    ingestVersionedFile("v1", "multi-page-doc.txt",
      "First page chunk multi page content about secure defaults.");
    ingestVersionedFile("v2", "multi-page-doc.txt",
      "Second page chunk multi page content about secure defaults.");
    ingestVersionedFile("v3", "multi-page-doc.txt",
      "Third page chunk multi page content about secure defaults.");
    assertThat(searchByDocumentName("multi-page-doc.txt")).hasSize(3);

    // Act / Assert: fails loudly instead of returning a false-success 204
    assertThatThrownBy(() -> service.deleteDocuments(null, "multi-page-doc.txt"))
      .isInstanceOf(IllegalStateException.class);
    assertThat(searchByDocumentName("multi-page-doc.txt")).isNotEmpty();
  }

  private void ingestFile(String fileName, String content) throws IOException {
    Path file = Files.writeString(temporaryDirectory.resolve(fileName), content);
    IngestionSummary summary = service.ingestResources(
      List.of(file.toUri().toString()), Map.of());
    assertThat(summary.resourcesFailed()).isZero();
  }

  private void ingestVersionedFile(String subdirectory, String fileName, String content)
    throws IOException {
    Path directory = Files.createDirectories(temporaryDirectory.resolve(subdirectory));
    Path file = Files.writeString(directory.resolve(fileName), content);
    IngestionSummary summary = service.ingestResources(
      List.of(file.toUri().toString()), Map.of());
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

  private String onlyId(List<Document> documents) {
    assertThat(documents).hasSize(1);
    return documents.get(0).getId();
  }

  private Set<String> idsOf(List<Document> documents) {
    return documents.stream().map(Document::getId).collect(Collectors.toSet());
  }
}
