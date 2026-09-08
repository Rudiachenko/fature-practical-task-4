package com.epam.prompting_llm.service;

import com.epam.prompting_llm.api.model.PromptRequest;
import com.epam.prompting_llm.api.model.PromptResponse;
import com.epam.prompting_llm.api.model.StructuredChatResponse;
import com.epam.prompting_llm.api.model.TokenUsage;
import com.epam.prompting_llm.config.ChatProperties;
import com.epam.prompting_llm.exception.StructuredOutputException;
import com.epam.prompting_llm.support.SafeLogFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/**
 * Orchestrates per-request model options, structured output, logging, and transactional memory.
 */
@Slf4j
@Service
public class ChatProcessor {

  private static final String DEFAULT_CONVERSATION_ID = "default-conversation";
  private static final int CONVERSATION_LOCK_COUNT = 64;
  private final ChatClient chatClient;
  private final SamplingOptionsResolver samplingOptionsResolver;
  private final ChatStructuredOutputConverter outputConverter;
  private final RawModelResponseCapture rawModelResponseCapture;
  private final TransactionalChatMemory chatMemory;
  private final ReentrantLock[] conversationLocks = createConversationLocks();

  public ChatProcessor(ChatModel chatModel, ChatProperties chatProperties,
                SamplingOptionsResolver samplingOptionsResolver,
                ChatStructuredOutputConverter outputConverter,
                RawModelResponseCapture rawModelResponseCapture) {
    this.samplingOptionsResolver = samplingOptionsResolver;
    this.outputConverter = outputConverter;
    this.rawModelResponseCapture = rawModelResponseCapture;
    this.chatMemory = createChatMemory(chatProperties);
    this.chatClient = createChatClient(chatModel, chatProperties, this.chatMemory);
  }

  public PromptResponse sendMessage(PromptRequest request) {
    String conversationId = resolveConversationId(request);
    AzureOpenAiChatOptions requestOptions = requestOptions(samplingOptionsResolver.resolve(request));
    logRequest(conversationId, request, requestOptions);
    return executeConversationTurn(conversationId, request.message(), requestOptions);
  }

  private PromptResponse toPromptResponse(String conversationId,
                                          ResponseEntity<ChatResponse, StructuredChatResponse> modelResponse) {
    ChatResponse chatResponse = modelResponse.response();
    StructuredChatResponse structuredResponse = modelResponse.entity();
    rawModelResponseCapture.capture(conversationId, rawContent(chatResponse), chatResponse);
    validateStructuredResponse(structuredResponse);
    return new PromptResponse(
      conversationId,
      structuredResponse.response(),
      structuredResponse.tone(),
      mapUsage(chatResponse)
    );
  }

  private AzureOpenAiChatOptions requestOptions(ResolvedChatOptions resolvedOptions) {
    AzureOpenAiChatOptions requestOptions = resolvedOptions.options().copy();
    requestOptions.setResponseFormat(outputConverter.responseFormat());
    applyModelCompatibility(requestOptions);
    return requestOptions;
  }

  private void applyModelCompatibility(AzureOpenAiChatOptions requestOptions) {
    String deploymentName = requestOptions.getDeploymentName();
    if (!StringUtils.hasText(deploymentName)) {
      return;
    }

    if (!ModelCapabilities.isReasoningModel(deploymentName)) {
      return;
    }

    if (requestOptions.getMaxTokens() != null && requestOptions.getMaxCompletionTokens() == null) {
      requestOptions.setMaxCompletionTokens(requestOptions.getMaxTokens());
      requestOptions.setMaxTokens(null);
    }

    requestOptions.setTemperature(null);
    requestOptions.setTopP(null);
  }

  private static ReentrantLock[] createConversationLocks() {
    return IntStream.range(0, CONVERSATION_LOCK_COUNT)
      .mapToObj(index -> new ReentrantLock())
      .toArray(ReentrantLock[]::new);
  }

  private static ChatMemory createWindowMemory(ChatProperties chatProperties) {
    return MessageWindowChatMemory.builder()
      .chatMemoryRepository(new InMemoryChatMemoryRepository())
      .maxMessages(chatProperties.getMaxMemoryMessages())
      .build();
  }

