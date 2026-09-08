package com.epam.docqachatbot.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFormatTest {

  @Test
  void shouldResolveSupportedFormat_whenExtensionIsAllowlisted() {
    assertThat(DocumentFormat.fromLocation("classpath:policy.MD"))
      .isEqualTo(DocumentFormat.MARKDOWN);
    assertThat(DocumentFormat.fromLocation("file:notes.markdown"))
      .isEqualTo(DocumentFormat.MARKDOWN);
    assertThat(DocumentFormat.fromLocation("classpath:notes.txt"))
      .isEqualTo(DocumentFormat.TEXT);
    assertThat(DocumentFormat.fromLocation("classpath:policy.PDF"))
      .isEqualTo(DocumentFormat.PDF);
  }

  @Test
  void shouldRejectDocumentType_whenExtensionIsNotAllowlisted() {
    assertThatThrownBy(() -> DocumentFormat.fromLocation("classpath:policy.html"))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo("UNSUPPORTED_DOCUMENT_TYPE");
  }
}
