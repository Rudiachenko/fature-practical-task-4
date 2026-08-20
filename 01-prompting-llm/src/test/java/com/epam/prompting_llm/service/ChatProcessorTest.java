package com.epam.prompting_llm.service;

import com.epam.prompting_llm.api.model.MessageTone;
import com.epam.prompting_llm.api.model.PromptRequest;
import com.epam.prompting_llm.api.model.PromptResponse;
import com.epam.prompting_llm.config.ChatProperties;
import com.epam.prompting_llm.exception.StructuredOutputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatProcessorTest {

  private ChatModel chatModel;
  private ChatProperties properties;
  private RawModelResponseCapture rawModelResponseCapture;
  private AtomicInteger responseCounter;

  @BeforeEach
  void setUp() {
    chatModel = mock(ChatModel.class);
    properties = new ChatProperties();
    properties.setModelName("test-deployment");
    properties.setSystemPrompt(new ByteArrayResource(
      "Return JSON with response and tone".getBytes(StandardCharsets.UTF_8)));
    properties.setMaxMemoryMessages(20);
    properties.setDefaultTemperature(0.7);
    properties.setTopPSupported(true);
    properties.setTopPFallbackTemperature(0.5);
    rawModelResponseCapture = mock(RawModelResponseCapture.class);

    responseCounter = new AtomicInteger();
    when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> response(
      "answer-" + responseCounter.incrementAndGet()));
  }

  @Test
  void shouldReturnStructuredResponseAndUsageWhenModelReturnsValidJson() {
    // Arrange
    ChatProcessor processor = processor();
    PromptRequest request = new PromptRequest("Hello", null, 1.5, null, 10);

    // Act
    PromptResponse response = processor.sendMessage(request);

    // Assert
    assertThat(response.conversationId()).isEqualTo("default-conversation");
    assertThat(response.response()).isEqualTo("answer-1");
    assertThat(response.tone()).isEqualTo(MessageTone.POSITIVE);
    assertThat(response.usage().promptTokens()).isEqualTo(3);
    assertThat(response.usage().completionTokens()).isEqualTo(4);
    assertThat(response.usage().totalTokens()).isEqualTo(7);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    AzureOpenAiChatOptions options =
      (AzureOpenAiChatOptions) promptCaptor.getValue().getOptions();
    assertThat(options.getDeploymentName()).isEqualTo("test-deployment");
    assertThat(options.getTemperature()).isEqualTo(1.5);
    assertThat(options.getTopP()).isNull();
    assertThat(options.getMaxTokens()).isEqualTo(10);
    assertThat(options.getResponseFormat()).isNotNull();
    assertThat(options.getResponseFormat().getType()).isEqualTo(AzureOpenAiResponseFormat.Type.JSON_SCHEMA);
    assertThat(options.getResponseFormat().getJsonSchema().getName()).isEqualTo("structured_chat_response");
    assertThat(options.getResponseFormat().getJsonSchema().getStrict()).isTrue();
    assertThat(toneEnum(options))
      .containsExactly("POSITIVE", "NEGATIVE", "NEUTRAL");
    verify(rawModelResponseCapture).capture(
      eq("default-conversation"), eq("{\"response\":\"answer-1\",\"tone\":\"POSITIVE\"}\n"),
      any(ChatResponse.class));
  }

  @Test
  void shouldUseReasoningModelOptionsForLegacyGpt5Family() {
    // Arrange
    properties.setModelName("gpt-5-mini-2025-08-07");
    ChatProcessor processor = processor();
    PromptRequest request = new PromptRequest("Hello", null, null, null, 10);

    // Act
    processor.sendMessage(request);

    // Assert
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    AzureOpenAiChatOptions options =
      (AzureOpenAiChatOptions) promptCaptor.getValue().getOptions();
    assertThat(options.getTemperature()).isNull();
    assertThat(options.getTopP()).isNull();
    assertThat(options.getMaxTokens()).isNull();
    assertThat(options.getMaxCompletionTokens()).isEqualTo(10);
  }

  @Test
  void shouldUseReasoningModelOptionsForGpt56Family() {
    // Arrange
    properties.setModelName("gpt-5.6-terra-2026-07-09");
    ChatProcessor processor = processor();
    PromptRequest request = new PromptRequest("Hello", null, null, null, 10);

    // Act
    processor.sendMessage(request);

    // Assert
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    AzureOpenAiChatOptions options =
      (AzureOpenAiChatOptions) promptCaptor.getValue().getOptions();
    assertThat(options.getTemperature()).isNull();
    assertThat(options.getTopP()).isNull();
    assertThat(options.getMaxTokens()).isNull();
    assertThat(options.getMaxCompletionTokens()).isEqualTo(10);
  }

  @Test
  void shouldRemoveSamplingAndUseMaxCompletionTokensForGpt54Mini() {
    // Arrange
    properties.setModelName("gpt-5.4-mini-2026-03-17");
    ChatProcessor processor = processor();
    PromptRequest request = new PromptRequest("Hello", null, null, null, 10);

    // Act
    processor.sendMessage(request);

    // Assert
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    AzureOpenAiChatOptions options =
      (AzureOpenAiChatOptions) promptCaptor.getValue().getOptions();
    assertThat(options.getTemperature()).isNull();
    assertThat(options.getTopP()).isNull();
    assertThat(options.getMaxTokens()).isNull();
    assertThat(options.getMaxCompletionTokens()).isEqualTo(10);
  }

  @Test
  void shouldReturnNullUsageWhenProviderDoesNotSupplyUsage() {
    // Arrange
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
      List.of(new Generation(new AssistantMessage(
        "{\"response\":\"answer\",\"tone\":\"NEUTRAL\"}")))));
    ChatProcessor processor = processor();

    // Act
    PromptResponse response = processor.sendMessage(request("question", "conversation-1"));

    // Assert
    assertThat(response.usage()).isNull();
  }

  @Test
  void shouldRejectResponseWhenModelResponseContentIsEmpty() {
    // Arrange
    when(chatModel.call(any(Prompt.class)))
      .thenReturn(null)
      .thenReturn(new ChatResponse(List.of()))
      .thenReturn(rawResponse(""));
    ChatProcessor processor = processor();

    // Act / Assert
    assertThatThrownBy(() -> processor.sendMessage(request("first", "conversation-1")))
      .isInstanceOf(StructuredOutputException.class);
    assertThatThrownBy(() -> processor.sendMessage(request("second", "conversation-1")))
      .isInstanceOf(StructuredOutputException.class);
    assertThatThrownBy(() -> processor.sendMessage(request("third", "conversation-1")))
      .isInstanceOf(StructuredOutputException.class);
  }

  @Test
  void shouldRejectResponseWhenStructuredConverterReturnsNull() {
    // Arrange
    ChatStructuredOutputConverter outputConverter = mock(ChatStructuredOutputConverter.class);
    when(outputConverter.convert(any(String.class))).thenReturn(null);
    when(chatModel.call(any(Prompt.class)))
      .thenReturn(rawResponse("{\"response\":\"answer\",\"tone\":\"NEUTRAL\"}"));
    ChatProcessor processor = new ChatProcessor(
      chatModel,
      properties,
      new SamplingOptionsResolver(properties),
      outputConverter,
      rawModelResponseCapture
    );

    // Act / Assert
    assertThatThrownBy(() -> processor.sendMessage(request("question", "conversation-1")))
      .isInstanceOf(StructuredOutputException.class);
  }

  @Test
  void shouldRollbackUserMessageWhenModelReturnsEmptyResponse() {
    // Arrange
    when(chatModel.call(any(Prompt.class)))
      .thenReturn(new ChatResponse(List.of()))
      .thenReturn(response("recovered"));
    ChatProcessor processor = processor();

    // Act / Assert
    assertThatThrownBy(() -> processor.sendMessage(request("empty-response-user", "conversation-1")))
      .isInstanceOf(StructuredOutputException.class);
    processor.sendMessage(request("next-user", "conversation-1"));

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(2)).call(promptCaptor.capture());
    assertThat(messageTexts(promptCaptor.getAllValues().get(1)))
      .noneMatch(text -> text.contains("empty-response-user"));
  }

  @Test
  void shouldRollbackUserMessageWhenProviderCallFails() {
    // Arrange
    when(chatModel.call(any(Prompt.class)))
      .thenThrow(new TransientAiException("provider failure"))
      .thenReturn(response("recovered"));
    ChatProcessor processor = processor();

    // Act / Assert
    assertThatThrownBy(() -> processor.sendMessage(request("failed-user", "conversation-1")))
      .isInstanceOf(TransientAiException.class);
    PromptResponse recovered = processor.sendMessage(request("next-user", "conversation-1"));
    assertThat(recovered.response()).isEqualTo("recovered");

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(2)).call(promptCaptor.capture());
    assertThat(messageTexts(promptCaptor.getAllValues().get(1)))
      .noneMatch(text -> text.contains("failed-user"));
  }

  @Test
  void shouldRollbackUserAndRawAssistantMessagesWhenConversionFails() {
    // Arrange
    when(chatModel.call(any(Prompt.class)))
      .thenReturn(rawResponse("not-json"))
      .thenReturn(response("recovered"));
    ChatProcessor processor = processor();

    // Act / Assert
    assertThatThrownBy(() -> processor.sendMessage(request("failed-user", "conversation-1")))
      .isInstanceOf(StructuredOutputException.class);
    processor.sendMessage(request("next-user", "conversation-1"));

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(2)).call(promptCaptor.capture());
    assertThat(messageTexts(promptCaptor.getAllValues().get(1)))
      .noneMatch(text -> text.contains("failed-user"))
      .noneMatch(text -> text.contains("not-json"));
  }

  @Test
  void shouldSerializeRollbackAndNextCommitWhenSameConversationCallsOverlap() throws Exception {
    // Arrange
    CountDownLatch firstEnteredModel = new CountDownLatch(1);
    CountDownLatch releaseFirstCall = new CountDownLatch(1);
    CountDownLatch secondEnteredModel = new CountDownLatch(1);
    AtomicInteger callCounter = new AtomicInteger();
    when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
      if (callCounter.incrementAndGet() == 1) {
        firstEnteredModel.countDown();
        releaseFirstCall.await(2, TimeUnit.SECONDS);
        throw new TransientAiException("provider failure");
      }
      secondEnteredModel.countDown();
      return response("successful-answer");
    });
    ChatProcessor processor = processor();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      // Act
      Future<Throwable> firstResult = executor.submit(() -> {
        try {
          processor.sendMessage(request("failed-concurrent-user", "conversation-1"));
          return null;
        } catch (Throwable exception) {
          return exception;
        }
      });
      assertThat(firstEnteredModel.await(1, TimeUnit.SECONDS)).isTrue();
      Future<PromptResponse> secondResult = executor.submit(() ->
        processor.sendMessage(request("successful-user", "conversation-1")));

      // Assert
      assertThat(secondEnteredModel.await(200, TimeUnit.MILLISECONDS)).isFalse();
      releaseFirstCall.countDown();
      assertThat(firstResult.get(2, TimeUnit.SECONDS)).isInstanceOf(TransientAiException.class);
      assertThat(secondResult.get(2, TimeUnit.SECONDS).response()).isEqualTo("successful-answer");

      ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
      verify(chatModel, times(2)).call(promptCaptor.capture());
      assertThat(messageTexts(promptCaptor.getAllValues().get(1)))
        .noneMatch(text -> text.contains("failed-concurrent-user"));
    } finally {
      releaseFirstCall.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void shouldKeepMemorySeparateWhenConversationIdentifiersDiffer() {
    // Arrange
    ChatProcessor processor = processor();

    // Act
    processor.sendMessage(request("first-a", "conversation-a"));
    processor.sendMessage(request("second-a", "conversation-a"));
    processor.sendMessage(request("first-b", "conversation-b"));

    // Assert
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(3)).call(promptCaptor.capture());
    List<Prompt> prompts = promptCaptor.getAllValues();

    assertThat(messageTexts(prompts.get(1)))
      .anyMatch(text -> text.contains("first-a"))
      .anyMatch(text -> text.contains("answer-1"));
    assertThat(messageTexts(prompts.get(2)))
      .noneMatch(text -> text.contains("first-a"))
      .noneMatch(text -> text.contains("answer-1"));
  }

  @Test
  void shouldEvictOldestTurnWhenConfiguredMemoryWindowIsExceeded() {
    // Arrange
    ChatProcessor processor = processor();

    // Act
    for (int index = 1; index <= 12; index++) {
      processor.sendMessage(request("turn-" + index, "bounded-conversation"));
    }

    // Assert
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(12)).call(promptCaptor.capture());
    List<String> finalPrompt = messageTexts(promptCaptor.getAllValues().get(11));
    assertThat(finalPrompt)
      .noneMatch("turn-1"::equals)
      .anyMatch("turn-11"::equals)
      .anyMatch(text -> text.contains("turn-12"));
  }

  private ChatProcessor processor() {
    return new ChatProcessor(
      chatModel,
      properties,
      new SamplingOptionsResolver(properties),
      new ChatStructuredOutputConverter(),
      rawModelResponseCapture
    );
  }

  private PromptRequest request(String message, String conversationId) {
    return new PromptRequest(message, conversationId, null, null, null);
  }

  private ChatResponse response(String answer) {
    String json = """
      {"response":"%s","tone":"POSITIVE"}
      """.formatted(answer);
    return new ChatResponse(
      List.of(new Generation(new AssistantMessage(json))),
      ChatResponseMetadata.builder()
        .usage(new DefaultUsage(3, 4, 7))
        .build()
    );
  }

  private ChatResponse rawResponse(String content) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
  }

  private List<String> messageTexts(Prompt prompt) {
    return prompt.getInstructions().stream()
      .map(Message::getText)
      .toList();
  }

  @SuppressWarnings("unchecked")
  private List<String> toneEnum(AzureOpenAiChatOptions options) {
    Map<String, Object> schema = options.getResponseFormat().getJsonSchema().getSchema();
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    Map<String, Object> tone = (Map<String, Object>) properties.get("tone");
    return (List<String>) tone.get("enum");
  }
}
