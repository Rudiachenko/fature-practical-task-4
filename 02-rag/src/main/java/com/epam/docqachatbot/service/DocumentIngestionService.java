package com.epam.docqachatbot.service;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.epam.docqachatbot.config.DocumentsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
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

  private final ResourceLoader resourceLoader;
  private final VectorStore vectorStore;
  private final DocumentProcessingProperties processingProperties;
  private final DocumentsProperties documentsProperties;

  @EventListener(ApplicationReadyEvent.class)
  void ensureSampleData() {
    /*
      TODO Check if any document exists in the store and assign the corresponding value to "storeIsEmpty" variable
     */
    boolean storeIsEmpty = false;
    if (storeIsEmpty) {
      log.info("Vector store empty, ingesting sample document");
      try {
        ingestResources(documentsProperties.getResources(), Map.of("sourceType", "bootstrap"));
      } catch (Exception ex) {
        log.warn("Failed to ingest bootstrap document", ex);
      }
    }
  }

  public void ingestResources(List<String> resourceLocations, Map<String, Object> metadata)
    throws IOException {
    for (String location : resourceLocations) {
      Resource resource = resourceLoader.getResource(location);
      if (!resource.exists()) {
        log.warn("Resource {} not found, skipping", location);
        continue;
      }

      List<Document> documents;

      // Check if resource is a PDF
      if (isPdfResource(location)) {
        log.info("Processing PDF document from {}", location);
        documents = processPdfDocument(resource, location, metadata);
      } else {
        log.info("Processing text document from {}", location);
        documents = processTextDocument(resource, location, metadata);
      }

      // Apply transformers
      documents = splitIntoChunks(documents);

      log.info("Ingesting {} document chunks from {}", documents.size(), location);

      /*
        TODO Insert documents into the vectorStore
       */
    }
  }

  private boolean isPdfResource(String location) {
    // Check by file extension
    return location.toLowerCase().endsWith(".pdf");
  }

  private List<Document> processPdfDocument(Resource resource, String location,
                                            Map<String, Object> baseMetadata) {
    try {
      /*
        TODO Read and process the PDF document to extract paragraphs.
          Enrich the document's metadata with additional fields: "source" eq location and "documentType" eq "pdf",
          while also keeping baseMetadata and those metadata produced by pdfReader

        Note: Use ParagraphPdfDocumentReader for proper paragraph-level extraction
        This ensures all text content is extracted, not just page-level chunks
       */

      List<Document> documents = null;

      log.info("Extracted {} paragraphs from PDF {}", documents.size(), location);
      return documents;

    } catch (Exception e) {
      log.error("Failed to process PDF document from {}, falling back to text processing", location,
        e);
      try {
        return processTextDocument(resource, location, baseMetadata);
      } catch (IOException ioException) {
        log.error("Failed to process document as text as well", ioException);
        return List.of();
      }
    }
  }

  private List<Document> processTextDocument(Resource resource, String location,
                                             Map<String, Object> baseMetadata) throws IOException {

    /*
      TODO Create a text document with the corresponding document's metadata
     */
    Document document = null;

    return List.of(document);
  }

  private List<Document> splitIntoChunks(List<Document> documents) {
    if (documents.isEmpty()) {
      return documents;
    }

    List<Document> processedDocuments = new ArrayList<>(documents);

    // Apply chunking transformer
    DocumentProcessingProperties.Chunking chunking = processingProperties.getChunking();
    if (chunking.isEnabled()) {
      log.debug("Applying TokenTextSplitter with {} tokens per chunk",
        chunking.getTokensPerChunk());

      /*
        TODO Split documents into smaller chunks and store them into processedDocuments
        Note: Spring AI's TokenTextSplitter doesn't support overlap natively
      */

      log.info("After chunking: {} document chunks created", processedDocuments.size());
    }

    return processedDocuments;
  }

  public void deleteByIds(List<String> documentIds) {
    // TODO Delete documents from the vector store
  }
}

