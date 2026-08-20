package com.epam.prompting_llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RawModelResponseCaptureTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @TempDir
  Path temporaryDirectory;

  @Test
  void shouldCaptureRawModelResponseWhenPathIsConfigured() throws Exception {
    // Arrange
    Path capturePath = temporaryDirectory.resolve("raw-response.jsonl");
    RawModelResponseCapture capture = new RawModelResponseCapture(capturePath.toString());

    // Act
    ChatResponse modelResponse = new ChatResponse(
      List.of(new Generation(
        new AssistantMessage("not valid structured JSON"),
        ChatGenerationMetadata.builder().finishReason("length").build())),
      ChatResponseMetadata.builder()
        .usage(new DefaultUsage(10, 20, 30, Map.of("reasoningTokens", 20)))
        .build());

    capture.capture("conversation-1", "not valid structured JSON", modelResponse);

    // Assert
    JsonNode captured = OBJECT_MAPPER.readTree(Files.readString(capturePath));
    assertThat(captured.path("conversationId").asText()).isEqualTo("conversation-1");
    assertThat(captured.path("rawModelResponse").asText())
      .isEqualTo("not valid structured JSON");
    assertThat(captured.path("finishReason").asText()).isEqualTo("length");
    assertThat(captured.path("usage").path("promptTokens").asInt()).isEqualTo(10);
    assertThat(captured.path("usage").path("completionTokens").asInt()).isEqualTo(20);
    assertThat(captured.path("usage").path("totalTokens").asInt()).isEqualTo(30);
    assertThat(captured.path("usage").path("nativeUsage").path("reasoningTokens").asInt())
      .isEqualTo(20);
  }

  @Test
  void shouldNotCreateCaptureFileWhenPathIsNotConfigured() {
    // Arrange
    RawModelResponseCapture capture = new RawModelResponseCapture("");

    // Act
    capture.capture("conversation-1", "raw response", null);

    // Assert
    assertThat(temporaryDirectory).isEmptyDirectory();
  }

  @Test
  void shouldCaptureMissingRawModelResponse() throws Exception {
    // Arrange
    Path capturePath = temporaryDirectory.resolve("missing-response.jsonl");
    RawModelResponseCapture capture = new RawModelResponseCapture(capturePath.toString());

    // Act
    capture.capture("conversation-1", null, null);

    // Assert
    JsonNode captured = OBJECT_MAPPER.readTree(Files.readString(capturePath));
    assertThat(captured.path("conversationId").asText()).isEqualTo("conversation-1");
    assertThat(captured.path("rawModelResponse").isNull()).isTrue();
    assertThat(captured.path("finishReason").isNull()).isTrue();
    assertThat(captured.path("usage").isNull()).isTrue();
  }
}
