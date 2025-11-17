package com.epam.codereviewagent.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.util.StringUtils;

public final class FileUtils {

  public static String readFile(String path) {
    if (!StringUtils.hasText(path)) {
      throw new IllegalArgumentException("Path must not be blank");
    }

    // Normalize incoming value and support common relative patterns
    String trimmed = path.trim();
    if (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1); // treat leading slash as classpath-style relative
    }

    Path cwd = Path.of(System.getProperty("user.dir"));

    Path[] candidates = new Path[] {
      // As passed (relative to working dir)
      cwd.resolve(trimmed).normalize(),
      // Common source roots
      cwd.resolve("src/main/java").resolve(trimmed).normalize(),
      cwd.resolve("src/test/java").resolve(trimmed).normalize(),
      // If user included src/ already, still try raw
      Path.of(trimmed).normalize()
    };

    Path resolved = null;
    for (Path p : candidates) {
      if (Files.exists(p) && Files.isRegularFile(p)) {
        resolved = p;
        break;
      }
    }

    if (resolved == null) {
      throw new IllegalArgumentException("File not found. Tried: " +
        java.util.Arrays.stream(candidates).map(Path::toString).toList());
    }

    try {
      return Files.readString(resolved, StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to read file: " + resolved, ex);
    }
  }

}
