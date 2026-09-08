package com.epam.docqachatbot.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentReaderFactoryTest {

  private final DocumentReaderFactory factory = new DocumentReaderFactory();

  @Test
  void shouldPreserveHeadingHierarchyBulletsAndCode_whenReadingMarkdown() {
    // Arrange
    String markdown = """
      # Security
      Intro.
      ## Input
      - Validate every value
      ```java
      validate(input);
      ```
      """;
    LoadedDocumentResource resource = resource("policy.md", DocumentFormat.MARKDOWN, markdown);

    // Act
    List<Document> documents = factory.create(resource).read();

    // Assert
    assertThat(documents).hasSize(2);
    assertThat(documents.get(1).getMetadata())
      .containsEntry(IngestionMetadata.HEADING_PATH, "Security > Input");
    assertThat(documents.get(1).getText())
      .contains("- Validate every value", "```java", "validate(input);", "```");
  }

  @Test
  void shouldIgnoreHeadingLikeLinesInsideBacktickAndTildeFences_whenReadingMarkdown() {
    String markdown = """
      # Security
      ```text
      # Not a heading
      ```
      ~~~text
      ## Also not a heading
      ~~~
      ## Real heading
      Guidance.
      """;

    List<Document> documents = factory.create(
      resource("policy.md", DocumentFormat.MARKDOWN, markdown)).read();

    assertThat(documents).hasSize(2);
    assertThat(documents.getFirst().getMetadata())
      .containsEntry(IngestionMetadata.HEADING_PATH, "Security");
    assertThat(documents.getFirst().getText())
      .contains("# Not a heading", "## Also not a heading");
    assertThat(documents.get(1).getMetadata())
      .containsEntry(IngestionMetadata.HEADING_PATH, "Security > Real heading");
  }

  @Test
  void shouldReadRepresentativeText_whenTextResourceIsSupported() {
    // Arrange
    LoadedDocumentResource resource = resource(
      "notes.txt", DocumentFormat.TEXT, "Use least privilege.");

    // Act / Assert
    assertThat(factory.create(resource).read())
      .singleElement()
      .extracting(Document::getText)
      .isEqualTo("Use least privilege.");
  }

  @Test
  void shouldReadRepresentativePdf_whenPdfResourceIsSupported() throws Exception {
    // Arrange
    Path pdf = Path.of("src/test/resources/documents/llm_context_document.pdf");
    byte[] content = Files.readAllBytes(pdf);
    LoadedDocumentResource resource = new LoadedDocumentResource(
      "file:" + pdf, pdf.getFileName().toString(), DocumentFormat.PDF,
      content, "fingerprint");

    // Act
    List<Document> documents = factory.create(resource).read();

    // Assert
    assertThat(documents).isNotEmpty();
    assertThat(documents).allSatisfy(document ->
      assertThat(document.getText()).isNotBlank());
  }

  private LoadedDocumentResource resource(String name, DocumentFormat format, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new LoadedDocumentResource(
      "classpath:" + name, name, format, bytes, "fingerprint");
  }
}
