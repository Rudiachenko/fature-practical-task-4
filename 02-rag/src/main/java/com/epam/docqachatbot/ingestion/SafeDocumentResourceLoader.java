package com.epam.docqachatbot.ingestion;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

@Component
public class SafeDocumentResourceLoader {

  private final ResourceLoader resourceLoader;
  private final DocumentProcessingProperties properties;

  public SafeDocumentResourceLoader(ResourceLoader resourceLoader,
                                    DocumentProcessingProperties properties) {
    this.resourceLoader = resourceLoader;
    this.properties = properties;
  }

  public LoadedDocumentResource load(String location) {
    Resource resource = resolveResource(location);
    DocumentFormat format = DocumentFormat.fromLocation(location);
    if (!resource.exists() || !resource.isReadable()) {
      throw new DocumentIngestionException("DOCUMENT_NOT_FOUND", "Document resource was not found");
    }
    byte[] content = readBounded(resource, properties.getMaxResourceBytes());
    String documentName = resource.getFilename();
    if (documentName == null || documentName.isBlank()) {
      documentName = location.substring(location.lastIndexOf('/') + 1);
    }
    documentName = IngestionMetadata.sanitizeDocumentName(documentName);
    return new LoadedDocumentResource(
      location, documentName, format, content, Hashing.sha256(content));
  }

  private Resource resolveResource(String location) {
    if (location == null || location.isBlank()) {
      throw new DocumentIngestionException("INVALID_DOCUMENT_LOCATION",
        "Document location must not be blank");
    }
    if (location.startsWith("classpath:")) {
      String path = location.substring("classpath:".length()).replace('\\', '/');
      if (path.isBlank() || path.contains("../") || path.startsWith("../")) {
        throw new DocumentIngestionException("UNSAFE_DOCUMENT_LOCATION",
          "Document location is not allowed");
      }
      return resourceLoader.getResource(location);
    }
    if (!location.startsWith("file:")) {
      throw new DocumentIngestionException("UNSAFE_DOCUMENT_SCHEME",
        "Only classpath and configured file resources are allowed");
    }
    return new FileSystemResource(resolveFileLocation(location));
  }

  private Path resolveFileLocation(String location) {
    try {
      URI uri = URI.create(location);
      Path candidate = (uri.isOpaque()
        ? Path.of(uri.getSchemeSpecificPart())
        : Path.of(uri)).toAbsolutePath().normalize();
      Path resolvedCandidate = resolveAgainstRealAncestor(candidate);
      List<Path> allowedRoots = properties.getAllowedFileRoots().stream()
        .map(Path::of)
        .map(Path::toAbsolutePath)
        .map(Path::normalize)
        .map(this::realPathIfPresent)
        .toList();
      if (allowedRoots.stream().noneMatch(resolvedCandidate::startsWith)) {
        throw new DocumentIngestionException("UNSAFE_DOCUMENT_LOCATION",
          "Document location is outside configured roots");
      }
      return resolvedCandidate;
    } catch (IllegalArgumentException | IOException exception) {
      throw new DocumentIngestionException("INVALID_DOCUMENT_LOCATION",
        "Document location is invalid", exception);
    }
  }

  private Path realPathIfPresent(Path path) {
    if (!Files.exists(path)) {
      return path;
    }
    try {
      return path.toRealPath();
    } catch (IOException exception) {
      throw new DocumentIngestionException("INVALID_DOCUMENT_LOCATION",
        "Configured document root is not readable", exception);
    }
  }

  private Path resolveAgainstRealAncestor(Path path) throws IOException {
    if (Files.exists(path)) {
      return path.toRealPath();
    }
    Path ancestor = path.getParent();
    while (ancestor != null && !Files.exists(ancestor)) {
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) {
      return path;
    }
    return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
  }

  private byte[] readBounded(Resource resource, long maximumBytes) {
    if (maximumBytes <= 0 || maximumBytes > Integer.MAX_VALUE) {
      throw new IllegalStateException("maxResourceBytes must be between 1 and 2147483647");
    }
    try (InputStream input = resource.getInputStream();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total += read;
        if (total > maximumBytes) {
          throw new DocumentIngestionException("DOCUMENT_TOO_LARGE",
            "Document exceeds the configured size limit");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (IOException exception) {
      throw new DocumentIngestionException("DOCUMENT_READ_FAILED",
        "Document could not be read", exception);
    }
  }
}
