package com.epam.prompting_llm;

import com.epam.prompting_llm.support.DeterministicChatModelConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
  classes = {PromptingLlmApplication.class, DeterministicChatModelConfiguration.class},
  properties = "app.chat.top-p-supported=false",
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ChatApiTopPFallbackIT {

  @LocalServerPort
  private int port;

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void shouldApplyConfiguredTemperatureFallbackWhenTopPIsUnsupported() throws Exception {
    // Arrange
    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create("http://127.0.0.1:" + port + "/chat"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("""
        {"message":"fallback request","conversationId":"fallback-it","topP":0.9}
        """))
      .build();

    // Act
    HttpResponse<String> response = HttpClient.newHttpClient().send(
      request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = objectMapper.readTree(response.body());

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body.path("response").asText())
      .isEqualTo("temperature=0.6;topP=null;maxTokens=null");
  }
}
