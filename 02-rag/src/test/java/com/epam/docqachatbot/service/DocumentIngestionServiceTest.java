package com.epam.docqachatbot.service;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.epam.docqachatbot.config.DocumentsProperties;
import com.epam.docqachatbot.ingestion.DocumentFormat;
import com.epam.docqachatbot.ingestion.DocumentIngestionException;
import com.epam.docqachatbot.ingestion.DocumentReaderFactory;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import com.epam.docqachatbot.ingestion.IngestionSummary;
import com.epam.docqachatbot.ingestion.LoadedDocumentResource;
import com.epam.docqachatbot.ingestion.ProvenanceDocumentTransformer;
import com.epam.docqachatbot.ingestion.SafeDocumentResourceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentIngestionServiceTest {

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final DocumentProcessingProperties processingProperties =
    new DocumentProcessingProperties();
  private final DocumentsProperties documentsProperties = new DocumentsProperties();
  private final SafeDocumentResourceLoader resourceLoader =
    mock(SafeDocumentResourceLoader.class);
  private final DocumentReaderFactory readerFactory = mock(DocumentReaderFactory.class);
  private final ProvenanceDocumentTransformer transformer =
    mock(ProvenanceDocumentTransformer.class);

  @BeforeEach
  void stubVectorStoreFilterDeleteAsUnsupportedByDefault() {
    // Makes the bare mock behave like SimpleVectorStore (no native filter-delete) by default, so
    // every existing fallback-path test keeps exercising the same similaritySearch/delete(List)
    // mechanism it always did. Tests proving the primary filter-delete path override this stub.
    doThrow(new UnsupportedOperationException("test double"))
      .when(vectorStore).delete(any(Filter.Expression.class));
  }

  @Test
  void shouldWriteTransformedChunksAndReturnSummary_whenResourceIsValid() {
    // Arrange
    DocumentIngestionService service = service();
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    List<Document> raw = List.of(new Document("raw"));
    List<Document> chunks = List.of(new Document("chunk-1"), new Document("chunk-2"));
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(() -> raw);
    when(transformer.forResource(loaded, Map.of("sourceType", "sample")))
      .thenReturn(documents -> chunks);

    // Act
    IngestionSummary summary = service.ingestResources(
      List.of("classpath:policy.md"), Map.of("sourceType", "sample"));

    // Assert
    assertThat(summary).isEqualTo(new IngestionSummary(1, 0, 2, List.of()));
    verify(vectorStore).accept(chunks);
  }

  @Test
  void shouldContinueAndReportFailure_whenOneResourceFails() {
    // Arrange
    DocumentIngestionService service = service();
    LoadedDocumentResource loaded = loaded("classpath:valid.md");
    List<Document> chunks = List.of(new Document("chunk"));
    when(resourceLoader.load("classpath:missing.md"))
      .thenThrow(new DocumentIngestionException("DOCUMENT_NOT_FOUND", "not found"));
    when(resourceLoader.load("classpath:valid.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    when(transformer.forResource(eq(loaded), anyMap())).thenReturn(documents -> chunks);

    // Act
    IngestionSummary summary = service.ingestResources(
      List.of("classpath:missing.md", "classpath:valid.md"), Map.of());

    // Assert
    assertThat(summary.resourcesProcessed()).isEqualTo(1);
    assertThat(summary.resourcesFailed()).isEqualTo(1);
    assertThat(summary.chunksWritten()).isEqualTo(1);
    assertThat(summary.failures()).singleElement()
      .satisfies(failure -> {
        assertThat(failure.location()).isEqualTo("classpath:missing.md");
        assertThat(failure.code()).isEqualTo("DOCUMENT_NOT_FOUND");
      });
    verify(vectorStore).accept(chunks);
  }

  @Test
  void shouldPropagateInfrastructureFailure_whenVectorStoreWriteFails() {
    LoadedDocumentResource loaded = loaded("classpath:valid.md");
    when(resourceLoader.load("classpath:valid.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    when(transformer.forResource(eq(loaded), anyMap()))
      .thenReturn(documents -> List.of(new Document("chunk")));
    doThrow(new IllegalStateException("provider unavailable"))
      .when(vectorStore).accept(anyList());

    assertThatThrownBy(() -> service().ingestResources(
      List.of("classpath:valid.md"), Map.of()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("provider unavailable");
  }

  @Test
  void shouldFailWithoutWriting_whenAllResourcesFailOrDocumentIsEmpty() {
    // Arrange
    DocumentIngestionService missingService = service();
    when(resourceLoader.load("classpath:missing.md"))
      .thenThrow(new DocumentIngestionException("DOCUMENT_NOT_FOUND", "not found"));

    // Act / Assert
    assertThatThrownBy(() -> missingService.ingestResources(
      List.of("classpath:missing.md"), Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INGESTION_FAILED");
    verifyNoInteractions(vectorStore);

    // Arrange empty output
    LoadedDocumentResource loaded = loaded("classpath:empty.md");
    when(resourceLoader.load("classpath:empty.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(List::of);
    when(transformer.forResource(eq(loaded), anyMap())).thenReturn(documents -> List.of());

    // Act / Assert
    assertThatThrownBy(() -> service().ingestResources(
      List.of("classpath:empty.md"), Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INGESTION_FAILED");
    verify(vectorStore, never()).accept(anyList());
  }

  @Test
  void shouldRejectRequestBeforeReading_whenResourceCountExceedsLimit() {
    // Arrange
    processingProperties.setMaxResourcesPerRequest(1);

    // Act / Assert
    assertThatThrownBy(() -> service().ingestResources(
      List.of("classpath:first.md", "classpath:second.md"), Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("TOO_MANY_RESOURCES");
    verifyNoInteractions(resourceLoader, vectorStore);
  }

  @Test
  void shouldAttemptBootstrapOnce_whenBootstrapIsEnabled() {
    // Arrange
    documentsProperties.setBootstrapEnabled(true);
    documentsProperties.setResources(List.of("classpath:policy.md"));
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    when(transformer.forResource(eq(loaded), anyMap()))
      .thenReturn(documents -> List.of(new Document("chunk")));
    DocumentIngestionService service = service();

    // Act
    service.ensureSampleData();
    service.ensureSampleData();

    // Assert
    verify(resourceLoader, times(1)).load("classpath:policy.md");
    verify(vectorStore, times(1)).accept(anyList());
  }

  @Test
  void shouldFailBootstrap_whenFailFastAndOneResourceIsRejected() {
    documentsProperties.setBootstrapEnabled(true);
    documentsProperties.setBootstrapFailFast(true);
    documentsProperties.setResources(List.of("classpath:missing.md", "classpath:valid.md"));
    when(resourceLoader.load("classpath:missing.md"))
      .thenThrow(new DocumentIngestionException("DOCUMENT_NOT_FOUND", "not found"));
    LoadedDocumentResource loaded = loaded("classpath:valid.md");
    when(resourceLoader.load("classpath:valid.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    when(transformer.forResource(eq(loaded), anyMap()))
      .thenReturn(documents -> List.of(new Document("chunk")));

    assertThatThrownBy(service()::ensureSampleData)
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("BOOTSTRAP_PARTIAL_FAILURE");
  }

  @Test
  void shouldSkipBootstrap_whenBootstrapIsDisabled() {
    // Arrange
    DocumentIngestionService service = service();

    // Act
    service.ensureSampleData();

    // Assert
    verifyNoInteractions(resourceLoader, vectorStore);
  }

  @Test
  void shouldIngestBeforeDeletingStaleChunks_whenReplacingADocumentWithStaleChunks() {
    // Arrange
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    Document staleDocument = Document.builder().id("stale-1").text("stale").build();
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
      .thenReturn(List.of(staleDocument))
      .thenReturn(List.of());
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    Document newChunk = Document.builder().id("new-1").text("new").build();
    when(transformer.forResource(eq(loaded), anyMap())).thenReturn(documents -> List.of(newChunk));
    DocumentIngestionService service = service();

    // Act
    service.replaceDocument("classpath:policy.md", Map.of());

    // Assert: new content is written first; only then is the primary filter-delete attempted,
    // and only after that throws does the fallback resolve-and-delete-as-you-go loop run.
    InOrder order = inOrder(vectorStore);
    order.verify(vectorStore).accept(List.of(newChunk));
    order.verify(vectorStore).delete(any(Filter.Expression.class));
    order.verify(vectorStore).similaritySearch(any(SearchRequest.class));
    order.verify(vectorStore).delete(List.of("stale-1"));
    order.verify(vectorStore).similaritySearch(any(SearchRequest.class));
  }

  @Test
  void shouldIngestWithoutDeleting_whenNoChunksAreCurrentlyIndexedUnderDocumentName() {
    // Arrange
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    Document newChunk = Document.builder().id("new-1").text("new").build();
    when(transformer.forResource(eq(loaded), anyMap())).thenReturn(documents -> List.of(newChunk));
    DocumentIngestionService service = service();

    // Act
    service.replaceDocument("classpath:policy.md", Map.of());

    // Assert
    verify(vectorStore).accept(List.of(newChunk));
    verify(vectorStore, never()).delete(anyList());
  }

  @Test
  void shouldNotDelete_whenIngestFails() {
    // Arrange
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenThrow(new RuntimeException("boom"));
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.replaceDocument("classpath:policy.md", Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("DOCUMENT_PARSE_FAILED");
    verify(vectorStore, never()).accept(anyList());
    verify(vectorStore, never()).delete(anyList());
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
  }

  @Test
  void shouldPropagateException_whenFinalCleanupDeleteFailsAfterSuccessfulIngest() {
    // Arrange
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    Document staleDocument = Document.builder().id("stale-1").text("stale").build();
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
      .thenReturn(List.of(staleDocument))
      .thenReturn(List.of());
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    Document newChunk = Document.builder().id("new-1").text("new").build();
    when(transformer.forResource(eq(loaded), anyMap())).thenReturn(documents -> List.of(newChunk));
    doThrow(new IllegalStateException("vector store unavailable"))
      .when(vectorStore).delete(anyList());
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.replaceDocument("classpath:policy.md", Map.of()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("vector store unavailable");
    verify(vectorStore, times(1)).accept(List.of(newChunk));
  }

  @Test
  void shouldScopeCleanupFilterToDocumentNameExcludingNewChunkIds_whenPartialOverlapExists() {
    // Arrange: mirrors an edit that keeps one chunk unchanged (chunk-2) and edits another
    // (chunk-1 is superseded by chunk-3). The exclusion of the new chunk ids is now baked into
    // the filter expression handed to the store, not computed in application code as a Java-level
    // set difference, so this asserts the filter content itself rather than a mocked-return-based
    // set-difference outcome; full end-to-end exclusion correctness (including the byte-identical
    // full-overlap case) is proven against a real, filter-evaluating store in
    // DocumentReplacementRealStoreTest, since a bare mock does not evaluate filter content.
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    doNothing().when(vectorStore).delete(any(Filter.Expression.class));
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    Document unchangedChunk = Document.builder().id("chunk-2").text("unchanged").build();
    Document editedChunk = Document.builder().id("chunk-3").text("edited").build();
    when(transformer.forResource(eq(loaded), anyMap()))
      .thenReturn(documents -> List.of(unchangedChunk, editedChunk));
    DocumentIngestionService service = service();

    // Act
    service.replaceDocument("classpath:policy.md", Map.of());

    // Assert: primary filter-delete path used, scoped to this document's documentName (matching
    // deleteByDocumentName's own scope, so a same-name replace from a different location is still
    // recognised as the previous version) with the newly-written chunk ids excluded -- never a
    // raw, un-excluded delete.
    ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
    verify(vectorStore).delete(captor.capture());
    FilterExpressionBuilder builder = new FilterExpressionBuilder();
    Filter.Expression expected = builder.and(
        builder.eq(IngestionMetadata.DOCUMENT_NAME, loaded.documentName()),
        builder.nin(IngestionMetadata.CHUNK_ID, "chunk-2", "chunk-3"))
      .build();
    assertThat(captor.getValue()).isEqualTo(expected);
    verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    verify(vectorStore, never()).delete(anyList());
  }

  @Test
  void shouldThrowIllegalState_whenReplaceResolutionPassCapIsExhausted() {
    // Arrange
    processingProperties.setMaxDeleteResolutionPasses(2);
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    Document staleDocument = Document.builder().id("chunk-x").text("text").build();
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    Document newChunk = Document.builder().id("new-1").text("new").build();
    when(transformer.forResource(eq(loaded), anyMap())).thenReturn(documents -> List.of(newChunk));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(staleDocument));
    DocumentIngestionService service = service();

    // Act / Assert: documented behavior change -- new content is now ingested before cleanup is
    // attempted, so pass-cap exhaustion leaves the new chunks written (duplication), unlike
    // DELETE's own cap-exhaustion case, which never writes anything.
    assertThatThrownBy(() -> service.replaceDocument("classpath:policy.md", Map.of()))
      .isInstanceOf(IllegalStateException.class);
    verify(vectorStore, times(1)).accept(List.of(newChunk));
    verify(vectorStore, times(2)).delete(anyList());
  }

  @Test
  void shouldThrowInvalidReplaceSelector_whenDerivedDocumentNameExceedsConfiguredLength() {
    // Arrange
    processingProperties.setMaxDocumentNameLength(5);
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    DocumentIngestionService service = service();

    // Act / Assert: rejected before any VectorStore call.
    assertThatThrownBy(() -> service.replaceDocument("classpath:policy.md", Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_REPLACE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidReplaceSelector_whenDocumentNameContainsControlCharacter() {
    // Arrange: bypasses SafeDocumentResourceLoader's own sanitization directly, so this proves
    // validateReplaceSelector rejects a raw control character defensively even if a documentName
    // ever reaches it unsanitized.
    LoadedDocumentResource loaded = new LoadedDocumentResource(
      "classpath:policy.md", "report.md", DocumentFormat.MARKDOWN,
      "content".getBytes(StandardCharsets.UTF_8), "fingerprint");
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    DocumentIngestionService service = service();

    // Act / Assert: rejected before any VectorStore call.
    assertThatThrownBy(() -> service.replaceDocument("classpath:policy.md", Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_REPLACE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowEmptyDeleteRequest_whenNeitherSelectorIsSupplied() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.deleteDocuments(null, null))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("EMPTY_DELETE_REQUEST");
    assertThatThrownBy(() -> service.deleteDocuments(List.of(), null))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("EMPTY_DELETE_REQUEST");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowAmbiguousDeleteSelector_whenBothIdsAndDocumentNameAreSupplied() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.deleteDocuments(List.of("chunk-1"), "policy.md"))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("AMBIGUOUS_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenDocumentNameIsBlankAfterTrim() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.deleteDocuments(null, "   "))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenDocumentNameExceedsConfiguredLength() {
    // Arrange
    processingProperties.setMaxDocumentNameLength(5);
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.deleteDocuments(null, "too-long-name"))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenDocumentNameContainsSingleQuote() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert: rejected before any VectorStore call, matching an ordinary filename such as
    // "O'Brien_report.md", not merely an adversarial payload.
    assertThatThrownBy(() -> service.deleteDocuments(null, "O'Brien_report.md"))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenDocumentNameContainsDoubleQuote() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert: guards the production-only Chroma converter weakness, which the hermetic
    // SimpleVectorStore-based real-store suite structurally cannot reproduce; store-independent
    // up-front validation is what makes this provable here.
    assertThatThrownBy(() -> service.deleteDocuments(null, "user\"s guide.pdf"))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenDocumentNameContainsBackslash() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert: guards the Chroma/JSON escape-introducer weakness (backslash is the JSON
    // escape character, unlike the SpEL path where it is inert), using a realistic Windows-style
    // relative path value rather than a purely adversarial payload.
    assertThatThrownBy(() -> service.deleteDocuments(null, "notes\\path.md"))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenDocumentNameContainsControlCharacter() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert: rejected before any VectorStore call, closing the previously-deferred
    // control-character gap recorded in IngestionMetadata's own Javadoc.
    assertThatThrownBy(() -> service.deleteDocuments(null, "report.md"))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenIdsExceedConfiguredCount() {
    // Arrange
    processingProperties.setMaxDeleteIdsPerRequest(1);
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.deleteDocuments(List.of("chunk-1", "chunk-2"), null))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldThrowInvalidDeleteSelector_whenIdsContainsBlankEntry() {
    // Arrange
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.deleteDocuments(List.of("chunk-1", "   "), null))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("INVALID_DELETE_SELECTOR");
    verifyNoInteractions(vectorStore);
  }

  @Test
  void shouldDeleteTrimmedIds_whenIdsSelectorIsValid() {
    // Arrange
    DocumentIngestionService service = service();

    // Act
    service.deleteDocuments(List.of(" chunk-1 ", "chunk-2"), null);

    // Assert
    verify(vectorStore).delete(List.of("chunk-1", "chunk-2"));
  }

  @Test
  void shouldUseNativeFilterDeleteWithoutFallback_whenStoreSupportsFilterDelete() {
    // Arrange: overrides the @BeforeEach default (store throws UnsupportedOperationException for
    // filter-delete, as SimpleVectorStore does) to prove the primary path production's
    // ChromaVectorStore takes: a single native metadata-only filter delete that never computes
    // similarity, so a matching chunk can never be missed due to a negative cosine-similarity
    // score or a search threshold.
    doNothing().when(vectorStore).delete(any(Filter.Expression.class));
    DocumentIngestionService service = service();

    // Act
    service.deleteDocuments(null, "policy.md");

    // Assert
    ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
    verify(vectorStore).delete(captor.capture());
    assertThat(captor.getValue()).isEqualTo(
      new FilterExpressionBuilder().eq(IngestionMetadata.DOCUMENT_NAME, "policy.md").build());
    verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    verify(vectorStore, never()).delete(anyList());
  }

  @Test
  void shouldRemainIdempotent_whenDocumentNameDeleteIsRepeatedOnNativeFilterDeletePath() {
    // Arrange
    doNothing().when(vectorStore).delete(any(Filter.Expression.class));
    DocumentIngestionService service = service();

    // Act / Assert: a second DELETE of the same, now-absent, documentName is a successful no-op
    // metadata delete, not a rejection -- matching how ChromaVectorStore's own delete behaves for
    // a filter that matches nothing.
    assertThatCode(() -> service.deleteDocuments(null, "policy.md")).doesNotThrowAnyException();
    assertThatCode(() -> service.deleteDocuments(null, "policy.md")).doesNotThrowAnyException();
    verify(vectorStore, times(2)).delete(any(Filter.Expression.class));
  }

  @Test
  void shouldSearchWithDocumentNameFilterAndAcceptAllThreshold_whenDocumentNameSelectorIsValid() {
    // Arrange
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    processingProperties.getChunking().setMaxNumChunks(250);
    DocumentIngestionService service = service();

    // Act
    service.deleteDocuments(null, "  policy.md  ");

    // Assert
    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    SearchRequest request = captor.getValue();
    assertThat(request.getFilterExpression()).isEqualTo(
      new FilterExpressionBuilder().eq(IngestionMetadata.DOCUMENT_NAME, "policy.md").build());
    assertThat(request.getSimilarityThreshold()).isEqualTo(0.0);
    assertThat(request.getTopK()).isEqualTo(250);
    verify(vectorStore, never()).delete(anyList());
  }

  @Test
  void shouldDeleteEveryPage_whenMultiPageResolutionSequenceEventuallyReturnsEmpty() {
    // Arrange
    Document pageOneDocument = Document.builder().id("chunk-a").text("text-a").build();
    Document pageTwoDocument = Document.builder().id("chunk-b").text("text-b").build();
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
      .thenReturn(List.of(pageOneDocument))
      .thenReturn(List.of(pageTwoDocument))
      .thenReturn(List.of());
    DocumentIngestionService service = service();

    // Act
    service.deleteDocuments(null, "policy.md");

    // Assert
    verify(vectorStore, times(3)).similaritySearch(any(SearchRequest.class));
    verify(vectorStore).delete(List.of("chunk-a"));
    verify(vectorStore).delete(List.of("chunk-b"));
  }

  @Test
  void shouldThrowIllegalState_whenResolutionPassCapIsExhaustedWithoutEmptyPage() {
    // Arrange
    processingProperties.setMaxDeleteResolutionPasses(2);
    Document document = Document.builder().id("chunk-x").text("text").build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
    DocumentIngestionService service = service();

    // Act / Assert
    assertThatThrownBy(() -> service.deleteDocuments(null, "policy.md"))
      .isInstanceOf(IllegalStateException.class);
    verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    verify(vectorStore, times(2)).delete(List.of("chunk-x"));
  }

  @Test
  void shouldSerializeAccessToTheSameDocument_whenReplaceAndDeleteRunConcurrently() throws Exception {
    // Arrange: the striped lock keyed by documentName must serialize replaceDocument/deleteDocuments
    // for the SAME document. Proven by an entrant counter inside the guarded call, not by timing,
    // so this is deterministic regardless of thread scheduling: every task locks on the identical
    // key, so they always land on the same stripe and are always fully serialized.
    LoadedDocumentResource loaded = loaded("classpath:policy.md");
    when(resourceLoader.load("classpath:policy.md")).thenReturn(loaded);
    when(readerFactory.create(loaded)).thenReturn(() -> List.of(new Document("raw")));
    when(transformer.forResource(eq(loaded), anyMap()))
      .thenReturn(documents -> List.of(Document.builder().id("chunk-1").text("chunk").build()));
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    doAnswer(invocation -> {
      int current = active.incrementAndGet();
      maxActive.accumulateAndGet(current, Math::max);
      Thread.sleep(20);
      active.decrementAndGet();
      return null;
    }).when(vectorStore).delete(any(Filter.Expression.class));
    DocumentIngestionService service = service();
    int taskCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(taskCount);

    // Act
    List<Future<?>> futures = new ArrayList<>();
    for (int index = 0; index < taskCount; index++) {
      boolean replace = index % 2 == 0;
      futures.add(executor.submit(() -> {
        if (replace) {
          service.replaceDocument("classpath:policy.md", Map.of());
        } else {
          service.deleteDocuments(null, "policy.md");
        }
      }));
    }
    for (Future<?> future : futures) {
      future.get(5, TimeUnit.SECONDS);
    }
    executor.shutdown();

    // Assert: at most one task was ever inside the guarded delete call at once.
    assertThat(maxActive.get()).isEqualTo(1);
  }

  private DocumentIngestionService service() {
    return new DocumentIngestionService(
      vectorStore,
      processingProperties,
      documentsProperties,
      resourceLoader,
      readerFactory,
      transformer
    );
  }

  private LoadedDocumentResource loaded(String location) {
    byte[] content = "content".getBytes(StandardCharsets.UTF_8);
    return new LoadedDocumentResource(
      location, "policy.md", DocumentFormat.MARKDOWN, content, "fingerprint");
  }
}
