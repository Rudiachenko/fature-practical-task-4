package com.epam.prompting_llm.config;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DialOpenAiClientBuilderCustomizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldRegisterPolicyOnOpenAiClientBuilder() throws Exception {
    // Arrange
    OpenAIClientBuilder clientBuilder = new OpenAIClientBuilder();

    // Act
    new DialOpenAiClientBuilderCustomizer().customize(clientBuilder);

    // Assert
    Field pipelinePoliciesField = OpenAIClientBuilder.class.getDeclaredField("pipelinePolicies");
    pipelinePoliciesField.setAccessible(true);
    List<?> pipelinePolicies = (List<?>) pipelinePoliciesField.get(clientBuilder);
    assertThat(pipelinePolicies)
      .anyMatch(DialOpenAiClientBuilderCustomizer.UnsupportedChatCompletionsParametersPolicy.class::isInstance);
  }

  @Test
  void shouldRemoveUnsupportedLogprobFieldsFromChatCompletionsRequests() throws Exception {
    // Arrange
    HttpRequest request = new HttpRequest(
      HttpMethod.POST,
      "https://example.test/openai/deployments/demo/chat/completions?api-version=2025-01-01-preview"
    ).setBody("""
      {
        "messages": [],
        "model": "demo",
        "logprobs": false,
        "top_logprobs": 3
      }
      """);
    DialOpenAiClientBuilderCustomizer.UnsupportedChatCompletionsParametersPolicy policy =
      new DialOpenAiClientBuilderCustomizer.UnsupportedChatCompletionsParametersPolicy();

    // Act
    policy.sanitize(request);

    // Assert
    @SuppressWarnings("unchecked")
    Map<String, Object> body = objectMapper.readValue(
      request.getBodyAsBinaryData().toBytes(), Map.class);
    assertThat(body)
      .containsEntry("model", "demo")
      .containsEntry("messages", List.of())
      .doesNotContainKeys("logprobs", "top_logprobs");
  }

  @Test
  void shouldKeepNonChatRequestsUnchanged() {
    // Arrange
    String originalBody = """
      {
        "input": "hello",
        "logprobs": false
      }
      """;
    HttpRequest request = new HttpRequest(
      HttpMethod.POST,
      "https://example.test/openai/deployments/demo/embeddings?api-version=2025-01-01-preview"
    ).setBody(originalBody);
    DialOpenAiClientBuilderCustomizer.UnsupportedChatCompletionsParametersPolicy policy =
      new DialOpenAiClientBuilderCustomizer.UnsupportedChatCompletionsParametersPolicy();

    // Act
    policy.sanitize(request);

    // Assert
    assertThat(request.getBodyAsBinaryData().toString()).isEqualTo(originalBody);
  }
}
