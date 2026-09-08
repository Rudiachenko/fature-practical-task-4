package com.epam.docqachatbot.ingestion;

import java.util.Set;

public final class IngestionMetadata {

  public static final String DOCUMENT_NAME = "documentName";
  public static final String SOURCE = "source";
  public static final String DOCUMENT_ID = "documentId";
  public static final String FINGERPRINT = "fingerprint";
  public static final String DOCUMENT_TYPE = "documentType";
  public static final String CHUNK_INDEX = "chunkIndex";
  public static final String CHUNK_ID = "chunkId";
  public static final String HEADING_PATH = "headingPath";
  public static final String EMBEDDING_MODEL = "embeddingModel";

  public static final Set<String> RESERVED_KEYS = Set.of(
    DOCUMENT_NAME,
    SOURCE,
    DOCUMENT_ID,
    FINGERPRINT,
    DOCUMENT_TYPE,
    CHUNK_INDEX,
    CHUNK_ID,
    HEADING_PATH,
    EMBEDDING_MODEL
  );

  /**
   * Characters that are hostile to a {@code documentName} value on either supported {@link
   * org.springframework.ai.vectorstore.VectorStore} filter-expression converter: every character
   * that is either a string-literal delimiter or an escape-introducer for either known {@code
   * Filter.Expression} String-value converter. {@code SimpleVectorStore}'s internal SpEL-based
   * converter wraps a value in unescaped single quotes, while production's {@code
   * ChromaVectorStore} (inherited {@code AbstractFilterExpressionConverter}) wraps a value in
   * unescaped double quotes for its Chroma {@code where} JSON, whose output is then really parsed
   * by a stock Jackson {@code ObjectMapper} in {@code ChromaVectorStore.doSimilaritySearch}/{@code
   * doDelete}. Neither converter escapes an embedded delimiter, so {@code '} and {@code "} can
   * break a value out of its literal on either path. {@code \} is hostile for a distinct reason:
   * it is JSON's escape-introducer. It is inert on the SpEL path (SpEL escapes a quote only by
   * doubling it, never with a backslash), but on the Chroma/JSON path an unescaped {@code \}
   * either produces an invalid escape sequence ({@code JsonParseException}/{@code
   * JsonEOFException}, an opaque HTTP 500) or silently consumes the following character as part of
   * a syntactically valid escape (e.g. {@code \n}), corrupting the value.
   *
   * <p>This is the single source of truth for that character set, shared by two independent
   * defenses: {@link #sanitizeDocumentName(String)} replaces every occurrence at ingestion time so
   * a legitimately-ingested {@code documentName} is always filter-safe, and {@code
   * DocumentIngestionService}'s delete-path validation separately rejects any of these characters
   * in a {@code documentName} value supplied directly in a delete request, as defense-in-depth.
   *
   * <p>Raw control characters (see {@link #isFilterHostileCharacter(char)}) are handled
   * separately by a range check rather than being added to this fixed set.
   */
  public static final Set<Character> FILTER_HOSTILE_DOCUMENT_NAME_CHARACTERS =
    Set.of('\'', '"', '\\');

  private IngestionMetadata() {
  }

  /**
   * True for every character {@link #FILTER_HOSTILE_DOCUMENT_NAME_CHARACTERS} names plus any
   * control character ({@link Character#isISOControl(char)}: {@code 0x00-0x1F} and {@code
   * 0x7F-0x9F}). A literal control character embedded in a {@code documentName} would likewise
   * fail Jackson's strict JSON parsing on the Chroma filter path, since JSON requires it to be
   * escaped; this closes that previously-deferred gap with the same range check its own prior
   * Javadoc anticipated.
   */
  public static boolean isFilterHostileCharacter(char character) {
    return FILTER_HOSTILE_DOCUMENT_NAME_CHARACTERS.contains(character)
      || Character.isISOControl(character);
  }

  /**
   * True if any character in {@code value} is {@linkplain #isFilterHostileCharacter(char)
   * filter-hostile}.
   */
  public static boolean containsFilterHostileCharacter(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (isFilterHostileCharacter(value.charAt(index))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Replaces every {@linkplain #isFilterHostileCharacter(char) filter-hostile} character in
   * {@code raw} with {@code _}, so the result can never trigger a delete-path {@code
   * INVALID_DELETE_SELECTOR} rejection. Deterministic (the same input always produces the same
   * output) and idempotent (sanitizing an already-sanitized value returns it unchanged, since
   * {@code _} is not itself hostile). {@code null} is returned unchanged.
   *
   * <p>Accepted limitation: two different original names that sanitize to the same value (e.g.
   * {@code "report's.md"} and {@code "report_s.md"}) collide onto one {@code documentName}.
   */
  public static String sanitizeDocumentName(String raw) {
    if (raw == null) {
      return null;
    }
    StringBuilder sanitized = new StringBuilder(raw.length());
    for (int index = 0; index < raw.length(); index++) {
      char character = raw.charAt(index);
      sanitized.append(isFilterHostileCharacter(character) ? '_' : character);
    }
    return sanitized.toString();
  }
}
