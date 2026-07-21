package com.epam.docqachatbot.service;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.epam.docqachatbot.config.DocumentsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

  // A probe query used only to detect whether the vector store already has data.
  // This is a heuristic, not a guaranteed count — acceptable for bootstrap gating.
  private static final String BOOTSTRAP_PROBE_QUERY = "bootstrap-check";
  private static final Map<String, Object> BOOTSTRAP_METADATA =
    Map.of("sourceType", "bootstrap");

  private final ResourceLoader resourceLoader;
  private final VectorStore vectorStore;
  private final DocumentProcessingProperties processingProperties;
  private final DocumentsProperties documentsProperties;

  @EventListener(ApplicationReadyEvent.class)
  void ensureSampleData() {
    if (isVectorStoreEmpty()) {
      log.info("Vector store appears empty — ingesting bootstrap documents");
      try {
        ingestResources(documentsProperties.getResources(), BOOTSTRAP_METADATA);
      } catch (Exception ex) {
        log.error("Bootstrap ingestion failed; application may lack seed data", ex);
      }
    }
  }

  private boolean isVectorStoreEmpty() {
    /*
      TODO Check if any document exists in the store and return whether it is empty
     */
    return false;
  }

  /**
   * Ingests all resources, continuing past individual failures and reporting them at the end.
   */
  public void ingestResources(List<String> resourceLocations, Map<String, Object> metadata) {
    List<String> failures = new ArrayList<>();
    for (String location : resourceLocations) {
      try {
        ingestSingleResource(location, metadata);
      } catch (Exception ex) {
        log.error("Failed to ingest '{}', skipping", location, ex);
        failures.add(location);
      }
    }
    if (!failures.isEmpty()) {
      log.warn("Ingestion completed with {} failure(s): {}", failures.size(), failures);
    }
  }

  private void ingestSingleResource(String location, Map<String, Object> metadata) {
    Resource resource = resourceLoader.getResource(location);
    if (!resource.exists()) {
      log.warn("Resource '{}' not found, skipping", location);
      return;
    }

    List<Document> raw = isPdf(location)
      ? readPdfDocument(resource, location, metadata)
      : readTextDocument(resource, location, metadata);

    List<Document> chunks = chunk(raw);
    log.info("Ingesting {} chunk(s) from '{}'", chunks.size(), location);

    /*
      TODO Insert documents into the vectorStore
     */
  }

  private boolean isPdf(String location) {
    return location.toLowerCase().endsWith(".pdf");
  }

  private List<Document> readPdfDocument(Resource resource,
    String location,
    Map<String, Object> baseMetadata) {
    log.info("Processing PDF document from {}", location);

    /*
      TODO Read and process the PDF document to extract paragraphs.
        Enrich each document's metadata with additional fields: "source" eq location and "documentType" eq "pdf",
        while also keeping baseMetadata and those metadata produced by pdfReader

      Note: Use ParagraphPdfDocumentReader for proper paragraph-level extraction
      This ensures all text content is extracted, not just page-level chunks
     */

    List<Document> paragraphs = null;

    log.info("Extracted {} paragraphs from PDF {}", paragraphs.size(), location);
    return paragraphs;
  }

  private List<Document> readTextDocument(Resource resource,
    String location,
    Map<String, Object> baseMetadata) {
    log.info("Processing text document from {}", location);

    /*
      TODO Create a text document with the corresponding document's metadata
     */
    Document document = null;

    return List.of(document);
  }

  private Document enrich(Document source,
    String location,
    String documentType,
    Map<String, Object> baseMetadata) {
    Map<String, Object> merged = buildMetadata(location, documentType, baseMetadata);
    merged.putAll(source.getMetadata()); // PDF-reader metadata wins on key conflicts
    return Document.builder()
      .id(source.getId())
      .text(source.getText())
      .metadata(merged)
      .build();
  }

  private Map<String, Object> buildMetadata(String location,
    String documentType,
    Map<String, Object> base) {
    Map<String, Object> meta = new HashMap<>(base);
    meta.put("source", location);
    meta.put("documentType", documentType);
    return meta;
  }

  private List<Document> chunk(List<Document> documents) {
    if (documents.isEmpty()) {
      return documents;
    }
    DocumentProcessingProperties.Chunking cfg = processingProperties.getChunking();
    if (!cfg.isEnabled()) {
      return documents;
    }

    /*
      TODO Split documents into smaller chunks and return them
      Note: Spring AI's TokenTextSplitter doesn't support overlap natively
     */

    List<Document> chunks = null;

    log.info("After chunking: {} document chunks created", chunks.size());
    return chunks;
  }

  public void deleteByIds(List<String> documentIds) {
    if (documentIds == null || documentIds.isEmpty()) {
      return;
    }
    log.info("Deleting {} document(s) from vector store", documentIds.size());
    // TODO Delete documents from the vector store
  }
}