  private static TransactionalChatMemory createChatMemory(ChatProperties chatProperties) {
    return new TransactionalChatMemory(createWindowMemory(chatProperties));
  }

  private static ChatClient createChatClient(ChatModel chatModel,
                                             ChatProperties chatProperties,
                                             TransactionalChatMemory chatMemory) {
    return ChatClient.builder(chatModel)
      .defaultSystem(chatProperties.getSystemPrompt())
      .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
      .build();
  }

  private String resolveConversationId(PromptRequest request) {
    return StringUtils.hasText(request.conversationId())
      ? request.conversationId().trim()
      : DEFAULT_CONVERSATION_ID;
  }

  private void logRequest(String conversationId,
                          PromptRequest request,
                          AzureOpenAiChatOptions requestOptions) {
    Integer effectiveMaxTokens = requestOptions.getMaxTokens() != null
      ? requestOptions.getMaxTokens()
      : requestOptions.getMaxCompletionTokens();
    log.info("Processing chat: conversationId={}, temperature={}, topP={}, maxTokens={}",
      SafeLogFormatter.format(conversationId), requestOptions.getTemperature(),
      requestOptions.getTopP(), effectiveMaxTokens);
    log.debug("User message: conversationId={}, message={}",
      SafeLogFormatter.format(conversationId), SafeLogFormatter.format(request.message()));
  }

  private PromptResponse executeConversationTurn(String conversationId,
                                                 String message,
                                                 AzureOpenAiChatOptions requestOptions) {
    ReentrantLock conversationLock = lockFor(conversationId);
    conversationLock.lock();
    boolean transactionActive = false;
    try {
      chatMemory.begin(conversationId);
      transactionActive = true;
      ResponseEntity<ChatResponse, StructuredChatResponse> modelResponse =
        callModel(conversationId, message, requestOptions);
      PromptResponse response = toPromptResponse(conversationId, modelResponse);
      chatMemory.commit();
      transactionActive = false;
      logResponse(conversationId, response);
      return response;
    } catch (RuntimeException | Error exception) {
      if (transactionActive) {
        chatMemory.rollback();
      }
      throw exception;
    } finally {
      conversationLock.unlock();
    }
  }

  private ResponseEntity<ChatResponse, StructuredChatResponse> callModel(
      String conversationId, String message, AzureOpenAiChatOptions requestOptions) {
    return chatClient.prompt()
      .user(message)
      .options(requestOptions)
      .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
      .call()
      .responseEntity(outputConverter);
  }

  private void logResponse(String conversationId, PromptResponse response) {
    log.debug("Model response: conversationId={}, response={}, tone={}",
      SafeLogFormatter.format(conversationId),
      SafeLogFormatter.format(response.response()),
      response.tone());
  }

  private String rawContent(ChatResponse modelResponse) {
    if (modelResponse == null || modelResponse.getResults().isEmpty()) {
      return null;
    }

    Generation generation = modelResponse.getResult();
    return generation.getOutput().getText();
  }

  private void validateStructuredResponse(StructuredChatResponse structuredResponse) {
    if (structuredResponse == null
      || !StringUtils.hasText(structuredResponse.response())
      || structuredResponse.tone() == null) {
      throw new StructuredOutputException();
    }
  }

  private ReentrantLock lockFor(String conversationId) {
    int index = Math.floorMod(conversationId.hashCode(), conversationLocks.length);
    return conversationLocks[index];
  }

  private TokenUsage mapUsage(ChatResponse chatResponse) {
    if (chatResponse == null) {
      return null;
    }

    ChatResponseMetadata metadata = chatResponse.getMetadata();
    Usage usage = metadata.getUsage();
    if (!hasMeaningfulUsage(usage)) {
      return null;
    }
    return new TokenUsage(
      usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }

  private boolean hasMeaningfulUsage(Usage usage) {
    return usage != null
      && !(usage instanceof EmptyUsage)
      && (usage.getPromptTokens() != null
      || usage.getCompletionTokens() != null
      || usage.getTotalTokens() != null);
  }
}
