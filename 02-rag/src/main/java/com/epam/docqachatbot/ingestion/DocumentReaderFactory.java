package com.epam.docqachatbot.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class DocumentReaderFactory {

  public DocumentReader create(LoadedDocumentResource resource) {
    return switch (resource.format()) {
      case MARKDOWN -> new MarkdownSectionDocumentReader(resource.content());
      case TEXT -> () -> List.of(Document.builder()
        .text(new String(resource.content(), StandardCharsets.UTF_8))
        .metadata(Map.of(IngestionMetadata.HEADING_PATH, ""))
        .build());
      case PDF -> new PagePdfDocumentReader(
        resource.asResource(),
        PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
    };
  }
}
