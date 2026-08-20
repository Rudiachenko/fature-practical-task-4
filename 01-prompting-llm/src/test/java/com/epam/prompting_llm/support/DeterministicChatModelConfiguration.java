package com.epam.prompting_llm.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@TestConfiguration(proxyBeanMethods = false)
public class DeterministicChatModelConfiguration {

  @Bean
  ChatModel deterministicChatModel() {
    return new DeterministicChatModel(JsonMapper.builder().findAndAddModules().build());
  }

  private static final class DeterministicChatModel implements ChatModel {

    private final ObjectMapper objectMapper;

    private DeterministicChatModel(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      List<String> userMessages = prompt.getInstructions().stream()
        .filter(UserMessage.class::isInstance)
        .map(Message::getText)
        .toList();
      String currentMessage = userMessages.getLast();

      if (currentMessage.contains("simulate-provider-failure")) {
        throw new TransientAiException("Authorization: test-provider-secret");
      }
      if (currentMessage.contains("simulate-unexpected-failure")) {
        throw new IllegalStateException("Api-Key=test-internal-secret");
      }
      if (currentMessage.contains("simulate-malformed-output")) {
        return response("not-json");
      }
      if (currentMessage.contains("simulate-invalid-tone")) {
        return response("{\"response\":\"invalid tone\",\"tone\":\"CONFUSED\"}");
      }

      AzureOpenAiChatOptions options = (AzureOpenAiChatOptions) prompt.getOptions();
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("response", answer(currentMessage, userMessages, options));
      result.put("tone", currentMessage.contains("wonderful") ? "POSITIVE" : "NEUTRAL");
      try {
        return response(objectMapper.writeValueAsString(result));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Deterministic test response could not be serialized", exception);
      }
    }

    private String answer(String currentMessage, List<String> userMessages,
                          AzureOpenAiChatOptions options) {
      if (currentMessage.contains("check-memory-alpha")) {
        return "alpha-visible=" + previousMessageContains(
          userMessages, currentMessage, "remember-alpha");
      }
      if (currentMessage.contains("check-window")) {
        return "oldest-visible=" + previousMessageContains(
          userMessages, currentMessage, "oldest-window-marker")
          + ";recent-visible=" + previousMessageContains(
          userMessages, currentMessage, "recent-window-marker");
      }
      return "temperature=" + options.getTemperature()
        + ";topP=" + options.getTopP()
        + ";maxTokens=" + options.getMaxTokens();
    }

    private boolean previousMessageContains(List<String> messages, String currentMessage,
                                            String expectedText) {
      return messages.stream()
        .filter(message -> !message.equals(currentMessage))
        .anyMatch(message -> message.contains(expectedText));
    }

    private ChatResponse response(String content) {
      return new ChatResponse(
        List.of(new Generation(new AssistantMessage(content))),
        ChatResponseMetadata.builder()
          .usage(new DefaultUsage(11, 7, 18))
          .build()
      );
    }
  }
}
