package com.epam.codereviewagent.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsTest {

  private static final String FIXTURE_ROOT = "src/test/resources/fixtures/repo-root";
  private static final Path NESTED_FILE = Path.of(FIXTURE_ROOT, "nested", "nested-file.txt");
  private static final Path EMPTY_FILE = Path.of(FIXTURE_ROOT, "empty.txt");
  private static final Path DIRECTORY_PATH = Path.of(FIXTURE_ROOT, "nested");

  @Test
  void shouldReturnFullContentUntruncated_whenLimitEqualsFileLength() throws IOException {
    String fullContent = Files.readString(NESTED_FILE, StandardCharsets.UTF_8);

    FileUtils.FileReadResult result = FileUtils.readFile(NESTED_FILE, fullContent.length());

    assertThat(result.truncated()).isFalse();
    assertThat(result.content()).isEqualTo(fullContent);
  }

  @Test
  void shouldReturnFullContentUntruncated_whenLimitExceedsFileLength() throws IOException {
    String fullContent = Files.readString(NESTED_FILE, StandardCharsets.UTF_8);

    FileUtils.FileReadResult result = FileUtils.readFile(NESTED_FILE, fullContent.length() + 500);

    assertThat(result.truncated()).isFalse();
    assertThat(result.content()).isEqualTo(fullContent);
  }

  @Test
  void shouldTruncateContentAndAppendMarker_whenLimitIsSmallerThanFileLength() throws IOException {
    String fullContent = Files.readString(NESTED_FILE, StandardCharsets.UTF_8);
    int limit = fullContent.length() - 10;

    FileUtils.FileReadResult result = FileUtils.readFile(NESTED_FILE, limit);

    assertThat(result.truncated()).isTrue();
    assertThat(result.content())
      .isEqualTo(fullContent.substring(0, limit) + FileUtils.TRUNCATION_MARKER);
    assertThat(result.content()).endsWith(FileUtils.TRUNCATION_MARKER);
  }

  @Test
  void shouldReturnEmptyUntruncatedContent_whenFileIsEmpty() {
    FileUtils.FileReadResult result = FileUtils.readFile(EMPTY_FILE, 100);

    assertThat(result.truncated()).isFalse();
    assertThat(result.content()).isEmpty();
  }

  @Test
  void shouldThrowIllegalArgumentException_whenPathIsNull() {
    assertThatThrownBy(() -> FileUtils.readFile(null, 10))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldThrowIllegalArgumentException_whenMaxCharsIsZero() {
    assertThatThrownBy(() -> FileUtils.readFile(NESTED_FILE, 0))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldThrowIllegalArgumentException_whenMaxCharsIsNegative() {
    assertThatThrownBy(() -> FileUtils.readFile(NESTED_FILE, -1))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldThrowIllegalStateException_whenPathCannotBeReadAsAFile() {
    // Empirically verified on this Windows host: opening a directory (even with NOFOLLOW_LINKS, see
    // NoFollowProbe results recorded in context/PROGRESS.md) throws AccessDeniedException (an
    // IOException) rather than succeeding or hanging - FileUtils must wrap any such IOException
    // in an unchecked exception rather than letting it, or a raw NPE, propagate.
    assertThat(Files.isDirectory(DIRECTORY_PATH)).isTrue();

    assertThatThrownBy(() -> FileUtils.readFile(DIRECTORY_PATH, 100))
      .isInstanceOf(IllegalStateException.class);
  }

  // --- Adversarial: surrogate-pair-aware truncation ------------------------------------------

  @Test
  void shouldBackOffCutByOneChar_whenNaiveCutWouldSplitASurrogatePair(@TempDir Path tempDir)
    throws IOException {
    // Reproduces the exact adversarial case from code review: "AB" + a non-BMP emoji (U+1F600,
    // encoded as the surrogate pair U+D83D U+DE00) + "CD", truncated at maxChars = 3 - a raw
    // char-index cut at index 3 would land between the emoji's high and low surrogate, producing
    // a lone high surrogate that UTF-8-encodes to a '?' replacement character.
    String content = "AB" + "😀" + "CD";
    Path file =
      Files.writeString(tempDir.resolve("surrogate-pair.txt"), content, StandardCharsets.UTF_8);

    FileUtils.FileReadResult result = FileUtils.readFile(file, 3);

    assertThat(result.truncated()).isTrue();
    String withoutMarker = withoutTruncationMarker(result.content());
    // Backed off from 3 to 2 chars rather than splitting the pair.
    assertThat(withoutMarker).isEqualTo("AB");
    assertContainsNoUnpairedSurrogate(withoutMarker);
    assertLosslessUtf8RoundTrip(withoutMarker);
  }

  @Test
  void shouldNotBackOffCut_whenCutLandsExactlyBetweenTwoCompleteCodepoints(@TempDir Path tempDir)
    throws IOException {
    String content = "AB" + "😀" + "CD";
    Path file =
      Files.writeString(tempDir.resolve("surrogate-boundary.txt"), content, StandardCharsets.UTF_8);

    // maxChars = 4 lands immediately after the complete surrogate pair (before 'C') - a genuine
    // codepoint boundary, so the cut must not back off further.
    FileUtils.FileReadResult result = FileUtils.readFile(file, 4);

    assertThat(result.truncated()).isTrue();
    String withoutMarker = withoutTruncationMarker(result.content());
    assertThat(withoutMarker).isEqualTo("AB" + "😀");
    assertContainsNoUnpairedSurrogate(withoutMarker);
    assertLosslessUtf8RoundTrip(withoutMarker);
  }

  @Test
  void shouldTruncateToEmptyContent_whenMaxCharsIsOneAndFirstCodepointIsNonBmp(
    @TempDir Path tempDir) throws IOException {
    String content = "😀" + "XY";
    Path file = Files.writeString(
      tempDir.resolve("surrogate-first-char.txt"), content, StandardCharsets.UTF_8);

    FileUtils.FileReadResult result = FileUtils.readFile(file, 1);

    assertThat(result.truncated()).isTrue();
    String withoutMarker = withoutTruncationMarker(result.content());
    // Backed off from 1 to 0 rather than splitting the pair - content is legitimately shorter than
    // maxChars in this case (see FileUtils.readFile Javadoc).
    assertThat(withoutMarker).isEmpty();
    assertContainsNoUnpairedSurrogate(withoutMarker);
  }

  // --- TOCTOU narrowing: reject a symlink at read time (availability-gated, see
  // RepositoryPathResolverTest) ---

  @Test
  void shouldThrowIllegalStateException_whenPathIsASymbolicLinkAtReadTime(@TempDir Path tempDir)
    throws IOException {
    Path target = Files.writeString(tempDir.resolve("target.txt"), "real content");
    Path link = tempDir.resolve("link-to-target.txt");

    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.assumeTrue(false,
        "Symbolic-link creation is unavailable on this host: " + e.getClass().getSimpleName());
      return;
    }

    // FileUtils.readFile opens with LinkOption.NOFOLLOW_LINKS specifically so that if the location
    // RepositoryPathResolver validated is swapped for a symlink before this call runs, the read
    // fails instead of silently following the link - this narrows (does not eliminate) the
    // resolve-then-read TOCTOU window recorded in context/PROGRESS.md. Even a symlink pointing
    // at a perfectly valid file is refused, proving the mechanism itself, independent of where
    // the link points.
    assertThatThrownBy(() -> FileUtils.readFile(link, 100))
      .isInstanceOf(IllegalStateException.class);
  }

  private static String withoutTruncationMarker(String content) {
    assertThat(content).endsWith(FileUtils.TRUNCATION_MARKER);
    return content.substring(0, content.length() - FileUtils.TRUNCATION_MARKER.length());
  }

  private static void assertContainsNoUnpairedSurrogate(String content) {
    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);
      if (Character.isHighSurrogate(c)) {
        assertThat(i + 1).as("high surrogate at index %d must be followed by a low surrogate", i)
          .isLessThan(content.length());
        assertThat(Character.isLowSurrogate(content.charAt(i + 1))).isTrue();
        i++;
      } else {
        assertThat(Character.isLowSurrogate(c))
          .as("char at index %d must not be an unpaired low surrogate", i)
          .isFalse();
      }
    }
  }

  private static void assertLosslessUtf8RoundTrip(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String roundTripped = new String(bytes, StandardCharsets.UTF_8);
    assertThat(roundTripped).isEqualTo(content);
  }
}
