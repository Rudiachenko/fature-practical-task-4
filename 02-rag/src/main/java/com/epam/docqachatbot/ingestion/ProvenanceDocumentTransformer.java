package com.epam.docqachatbot.ingestion;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProvenanceDocumentTransformer {

  private final DocumentProcessingProperties properties;
  private final Encoding encoding = Encodings.newLazyEncodingRegistry()
    .getEncoding(EncodingType.CL100K_BASE);

  public ProvenanceDocumentTransformer(DocumentProcessingProperties properties) {
    this.properties = properties;
  }

  public DocumentTransformer forResource(LoadedDocumentResource resource,
                                         Map<String, Object> callerMetadata) {
    Map<String, Object> metadataSnapshot = Map.copyOf(callerMetadata);
    return documents -> transform(resource, documents, metadataSnapshot);
  }

  public List<Document> transform(LoadedDocumentResource resource,
                                  List<Document> rawDocuments,
                                  Map<String, Object> callerMetadata) {
    rejectReservedMetadata(callerMetadata);
    validateConfiguration();
    List<Document> semanticSections = rawDocuments.stream()
      .filter(Document::isText)
      .filter(document -> document.getText() != null && !document.getText().isBlank())
      .map(document -> withHeadingBreadcrumb(resource, document, callerMetadata))
      .toList();
    List<Document> split = splitOversized(semanticSections);
    if (split.size() > properties.getChunking().getMaxNumChunks()) {
      throw new DocumentIngestionException("TOO_MANY_CHUNKS",
        "Document exceeds the configured chunk limit");
    }
    List<Document> result = new ArrayList<>(split.size());
    String documentId = resource.documentId();
    for (int index = 0; index < split.size(); index++) {
      Document chunk = split.get(index);
      String chunkId = Hashing.sha256(documentId + "|" + index + "|"
        + chunk.getText() + "|" + properties.getEmbeddingModel());
      Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
      metadata.put(IngestionMetadata.DOCUMENT_NAME, resource.documentName());
      metadata.put(IngestionMetadata.SOURCE, resource.location());
      metadata.put(IngestionMetadata.DOCUMENT_ID, documentId);
      metadata.put(IngestionMetadata.FINGERPRINT, resource.fingerprint());
      metadata.put(IngestionMetadata.DOCUMENT_TYPE, resource.format().metadataValue());
      metadata.put(IngestionMetadata.CHUNK_INDEX, index);
      metadata.put(IngestionMetadata.CHUNK_ID, chunkId);
      metadata.put(IngestionMetadata.EMBEDDING_MODEL, properties.getEmbeddingModel());
      metadata.putIfAbsent(IngestionMetadata.HEADING_PATH, "");
      result.add(Document.builder()
        .id(chunkId)
        .text(chunk.getText())
        .metadata(metadata)
        .build());
    }
    return List.copyOf(result);
  }

  private Document withHeadingBreadcrumb(LoadedDocumentResource resource,
                                          Document document,
                                          Map<String, Object> callerMetadata) {
    Map<String, Object> metadata = new HashMap<>(callerMetadata);
    metadata.putAll(document.getMetadata());
    String heading = String.valueOf(metadata.getOrDefault(IngestionMetadata.HEADING_PATH, ""));
    String text = document.getText().strip();
    metadata.put(IngestionMetadata.DOCUMENT_NAME, resource.documentName());
    metadata.put(IngestionMetadata.SOURCE, resource.location());
    metadata.put(IngestionMetadata.DOCUMENT_TYPE, resource.format().metadataValue());
    String sectionId = Hashing.sha256(
      resource.location() + "|" + resource.fingerprint() + "|" + heading + "|" + text);
    return Document.builder().id(sectionId).text(text).metadata(metadata).build();
  }

  private List<Document> splitOversized(List<Document> documents) {
    DocumentProcessingProperties.Chunking config = properties.getChunking();
    if (!config.isEnabled()) {
      return documents.stream()
        .map(document -> withBreadcrumb(document, breadcrumb(document) + document.getText()))
        .toList();
    }
    List<Document> result = new ArrayList<>();
    for (Document document : documents) {
      result.addAll(splitWithBreadcrumb(document, config));
      if (result.size() > config.getMaxNumChunks()) {
        throw new DocumentIngestionException("TOO_MANY_CHUNKS",
          "Document exceeds the configured chunk limit");
      }
    }
    return result;
  }

  private List<Document> splitWithBreadcrumb(Document document,
                                              DocumentProcessingProperties.Chunking config) {
    String breadcrumb = breadcrumb(document);
    int breadcrumbTokens = encoding.countTokens(breadcrumb);
    int contentBudget = config.getTokensPerChunk() - breadcrumbTokens;
    if (contentBudget <= 0) {
      throw new DocumentIngestionException("HEADING_EXCEEDS_CHUNK_LIMIT",
        "Heading breadcrumb exceeds the configured token limit");
    }

    while (contentBudget > 0) {
      TokenTextSplitter splitter = splitter(config, contentBudget);
      List<Document> split = splitter.apply(List.of(document));
      if (split.isEmpty()) {
        String combined = breadcrumb + document.getText();
        if (encoding.countTokens(combined) <= config.getTokensPerChunk()) {
          return List.of(withBreadcrumb(document, combined));
        }
        throw new DocumentIngestionException("TOO_MANY_CHUNKS",
          "Document exceeds the configured chunk limit");
      }

      List<Document> prefixed = split.stream()
        .map(chunk -> withBreadcrumb(chunk, breadcrumb + chunk.getText()))
        .toList();
      int overflow = prefixed.stream()
        .mapToInt(chunk -> encoding.countTokens(chunk.getText()) - config.getTokensPerChunk())
        .max()
        .orElse(0);
      if (overflow <= 0) {
        return prefixed;
      }
      contentBudget -= overflow;
    }
    throw new DocumentIngestionException("TOO_MANY_CHUNKS",
      "Document exceeds the configured chunk limit");
  }

  private String breadcrumb(Document document) {
    String heading = String.valueOf(
      document.getMetadata().getOrDefault(IngestionMetadata.HEADING_PATH, ""));
    return heading.isBlank() ? "" : "Heading: " + heading + "\n\n";
  }

  private TokenTextSplitter splitter(DocumentProcessingProperties.Chunking config,
                                     int contentBudget) {
    return TokenTextSplitter.builder()
      .withChunkSize(contentBudget)
      .withMinChunkSizeChars(config.getMinChunkSizeChars())
      .withMinChunkLengthToEmbed(config.getMinChunkLengthToEmbed())
      .withMaxNumChunks(config.getMaxNumChunks())
      .withKeepSeparator(config.isKeepSeparator())
      .build();
  }

  private Document withBreadcrumb(Document document, String text) {
    return Document.builder()
      .id(document.getId())
      .text(text)
      .metadata(document.getMetadata())
      .build();
  }

  private void rejectReservedMetadata(Map<String, Object> callerMetadata) {
    if (callerMetadata.keySet().stream().anyMatch(IngestionMetadata.RESERVED_KEYS::contains)) {
      throw new DocumentIngestionException("RESERVED_METADATA_KEY",
        "Caller metadata contains a reserved provenance key");
    }
  }

  private void validateConfiguration() {
    DocumentProcessingProperties.Chunking config = properties.getChunking();
    if (properties.getEmbeddingModel() == null || properties.getEmbeddingModel().isBlank()
      || config.getTokensPerChunk() <= 0
      || config.getMinChunkSizeChars() < 0
      || config.getMinChunkLengthToEmbed() < 0
      || config.getMaxNumChunks() <= 0) {
      throw new IllegalStateException("Document processing configuration is invalid");
    }
  }
}
