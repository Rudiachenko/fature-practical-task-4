package com.epam.prompting_llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optionally appends raw model output and provider metadata as JSON lines for live evaluations.
 */
@Slf4j
@Component
public class RawModelResponseCapture {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final Path capturePath;

  public RawModelResponseCapture(
    @Value("${app.chat.raw-response-capture-path:}") String capturePath) {
    this.capturePath = StringUtils.hasText(capturePath) ? Path.of(capturePath) : null;
  }

  public void capture(String conversationId, String content, ChatResponse modelResponse) {
    if (capturePath == null) {
      return;
    }

    try {
      Path parent = capturePath.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Map<String, Object> capture = new LinkedHashMap<>();
      capture.put("conversationId", conversationId);
      capture.put("rawModelResponse", content);
      capture.put("finishReason", finishReason(modelResponse));
      capture.put("usage", usage(modelResponse));
      String json = OBJECT_MAPPER.writeValueAsString(capture);
      Files.writeString(capturePath, json + System.lineSeparator(),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException exception) {
      log.warn("Raw model response capture failed, type={}", exception.getClass().getName());
    }
  }

  private String finishReason(ChatResponse modelResponse) {
    if (modelResponse == null || modelResponse.getResults().isEmpty()) {
      return null;
    }

    Generation generation = modelResponse.getResult();
    return generation.getMetadata().getFinishReason();
  }

  private Map<String, Object> usage(ChatResponse modelResponse) {
    if (modelResponse == null) {
      return null;
    }

    ChatResponseMetadata metadata = modelResponse.getMetadata();
    if (metadata.getUsage() == null) {
      return null;
    }

    Usage modelUsage = metadata.getUsage();
    Map<String, Object> capturedUsage = new LinkedHashMap<>();
    capturedUsage.put("promptTokens", modelUsage.getPromptTokens());
    capturedUsage.put("completionTokens", modelUsage.getCompletionTokens());
    capturedUsage.put("totalTokens", modelUsage.getTotalTokens());
    capturedUsage.put("nativeUsage", modelUsage.getNativeUsage());
    return capturedUsage;
  }
}
