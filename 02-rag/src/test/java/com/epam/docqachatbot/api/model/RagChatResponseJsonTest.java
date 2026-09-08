package com.epam.docqachatbot.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagChatResponseJsonTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldSerializeExactResponseContract_whenResponseContainsSources() throws Exception {
    // Arrange
    RagChatResponse response = new RagChatResponse(
      "Use prepared statements.",
      List.of(new RagSource("EPAM_JavaSecureCodingGD.md", "chunk-17"))
    );

    // Act
    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

    // Assert
    assertThat(json.fieldNames()).toIterable()
      .containsExactlyInAnyOrder("response", "sources");
    assertThat(json.path("response").asText()).isEqualTo("Use prepared statements.");
    assertThat(json.path("answer").isMissingNode()).isTrue();
    assertThat(json.path("sources").isArray()).isTrue();
    assertThat(json.path("sources").get(0).fieldNames()).toIterable()
      .containsExactlyInAnyOrder("documentName", "chunkId");
    assertThat(json.path("sources").get(0).path("documentName").asText())
      .isEqualTo("EPAM_JavaSecureCodingGD.md");
    assertThat(json.path("sources").get(0).path("chunkId").asText())
      .isEqualTo("chunk-17");
  }

  @Test
  void shouldProtectSourcesFromExternalMutation_whenResponseIsCreated() {
    // Arrange
    List<RagSource> mutableSources = new ArrayList<>();
    mutableSources.add(new RagSource("policy.md", "chunk-1"));

    // Act
    RagChatResponse response = new RagChatResponse("response", mutableSources);
    mutableSources.clear();

    // Assert
    assertThat(response.sources())
      .containsExactly(new RagSource("policy.md", "chunk-1"));
    assertThat(response.sources()).isUnmodifiable();
  }

  @Test
  void shouldUseEmptyImmutableSources_whenSourcesAreNull() {
    // Act
    RagChatResponse response = new RagChatResponse("response", null);

    // Assert
    assertThat(response.sources()).isEmpty();
    assertThat(response.sources()).isUnmodifiable();
  }
}
