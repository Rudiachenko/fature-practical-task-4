package com.epam.codereviewagent.service;

import com.epam.codereviewagent.config.ConventionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConventionService {

  private final ConventionProperties properties;
  private final Map<String, String> conventions = new HashMap<>();

  @EventListener(ApplicationReadyEvent.class)
  public void loadConventions() {
    List<Resource> resources = properties.getResources();
    log.info("Application ready - Loading coding conventions from {} resources", resources.size());

    for (Resource resource : resources) {
      try {
        String content = StreamUtils.copyToString(resource.getInputStream(),
          StandardCharsets.UTF_8);
        String filename = resource.getFilename();

        if (Objects.nonNull(filename)) {
          // Extract language from filename (e.g., "java-convention.md" -> "java")
          String language = filename.split("-")[0].toLowerCase();
          conventions.put(language, content);
          log.info("Loaded {} convention ({} characters)", language, content.length());
        }
      } catch (IOException e) {
        log.error("Failed to load convention from {}", resource.getFilename(), e);
      }
    }

    log.info("Conventions loaded successfully: {}", conventions.keySet());
  }

  /**
   * Retrieve coding convention for a specific programming language
   *
   * @param language Programming language (e.g., "java", "python")
   * @return Convention text or message if not found
   */
  public String getConvention(String language) {
    String normalizedLanguage = language.toLowerCase().trim();
    String convention = conventions.get(normalizedLanguage);

    if (Objects.nonNull(convention)) {
      return convention;
    }

    log.warn("No convention found for language: {}", language);
    return String.format("No coding convention found for language: %s. Available conventions: %s",
      language, conventions.keySet());
  }

  /**
   * Get all available language conventions
   */
  public Map<String, String> getAllConventions() {
    return Map.copyOf(conventions);
  }

  /**
   * Get list of supported languages
   */
  public List<String> getSupportedLanguages() {
    return List.copyOf(conventions.keySet());
  }
}

