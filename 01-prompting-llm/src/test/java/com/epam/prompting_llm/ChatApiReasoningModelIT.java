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
  properties = "app.chat.model-name=gpt-5.4-mini-2026-03-17",
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ChatApiReasoningModelIT {

  private static final String ERROR_MESSAGE =
    "The selected reasoning model does not support temperature or topP";

  @LocalServerPort
  private int port;

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void shouldRejectUnsupportedSamplingParametersWhenReasoningModelIsSelected()
    throws Exception {
    // Act
    ApiResponse temperatureResponse = post("""
      {"message":"temperature request","temperature":0.7}
      """);
    ApiResponse topPResponse = post("""
      {"message":"topP request","topP":0.9}
      """);

    // Assert
    assertThat(temperatureResponse.statusCode()).isEqualTo(400);
    assertThat(temperatureResponse.body().path("message").asText()).isEqualTo(ERROR_MESSAGE);
    assertThat(topPResponse.statusCode()).isEqualTo(400);
    assertThat(topPResponse.body().path("message").asText()).isEqualTo(ERROR_MESSAGE);
  }

  private ApiResponse post(String json) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create("http://127.0.0.1:" + port + "/chat"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json))
      .build();
    HttpResponse<String> response = HttpClient.newHttpClient().send(
      request, HttpResponse.BodyHandlers.ofString());
    return new ApiResponse(response.statusCode(), objectMapper.readTree(response.body()));
  }

  private record ApiResponse(int statusCode, JsonNode body) {
  }
}
