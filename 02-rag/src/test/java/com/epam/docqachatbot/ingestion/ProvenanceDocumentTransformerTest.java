package com.epam.docqachatbot.ingestion;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvenanceDocumentTransformerTest {

  @Test
  void shouldAssignStableProvenanceAndPreserveMetadata_whenDocumentIsTransformed() {
    // Arrange
    ProvenanceDocumentTransformer transformer = transformer(800, 100);
    LoadedDocumentResource resource = resource("classpath:policy.md", "policy.md", "content");
    Document raw = Document.builder()
      .text("Validate all inputs.")
      .metadata(Map.of(IngestionMetadata.HEADING_PATH, "Security > Input", "page", 3))
      .build();

    // Act
    List<Document> first = transformer.transform(
      resource, List.of(raw), Map.of("sourceType", "sample"));
    List<Document> second = transformer.transform(
      resource, List.of(raw), Map.of("sourceType", "sample"));

    // Assert
    assertThat(first).hasSize(1);
    Document chunk = first.getFirst();
    assertThat(chunk.getId()).isEqualTo(second.getFirst().getId()).hasSize(64);
    assertThat(chunk.getMetadata()).isEqualTo(second.getFirst().getMetadata());
    assertThat(chunk.getText())
      .startsWith("Heading: Security > Input")
      .contains("Validate all inputs.");
    assertThat(chunk.getMetadata())
      .containsEntry("sourceType", "sample")
      .containsEntry("page", 3)
      .containsEntry(IngestionMetadata.DOCUMENT_NAME, "policy.md")
      .containsEntry(IngestionMetadata.SOURCE, "classpath:policy.md")
      .containsEntry(IngestionMetadata.DOCUMENT_TYPE, "markdown")
      .containsEntry(IngestionMetadata.CHUNK_INDEX, 0)
      .containsEntry(IngestionMetadata.CHUNK_ID, chunk.getId())
      .containsEntry(IngestionMetadata.HEADING_PATH, "Security > Input")
      .containsEntry(IngestionMetadata.EMBEDDING_MODEL, "embedding-test-model");
    assertThat(chunk.getMetadata().get(IngestionMetadata.DOCUMENT_ID).toString()).hasSize(64);
    assertThat(chunk.getMetadata().get(IngestionMetadata.FINGERPRINT))
      .isEqualTo(resource.fingerprint());
  }

  @Test
  void shouldCreateNonCollidingIds_whenSameContentHasDifferentSource() {
    // Arrange
    ProvenanceDocumentTransformer transformer = transformer(800, 100);
    Document raw = new Document("same content");

    // Act
    Document first = transformer.transform(
      resource("classpath:first.md", "first.md", "same content"),
      List.of(raw), Map.of()).getFirst();
    Document second = transformer.transform(
      resource("classpath:second.md", "second.md", "same content"),
      List.of(raw), Map.of()).getFirst();

    // Assert
    assertThat(first.getId()).isNotEqualTo(second.getId());
    assertThat(first.getMetadata().get(IngestionMetadata.DOCUMENT_ID))
      .isNotEqualTo(second.getMetadata().get(IngestionMetadata.DOCUMENT_ID));
  }

  @Test
  void shouldSplitOnlyOversizedSectionsAndPreserveHeading_whenTextExceedsBound() {
    // Arrange
    ProvenanceDocumentTransformer transformer = transformer(20, 100);
    Document raw = Document.builder()
      .text("secure value ".repeat(100))
      .metadata(Map.of(IngestionMetadata.HEADING_PATH, "Security"))
      .build();

    // Act
    List<Document> chunks = transformer.transform(
      resource("classpath:large.md", "large.md", raw.getText()),
      List.of(raw), Map.of());
    List<Document> repeated = transformer.transform(
      resource("classpath:large.md", "large.md", raw.getText()),
      List.of(raw), Map.of());

    // Assert
    assertThat(chunks).hasSizeGreaterThan(1).hasSizeLessThanOrEqualTo(100);
    assertThat(chunks).allSatisfy(chunk -> {
      assertThat(chunk.getText()).startsWith("Heading: Security\n\n");
      assertThat(tokenCount(chunk.getText())).isLessThanOrEqualTo(20);
      assertThat(chunk.getMetadata())
        .containsEntry(IngestionMetadata.HEADING_PATH, "Security");
      assertThat(chunk.getId()).isNotBlank();
    });
    assertThat(repeated).extracting(Document::getId)
      .containsExactlyElementsOf(chunks.stream().map(Document::getId).toList());
    assertThat(repeated).extracting(Document::getMetadata)
      .containsExactlyElementsOf(chunks.stream().map(Document::getMetadata).toList());
  }

  @Test
  void shouldUseTokenizerAndRetainShortDocuments_whenCharactersUnderHeuristicCanExceedTokens() {
    ProvenanceDocumentTransformer transformer = transformer(10, 100);
    Document adversarial = Document.builder()
      .text("界".repeat(30))
      .metadata(Map.of(IngestionMetadata.HEADING_PATH, "S"))
      .build();
    LoadedDocumentResource resource = resource(
      "classpath:tokens.md", "tokens.md", "界".repeat(30));

    List<Document> chunks = transformer.transform(
      resource, List.of(adversarial, new Document("tiny")), Map.of());

    assertThat(chunks).hasSizeGreaterThan(2);
    assertThat(chunks.stream().filter(chunk -> !chunk.getText().equals("tiny")))
      .allSatisfy(chunk -> {
        assertThat(chunk.getText()).startsWith("Heading: S\n\n");
        assertThat(tokenCount(chunk.getText())).isLessThanOrEqualTo(10);
      });
    assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.getText()).isEqualTo("tiny"));
  }

  @Test
  void shouldRejectReservedCallerMetadata_whenCallerAttemptsToOverrideProvenance() {
    // Arrange
    ProvenanceDocumentTransformer transformer = transformer(800, 100);

    // Act / Assert
    assertThatThrownBy(() -> transformer.transform(
      resource("classpath:policy.md", "policy.md", "content"),
      List.of(new Document("content")),
      Map.of(IngestionMetadata.CHUNK_ID, "caller-value")))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("RESERVED_METADATA_KEY");
  }

  @Test
  void shouldRejectExcessiveChunks_whenSplitOutputExceedsConfiguredMaximum() {
    // Arrange
    ProvenanceDocumentTransformer transformer = transformer(5, 1);
    Document raw = new Document("word ".repeat(100));

    // Act / Assert
    assertThatThrownBy(() -> transformer.transform(
      resource("classpath:large.md", "large.md", raw.getText()),
      List.of(raw), Map.of()))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("TOO_MANY_CHUNKS");
  }

  @Test
  void shouldRejectInvalidProcessingConfiguration_whenEmbeddingModelIsBlank() {
    // Arrange
    DocumentProcessingProperties properties = properties(100, 10);
    properties.setEmbeddingModel(" ");
    ProvenanceDocumentTransformer transformer = new ProvenanceDocumentTransformer(properties);

    // Act / Assert
    assertThatThrownBy(() -> transformer.transform(
      resource("classpath:policy.md", "policy.md", "content"),
      List.of(new Document("content")), Map.of()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Document processing configuration is invalid");
  }

  private ProvenanceDocumentTransformer transformer(int tokens, int maxChunks) {
    return new ProvenanceDocumentTransformer(properties(tokens, maxChunks));
  }

  private DocumentProcessingProperties properties(int tokens, int maxChunks) {
    DocumentProcessingProperties properties = new DocumentProcessingProperties();
    properties.setEmbeddingModel("embedding-test-model");
    properties.getChunking().setTokensPerChunk(tokens);
    properties.getChunking().setMinChunkSizeChars(1);
    properties.getChunking().setMinChunkLengthToEmbed(1);
    properties.getChunking().setMaxNumChunks(maxChunks);
    return properties;
  }

  private LoadedDocumentResource resource(String location, String name, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new LoadedDocumentResource(
      location, name, DocumentFormat.MARKDOWN, bytes, Hashing.sha256(bytes));
  }

  private int tokenCount(String text) {
    return Encodings.newLazyEncodingRegistry()
      .getEncoding(EncodingType.CL100K_BASE)
      .countTokens(text);
  }
}
