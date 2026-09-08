package com.epam.docqachatbot.service;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.epam.docqachatbot.config.DocumentsProperties;
import com.epam.docqachatbot.ingestion.DocumentIngestionException;
import com.epam.docqachatbot.ingestion.DocumentReaderFactory;
import com.epam.docqachatbot.ingestion.IngestionFailure;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import com.epam.docqachatbot.ingestion.IngestionSummary;
import com.epam.docqachatbot.ingestion.LoadedDocumentResource;
import com.epam.docqachatbot.ingestion.ProvenanceDocumentTransformer;
import com.epam.docqachatbot.ingestion.SafeDocumentResourceLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class DocumentIngestionService {

  private static final Map<String, Object> BOOTSTRAP_METADATA =
    Map.of("sourceType", "bootstrap");

  private static final int DOCUMENT_LOCK_STRIPE_COUNT = 32;

  private final VectorStore vectorStore;
  private final DocumentProcessingProperties processingProperties;
  private final DocumentsProperties documentsProperties;
  private final SafeDocumentResourceLoader resourceLoader;
  private final DocumentReaderFactory readerFactory;
  private final ProvenanceDocumentTransformer transformer;
  private final AtomicBoolean bootstrapAttempted = new AtomicBoolean();
  private final StripedLock documentLocks = new StripedLock(DOCUMENT_LOCK_STRIPE_COUNT);

  public DocumentIngestionService(VectorStore vectorStore,
                                  DocumentProcessingProperties processingProperties,
                                  DocumentsProperties documentsProperties,
                                  SafeDocumentResourceLoader resourceLoader,
                                  DocumentReaderFactory readerFactory,
                                  ProvenanceDocumentTransformer transformer) {
    this.vectorStore = vectorStore;
    this.processingProperties = processingProperties;
    this.documentsProperties = documentsProperties;
    this.resourceLoader = resourceLoader;
    this.readerFactory = readerFactory;
    this.transformer = transformer;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void ensureSampleData() {
    if (!documentsProperties.isBootstrapEnabled()
      || !bootstrapAttempted.compareAndSet(false, true)) {
      return;
    }
    try {
      IngestionSummary summary = ingestResources(
        documentsProperties.getResources(), BOOTSTRAP_METADATA);
      if (documentsProperties.isBootstrapFailFast() && summary.resourcesFailed() > 0) {
        throw new DocumentIngestionException("BOOTSTRAP_PARTIAL_FAILURE",
          "Bootstrap ingestion did not process every configured resource");
      }
      log.info("Bootstrap ingestion completed: resources={}, chunks={}, failures={}",
        summary.resourcesProcessed(), summary.chunksWritten(), summary.resourcesFailed());
    } catch (RuntimeException exception) {
      log.error("Bootstrap ingestion failed, type={}", exception.getClass().getName());
      if (documentsProperties.isBootstrapFailFast()) {
        throw exception;
      }
    }
  }

  public IngestionSummary ingestResources(List<String> resourceLocations,
                                          Map<String, Object> metadata) {
    validateRequest(resourceLocations);
    Map<String, Object> safeMetadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    List<IngestionFailure> failures = new ArrayList<>();
    int resourcesProcessed = 0;
    int chunksWritten = 0;

    for (String location : resourceLocations) {
      try {
        List<Document> resourceChunks = ingestSingleResource(location, safeMetadata);
        resourcesProcessed++;
        chunksWritten += resourceChunks.size();
      } catch (DocumentIngestionException exception) {
        failures.add(new IngestionFailure(location, exception.code()));
        log.warn("Document ingestion rejected: code={}, locationLength={}",
          exception.code(), location == null ? 0 : location.length());
      }
    }

    if (resourcesProcessed == 0 && !failures.isEmpty()) {
      throw new DocumentIngestionException("INGESTION_FAILED",
        "No document resources were ingested");
    }
    return new IngestionSummary(
      resourcesProcessed, failures.size(), chunksWritten, failures);
  }

  /**
   * Replaces every currently-indexed chunk of one resource's resolved {@code documentName} with
   * the content of that resource in a single call, without ever leaving the document absent
   * from the index.
   *
   * <p><b>Ordering: validate -&gt; ingest new content -&gt; delete only what became stale.</b>
   * The new content is ingested first, through the same per-resource path
   * {@link #ingestResources(List, Map)} uses; only then is a single filter delete issued for
   * {@code documentName == trimmedName AND chunkId NOT IN newChunkIds} (never a raw,
   * un-excluded delete): because {@code chunkId} is a content-fingerprint, not a version
   * counter, a byte-identical or partially-unchanged replace can make the prior version's chunk
   * ids overlap the newly-written ones, and deleting without excluding them would delete chunks
   * this call just wrote. The cleanup scope is {@code documentName}, matching the caller-facing
   * selector {@link #deleteByDocumentName(String)} already uses, so a resource re-ingested from a
   * different location under the same {@code documentName} (e.g. a re-uploaded new version
   * staged at a different path) is still recognised as the previous version and cleaned up.
   * {@code documentId} (location-derived, exposed via chunk metadata) is a stable per-location
   * identity for provenance purposes; it is deliberately not used to scope this cleanup, because
   * doing so would stop treating a same-name replacement from a different location as "the
   * previous version." One accepted consequence, matching {@link #deleteByDocumentName(String)}'s
   * already-documented limitation: two genuinely unrelated documents whose resource locations
   * happen to sanitize to the same {@code documentName} share this cleanup scope too, so
   * replacing one also removes the other's chunks.
   *
   * <p>The store's native filter delete ({@link VectorStore#delete(Filter.Expression)}) is used
   * when available; only a store that throws {@link UnsupportedOperationException} for it (e.g.
   * {@code SimpleVectorStore}) falls back to a bounded similarity-search delete-as-you-go loop —
   * see {@link #deleteAllMatching(Filter.Expression, String, String)}.
   *
   * <p><b>Not atomic — stated precisely, not claimed stronger than it is.</b> This is two
   * independent {@link VectorStore} calls (ingest, then cleanup delete). If validation fails,
   * nothing has changed. If ingestion fails, the prior version is completely untouched, since no
   * delete call is ever reached. If the cleanup delete fails, or the fallback path exhausts its
   * pass cap, after a successful ingest, the prior version's stale chunks can remain alongside
   * the new ones — duplicated, never lost; this is the deliberate, accepted failure direction.
   *
   * <p>If nothing is currently indexed under the resolved {@code documentName}, the cleanup delete
   * simply matches nothing, and this call behaves as a plain create (idempotent upsert), not a
   * rejection.
   *
   * <p>Concurrent replace/delete of the same {@code documentName} is serialized via a bounded
   * striped lock ({@link #documentLocks}); it is not a global lock, so unrelated documents
   * proceed concurrently.
   */
  public void replaceDocument(String resourceLocation, Map<String, Object> metadata) {
    Map<String, Object> safeMetadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    LoadedDocumentResource resource = resourceLoader.load(resourceLocation);
    String trimmedName = validateReplaceSelector(resource.documentName());
    documentLocks.withLock(trimmedName, () -> {
      List<Document> newChunks = ingestSingleResource(resourceLocation, safeMetadata);
      List<String> newChunkIds = newChunks.stream().map(Document::getId).toList();
      FilterExpressionBuilder builder = new FilterExpressionBuilder();
      Filter.Expression filterExpression = builder.and(
          builder.eq(IngestionMetadata.DOCUMENT_NAME, trimmedName),
          builder.nin(IngestionMetadata.CHUNK_ID, newChunkIds.toArray()))
        .build();
      deleteAllMatching(filterExpression, trimmedName,
        "Document replace resolution exceeded the configured pass cap");
      log.info("Document replace completed: newChunks={}", newChunks.size());
    });
  }

  /**
   * Deletes stored chunks by exactly one of two mutually exclusive selectors.
   *
   * <p>{@code ids} deletes the given opaque chunk ids directly. {@code documentName} deletes
   * every chunk whose {@link IngestionMetadata#DOCUMENT_NAME} metadata equals the trimmed value,
   * deterministically and without depending on vector similarity — see
   * {@link #deleteAllMatching(Filter.Expression, String, String)} — because {@code documentId}/
   * {@code chunkId} are content-fingerprint-derived and change on every content edit while
   * {@code documentName} does not. Concurrent delete/replace of the same {@code documentName} is
   * serialized via a bounded striped lock ({@link #documentLocks}).</p>
   *
   * <p>A selector that matches nothing is a successful no-op, so repeated DELETE is idempotent;
   * neither selector supplied, both supplied, or an invalid selector value throws {@link
   * DocumentIngestionException} before any {@link VectorStore} call. Fallback-path resolution-cap
   * exhaustion throws {@link IllegalStateException} rather than allowing a false-success
   * return.</p>
   */
  public void deleteDocuments(List<String> ids, String documentName) {
    boolean idsProvided = ids != null && !ids.isEmpty();
    boolean documentNameProvided = documentName != null;
    if (!idsProvided && !documentNameProvided) {
      throw new DocumentIngestionException("EMPTY_DELETE_REQUEST",
        "Exactly one of ids or documentName is required");
    }
    if (idsProvided && documentNameProvided) {
      throw new DocumentIngestionException("AMBIGUOUS_DELETE_SELECTOR",
        "Exactly one of ids or documentName may be supplied, not both");
    }
    if (idsProvided) {
      deleteByIds(ids);
    } else {
      deleteByDocumentName(documentName);
    }
  }

  private List<Document> ingestSingleResource(String location, Map<String, Object> metadata) {
    LoadedDocumentResource resource = resourceLoader.load(location);
    List<Document> rawDocuments;
    try {
      rawDocuments = readerFactory.create(resource).read();
    } catch (DocumentIngestionException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new DocumentIngestionException("DOCUMENT_PARSE_FAILED",
        "Document content could not be parsed", exception);
    }
    List<Document> chunks = transformer.forResource(resource, metadata).transform(rawDocuments);
    if (chunks.isEmpty()) {
      throw new DocumentIngestionException("EMPTY_DOCUMENT",
        "Document contains no indexable text");
    }
    vectorStore.accept(chunks);
    log.info("Document ingestion completed: documentName={}, chunks={}",
      resource.documentName(), chunks.size());
    return chunks;
  }

  private void validateRequest(List<String> resourceLocations) {
    if (resourceLocations == null || resourceLocations.isEmpty()) {
      throw new DocumentIngestionException("EMPTY_INGESTION_REQUEST",
        "At least one document resource is required");
    }
    if (resourceLocations.size() > processingProperties.getMaxResourcesPerRequest()) {
      throw new DocumentIngestionException("TOO_MANY_RESOURCES",
        "Ingestion request exceeds the configured resource limit");
    }
  }

  /**
   * Validates a {@code documentName} derived from a PUT's resource selector before any
   * {@link VectorStore} call, throwing {@link DocumentIngestionException} with code
   * {@code INVALID_REPLACE_SELECTOR} (distinct from {@link #deleteByDocumentName(String)}'s
   * {@code INVALID_DELETE_SELECTOR}, since this failure occurs inside a {@code PUT}, not a
   * caller-supplied {@code DELETE} selector) on a blank, oversized, or filter-hostile value. In
   * practice {@code documentName} here is always already-sanitized by {@link
   * com.epam.docqachatbot.ingestion.SafeDocumentResourceLoader}, so only the length check is
   * practically reachable; the others are kept defensively.
   */
  private String validateReplaceSelector(String documentName) {
    String trimmedName = documentName.trim();
    if (trimmedName.isEmpty()) {
      throw new DocumentIngestionException("INVALID_REPLACE_SELECTOR",
        "documentName must not be blank");
    }
    if (trimmedName.length() > processingProperties.getMaxDocumentNameLength()) {
      throw new DocumentIngestionException("INVALID_REPLACE_SELECTOR",
        "documentName exceeds the configured length limit");
    }
    if (IngestionMetadata.containsFilterHostileCharacter(trimmedName)) {
      throw new DocumentIngestionException("INVALID_REPLACE_SELECTOR",
        "documentName contains a character that is not supported by the delete filter");
    }
    return trimmedName;
  }

  /**
   * Deletes every chunk matching {@code filterExpression}, preferring the store's native filter
   * delete ({@link VectorStore#delete(Filter.Expression)}) and falling back to a bounded
   * similarity-search delete-as-you-go loop only when the store throws {@link
   * UnsupportedOperationException} for it — a failure bytecode-verified to be synchronous and
   * side-effect-free for every store in this codebase, so falling back afterward is safe.
   *
   * <p>The fallback loop deletes each page as soon as it is found (unlike a read-only resolve
   * pass), so a deleted chunk can never reappear on the next page: no progressive exclusion
   * across passes is needed, and {@code filterExpression} may already bake in any exclusion the
   * caller needs (e.g. {@link #replaceDocument(String, Map)}'s {@code chunkId NOT IN
   * newChunkIds)}). {@code fallbackSearchQuery} only feeds the fallback's similarity-search
   * query text; it never gates which chunks match, since the search's similarity threshold
   * accepts everything and the actual match is entirely determined by {@code filterExpression}.
   * Resolution-cap exhaustion throws {@link IllegalStateException} with {@code
   * passCapExceededMessage} rather than returning a false success.
   */
  private void deleteAllMatching(Filter.Expression filterExpression, String fallbackSearchQuery,
                                 String passCapExceededMessage) {
    try {
      vectorStore.delete(filterExpression);
      return;
    } catch (UnsupportedOperationException unsupported) {
      // Store has no native filter-delete (e.g. SimpleVectorStore in tests); the throw above is
      // synchronous and side-effect-free, so falling back here is safe.
    }
    int pageSize = processingProperties.getChunking().getMaxNumChunks();
    int maxPasses = processingProperties.getMaxDeleteResolutionPasses();
    for (int pass = 1; pass <= maxPasses; pass++) {
      List<Document> matches = vectorStore.similaritySearch(SearchRequest.builder()
        .query(fallbackSearchQuery)
        .topK(pageSize)
        .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
        .filterExpression(filterExpression)
        .build());
      if (matches.isEmpty()) {
        log.info("Filter-delete fallback resolved: passes={}", pass);
        return;
      }
      List<String> pageIds = matches.stream().map(Document::getId).toList();
      vectorStore.delete(pageIds);
      log.info("Filter-delete fallback deleted page: pass={}, chunks={}", pass, pageIds.size());
    }
    throw new IllegalStateException(passCapExceededMessage);
  }

  private void deleteByIds(List<String> ids) {
    if (ids.size() > processingProperties.getMaxDeleteIdsPerRequest()) {
      throw new DocumentIngestionException("INVALID_DELETE_SELECTOR",
        "Delete request exceeds the configured id count limit");
    }
    List<String> trimmedIds = new ArrayList<>(ids.size());
    for (String id : ids) {
      if (id == null || id.isBlank()) {
        throw new DocumentIngestionException("INVALID_DELETE_SELECTOR",
          "Delete request contains a blank id");
      }
      trimmedIds.add(id.trim());
    }
    log.info("Deleting document chunks by id: count={}", trimmedIds.size());
    vectorStore.delete(trimmedIds);
  }

  private void deleteByDocumentName(String documentName) {
    String trimmedName = documentName.trim();
    if (trimmedName.isEmpty()) {
      throw new DocumentIngestionException("INVALID_DELETE_SELECTOR",
        "documentName must not be blank");
    }
    if (trimmedName.length() > processingProperties.getMaxDocumentNameLength()) {
      throw new DocumentIngestionException("INVALID_DELETE_SELECTOR",
        "documentName exceeds the configured length limit");
    }
    if (IngestionMetadata.containsFilterHostileCharacter(trimmedName)) {
      throw new DocumentIngestionException("INVALID_DELETE_SELECTOR",
        "documentName contains a character that is not supported by the delete filter; "
          + "delete the affected chunks by id instead");
    }
    log.info("Deleting document chunks by documentName: nameLength={}", trimmedName.length());
    documentLocks.withLock(trimmedName, () -> {
      Filter.Expression filterExpression = new FilterExpressionBuilder()
        .eq(IngestionMetadata.DOCUMENT_NAME, trimmedName)
        .build();
      deleteAllMatching(filterExpression, trimmedName,
        "Document deletion resolution exceeded the configured pass cap");
    });
  }
}
