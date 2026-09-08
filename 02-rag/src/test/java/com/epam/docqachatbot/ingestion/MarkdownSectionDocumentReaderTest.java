package com.epam.docqachatbot.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSectionDocumentReaderTest {

  @Test
  void shouldDropHeadingOnlySection_whenAParentHeadingHasNoBodyBeforeItsFirstChild() {
    // Arrange: "# Top" is immediately followed by "## Child" with no introductory paragraph
    // between them, exactly the shape that produced heading-only chunks in the real corpus (e.g.
    // "# 2 SECURE CODING GUIDELINES" directly followed by "## 2.1 CORE").
    String markdown = """
      # Top
      ## Child
      Body text.
      """;

    // Act
    List<Document> documents = read(markdown);

    // Assert: only the substantive child section is retained; the parent contributes no chunk of
    // its own but its heading still appears in the child's breadcrumb.
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getMetadata())
      .containsEntry(IngestionMetadata.HEADING_PATH, "Top > Child");
    assertThat(documents.getFirst().getText()).contains("Body text.");
  }

  @Test
  void shouldDropHeadingOnlyTrailingSection_whenDocumentEndsRightAfterAHeading() {
    // Arrange: the document's very last section is a heading with nothing after it.
    String markdown = """
      # Top
      Intro.
      ## Trailing
      """;

    // Act
    List<Document> documents = read(markdown);

    // Assert
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getText()).contains("Intro.");
  }

  @Test
  void shouldKeepSection_whenHeadingIsFollowedByOnlyWhitespaceThenSubstantiveChild() {
    // Arrange: blank lines between a heading and its first child must not be mistaken for body
    // content, but must also not break the heading-only detection.
    String markdown = """
      # Top


      ## Child
      Guidance.
      """;

    // Act
    List<Document> documents = read(markdown);

    // Assert
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getMetadata())
      .containsEntry(IngestionMetadata.HEADING_PATH, "Top > Child");
  }

  @Test
  void shouldKeepSection_whenHeadingHasItsOwnIntroductoryParagraph() {
    // Arrange: a heading with real body content must never be dropped, even though it is later
    // followed by a child heading.
    String markdown = """
      # Top
      This paragraph belongs to Top.
      ## Child
      Guidance.
      """;

    // Act
    List<Document> documents = read(markdown);

    // Assert: both the parent (with its own paragraph) and the child are retained.
    assertThat(documents).hasSize(2);
    assertThat(documents.get(0).getText()).contains("This paragraph belongs to Top.");
    assertThat(documents.get(1).getMetadata())
      .containsEntry(IngestionMetadata.HEADING_PATH, "Top > Child");
  }

  @Test
  void shouldKeepFencedHeadingLikeLineAsBody_whenSectionAlsoContainsOtherFenceLines() {
    // Arrange: a fenced code block containing a line that looks like a heading must not be
    // mistaken for a real, section-ending heading-only body - the fence lines around it are real
    // content, so the section must be kept in full.
    String markdown = """
      # Top
      ```text
      # Not a heading
      ```
      """;

    // Act
    List<Document> documents = read(markdown);

    // Assert
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getText()).contains("# Not a heading", "```text");
  }

  private List<Document> read(String markdown) {
    return new MarkdownSectionDocumentReader(markdown.getBytes(StandardCharsets.UTF_8)).get();
  }
}
