package com.epam.docqachatbot.ingestion;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeDocumentResourceLoaderTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void shouldLoadFileWithinAllowedRoot_whenResourceIsBoundedAndSupported() throws IOException {
    // Arrange
    Path resource = Files.writeString(temporaryDirectory.resolve("notes.txt"), "secure notes");
    SafeDocumentResourceLoader loader = loader(1024, List.of(temporaryDirectory.toString()));

    // Act
    LoadedDocumentResource loaded = loader.load(resource.toUri().toString());

    // Assert
    assertThat(loaded.documentName()).isEqualTo("notes.txt");
    assertThat(loaded.format()).isEqualTo(DocumentFormat.TEXT);
    assertThat(new String(loaded.content(), java.nio.charset.StandardCharsets.UTF_8))
      .isEqualTo("secure notes");
    assertThat(loaded.fingerprint()).hasSize(64);
  }

  @Test
  void shouldRejectUnsafeOrUnsupportedLocation_whenSchemeOrPathIsNotAllowed()
    throws IOException {
    // Arrange
    Path allowed = Files.createDirectory(temporaryDirectory.resolve("allowed"));
    Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "x");
    SafeDocumentResourceLoader loader = loader(1024, List.of(allowed.toString()));

    // Act / Assert
    assertCode(loader, "https://example.com/policy.md", "UNSAFE_DOCUMENT_SCHEME");
    assertCode(loader, "classpath:../policy.md", "UNSAFE_DOCUMENT_LOCATION");
    assertCode(loader, outside.toUri().toString(), "UNSAFE_DOCUMENT_LOCATION");
    assertCode(loader, "classpath:policy.html", "UNSUPPORTED_DOCUMENT_TYPE");
  }

  @Test
  void shouldRejectMissingOrOversizedResource_whenResourceCannotBeSafelyRead()
    throws IOException {
    // Arrange
    Path oversized = Files.writeString(temporaryDirectory.resolve("large.txt"), "12345");
    SafeDocumentResourceLoader loader = loader(4, List.of(temporaryDirectory.toString()));

    // Act / Assert
    assertCode(loader, temporaryDirectory.resolve("missing.txt").toUri().toString(),
      "DOCUMENT_NOT_FOUND");
    assertCode(loader, oversized.toUri().toString(), "DOCUMENT_TOO_LARGE");
  }

  @Test
  void shouldRejectInvalidSizeConfiguration_whenMaximumIsNotPositive() throws IOException {
    // Arrange
    Path resource = Files.writeString(temporaryDirectory.resolve("notes.txt"), "x");
    SafeDocumentResourceLoader loader = loader(0, List.of(temporaryDirectory.toString()));

    // Act / Assert
    assertThatThrownBy(() -> loader.load(resource.toUri().toString()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("maxResourceBytes must be between 1 and 2147483647");
  }

  @Test
  void shouldRejectSymlinkEscape_whenLinkTargetsFileOutsideAllowedRoot() throws IOException {
    Path allowed = Files.createDirectory(temporaryDirectory.resolve("allowed"));
    Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "secret");
    Path link = allowed.resolve("linked.txt");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | IOException | SecurityException exception) {
      Assumptions.assumeTrue(false,
        "Symbolic-link creation is unavailable: " + exception.getClass().getSimpleName());
    }

    assertCode(loader(1024, List.of(allowed.toString())), link.toUri().toString(),
      "UNSAFE_DOCUMENT_LOCATION");
  }

  @Test
  void shouldLoadFileWithinAllowedRoot_whenLocationIsRelativeOpaqueFileUri() throws IOException {
    // Arrange
    Path workingDirectory = Path.of("").toAbsolutePath();
    String relativeDirectoryName = "opaque-uri-fixture-" + UUID.randomUUID();
    Path absoluteDirectory = workingDirectory.resolve(relativeDirectoryName);
    Files.createDirectory(absoluteDirectory);
    try {
      Files.writeString(absoluteDirectory.resolve("notes.txt"), "secure notes");
      SafeDocumentResourceLoader loader = loader(1024, List.of(absoluteDirectory.toString()));

      // Act
      LoadedDocumentResource loaded = loader.load("file:" + relativeDirectoryName + "/notes.txt");

      // Assert
      assertThat(loaded.documentName()).isEqualTo("notes.txt");
      assertThat(loaded.format()).isEqualTo(DocumentFormat.TEXT);
      assertThat(new String(loaded.content(), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("secure notes");
    } finally {
      Files.deleteIfExists(absoluteDirectory.resolve("notes.txt"));
      Files.deleteIfExists(absoluteDirectory);
    }
  }

  @Test
  void shouldSanitizeDocumentName_whenFileNameContainsAHostileApostrophe() throws IOException {
    // Arrange: a legitimate, Windows-legal filename containing a hostile character.
    Path resource = Files.writeString(
      temporaryDirectory.resolve("O'Brien_report.md"), "policy notes for O'Brien");
    SafeDocumentResourceLoader loader = loader(1024, List.of(temporaryDirectory.toString()));

    // Act
    LoadedDocumentResource first = loader.load(resource.toUri().toString());
    LoadedDocumentResource second = loader.load(resource.toUri().toString());

    // Assert: sanitized, no hostile character remains, and stable across repeated loads.
    assertThat(first.documentName()).isEqualTo("O_Brien_report.md");
    assertThat(first.documentName()).doesNotContain("'", "\"", "\\");
    assertThat(second.documentName()).isEqualTo(first.documentName());
  }

  @Test
  void shouldRejectOpaqueRelativeLocation_whenPathEscapesAllowedRoot() throws IOException {
    // Arrange
    Path workingDirectory = Path.of("").toAbsolutePath();
    String relativeDirectoryName = "opaque-uri-escape-" + UUID.randomUUID();
    Path absoluteDirectory = workingDirectory.resolve(relativeDirectoryName);
    Path allowedSubdirectory = absoluteDirectory.resolve("allowed");
    Files.createDirectories(allowedSubdirectory);
    try {
      Files.writeString(absoluteDirectory.resolve("outside.txt"), "secret");
      SafeDocumentResourceLoader loader = loader(1024, List.of(allowedSubdirectory.toString()));

      // Act / Assert
      assertCode(loader, "file:" + relativeDirectoryName + "/outside.txt",
        "UNSAFE_DOCUMENT_LOCATION");
      assertCode(loader, "file:" + relativeDirectoryName + "/allowed/../outside.txt",
        "UNSAFE_DOCUMENT_LOCATION");
    } finally {
      Files.deleteIfExists(absoluteDirectory.resolve("outside.txt"));
      Files.deleteIfExists(allowedSubdirectory);
      Files.deleteIfExists(absoluteDirectory);
    }
  }

  private SafeDocumentResourceLoader loader(long maxBytes, List<String> allowedRoots) {
    DocumentProcessingProperties properties = new DocumentProcessingProperties();
    properties.setMaxResourceBytes(maxBytes);
    properties.setAllowedFileRoots(allowedRoots);
    return new SafeDocumentResourceLoader(new DefaultResourceLoader(), properties);
  }

  private void assertCode(SafeDocumentResourceLoader loader, String location, String code) {
    assertThatThrownBy(() -> loader.load(location))
      .isInstanceOf(DocumentIngestionException.class)
      .extracting(exception -> ((DocumentIngestionException) exception).code())
      .isEqualTo(code);
  }
}
