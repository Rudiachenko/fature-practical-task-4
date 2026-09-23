package com.epam.codereviewagent.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * A bounded UTF-8 file reader operating only on a {@link Path} that the caller has already
 * validated against the repository-root security boundary (see {@link RepositoryPathResolver}).
 * This class knows nothing about path resolution, working-directory guessing, or candidate
 * roots - that responsibility belongs entirely to {@link RepositoryPathResolver}.
 *
 * <p><strong>TOCTOU note:</strong> {@code RepositoryPathResolver.resolveFile(...)} proves
 * containment via {@code toRealPath()} and returns a plain {@link Path}; the actual read happens
 * later, in a separate call to {@link #readFile(Path, int)}. This class narrows (but, given the
 * two-call split, cannot fully eliminate) the resulting race window by opening with
 * {@link LinkOption#NOFOLLOW_LINKS}: if the resolved location is replaced by a symbolic link
 * between the two calls, the read fails instead of silently following the link outside the
 * repository root. See {@code context/PROGRESS.md}'s "Recorded, considered gaps" for the full
 * accepted-risk statement.</p>
 */
public final class FileUtils {

  /**
   * Appended, visibly, to content that was cut short because it exceeded the configured character
   * limit.
   */
  public static final String TRUNCATION_MARKER =
    "\n... [TRUNCATED: content exceeds the configured character limit]";

  /**
   * Prefix used by callers (see {@code CodeReviewTools}) to build a deterministic, model-readable
   * error message instead of letting an exception escape a tool method.
   */
  public static final String READ_ERROR_PREFIX = "ERROR: ";

  private FileUtils() {
  }

  /**
   * Reads the UTF-8 text content of {@code path}, truncating to {@code maxChars} characters if
   * necessary. The truncation cut point is codepoint-aware: if a raw {@code maxChars}-character cut
   * would split a surrogate pair in two, the cut backs off by one character so the returned content
   * never ends in an unpaired (lone) surrogate. As a deliberate consequence, the returned
   * content's length may be {@code maxChars - 1} rather than exactly {@code maxChars} in that
   * specific case; a cut that already lands on a codepoint boundary is never backed off.
   *
   * @param path     an already-resolved, already-validated path to a regular file
   * @param maxChars the maximum number of characters to return before truncation (must be positive)
   * @return the file content (truncated, with {@link #TRUNCATION_MARKER} appended, if it exceeded
   *         {@code maxChars}) and whether truncation occurred
   * @throws IllegalArgumentException if {@code path} is {@code null} or {@code maxChars} is not
   *                                   positive
   * @throws IllegalStateException    if the file could not be read
   */
  public static FileReadResult readFile(Path path, int maxChars) {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be positive");
    }

    String content;
    try {
      content = readUtf8StrictNoFollowLinks(path);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read file: " + path, e);
    }

    if (content.length() > maxChars) {
      int cutLength = codepointSafeCutLength(content, maxChars);
      return new FileReadResult(content.substring(0, cutLength) + TRUNCATION_MARKER, true);
    }
    return new FileReadResult(content, false);
  }

  /**
   * Reads {@code path} as strict UTF-8 (malformed/unmappable input throws, matching the previous
   * {@code Files.readString} behavior), opened with {@link LinkOption#NOFOLLOW_LINKS} so that a
   * symbolic link planted at this exact location after {@code RepositoryPathResolver} validated
   * it is refused rather than silently followed (see the class-level TOCTOU note).
   */
  private static String readUtf8StrictNoFollowLinks(Path path) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      bytes = in.readAllBytes();
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT);
    return decoder.decode(ByteBuffer.wrap(bytes)).toString();
  }

  /**
   * Returns {@code maxChars}, unless a cut at that exact index would fall between the two
   * {@code char}s of a surrogate pair (i.e., {@code content.charAt(maxChars - 1)} is a high
   * surrogate), in which case it returns {@code maxChars - 1} so the cut lands before the pair
   * instead of inside it. A cut that already lands on a codepoint boundary is returned unchanged.
   */
  private static int codepointSafeCutLength(String content, int maxChars) {
    if (maxChars < content.length() && Character.isHighSurrogate(content.charAt(maxChars - 1))) {
      return maxChars - 1;
    }
    return maxChars;
  }

  /**
   * The result of a bounded file read.
   *
   * @param content   the (possibly truncated) file content
   * @param truncated {@code true} if {@code content} was cut short of the file's actual length
   */
  public record FileReadResult(String content, boolean truncated) {
  }
}
