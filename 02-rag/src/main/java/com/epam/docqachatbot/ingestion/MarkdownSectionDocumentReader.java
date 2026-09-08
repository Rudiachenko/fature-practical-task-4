package com.epam.docqachatbot.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownSectionDocumentReader implements DocumentReader {

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
  private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");

  private final byte[] content;

  public MarkdownSectionDocumentReader(byte[] content) {
    this.content = content.clone();
  }

  @Override
  public List<Document> get() {
    List<Document> sections = new ArrayList<>();
    List<String> headings = new ArrayList<>();
    StringBuilder body = new StringBuilder();
    char fenceCharacter = 0;
    int fenceLength = 0;

    for (String line : new String(content, StandardCharsets.UTF_8).split("\\R", -1)) {
      Matcher fence = FENCE.matcher(line);
      if (fenceCharacter != 0) {
        body.append(line).append('\n');
        if (fence.matches() && fence.group(1).charAt(0) == fenceCharacter
          && fence.group(1).length() >= fenceLength && fence.group(2).isBlank()) {
          fenceCharacter = 0;
          fenceLength = 0;
        }
        continue;
      }
      if (fence.matches()) {
        fenceCharacter = fence.group(1).charAt(0);
        fenceLength = fence.group(1).length();
        body.append(line).append('\n');
        continue;
      }
      Matcher matcher = HEADING.matcher(line);
      if (matcher.matches()) {
        addSection(sections, headings, body);
        int level = matcher.group(1).length();
        while (headings.size() >= level) {
          headings.removeLast();
        }
        while (headings.size() < level - 1) {
          headings.add("");
        }
        headings.add(matcher.group(2).strip());
        body.append(line).append('\n');
      } else {
        body.append(line).append('\n');
      }
    }
    addSection(sections, headings, body);
    return sections;
  }

  private void addSection(List<Document> sections, List<String> headings, StringBuilder body) {
    String text = body.toString().strip();
    body.setLength(0);
    if (text.isBlank() || isHeadingOnly(text)) {
      return;
    }
    String headingPath = headings.stream()
      .filter(heading -> !heading.isBlank())
      .collect(java.util.stream.Collectors.joining(" > "));
    sections.add(Document.builder()
      .text(text)
      .metadata(Map.of(IngestionMetadata.HEADING_PATH, headingPath))
      .build());
  }

  /**
   * True when a section's accumulated body is nothing but the heading line that started it (e.g.
   * an "H1" immediately followed by its first "H2" child, with no introductory paragraph between
   * them). Such a section carries no retrievable content of its own: the same heading text is
   * already present as the {@link IngestionMetadata#HEADING_PATH} breadcrumb on every substantive
   * child chunk, so dropping it loses no information while freeing a retrieval slot that a
   * heading-only chunk would otherwise occupy for nothing.
   */
  private boolean isHeadingOnly(String text) {
    String[] parts = text.split("\\R", 2);
    if (parts.length == 1) {
      return HEADING.matcher(parts[0]).matches();
    }
    return HEADING.matcher(parts[0]).matches() && parts[1].strip().isEmpty();
  }
}
