package com.epam.docqachatbot.service;

import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.api.model.RagSource;
import com.epam.docqachatbot.config.RagProperties;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

@Slf4j
@Service
public class RagChatService {

  public static final String REFUSAL = "I don't have enough information to answer this question.";
  private static final String DEFAULT_CONVERSATION_ID = "default-conversation";
  private static final int MAX_CONVERSATION_ID_LENGTH = 128;
  private static final int MIN_MEMORY_MESSAGES = 2;
  private static final int MAX_MEMORY_MESSAGES = 200;
  private static final int CONVERSATION_LOCK_COUNT = 64;

  private final ChatClient chatClient;
  private final ChatMemory chatMemory;
  private final ReentrantLock[] conversationLocks = IntStream.range(0, CONVERSATION_LOCK_COUNT)
    .mapToObj(index -> new ReentrantLock())
    .toArray(ReentrantLock[]::new);

  @Autowired
  public RagChatService(RetrievalAugmentationAdvisor retrievalAugmentationAdvisor,
                        RagProperties ragProperties,
                        ChatClient.Builder chatClientBuilder,
                        ResourceLoader resourceLoader) {
    int maxMemoryMessages = ragProperties.getMaxMemoryMessages();
    if (maxMemoryMessages < MIN_MEMORY_MESSAGES || maxMemoryMessages > MAX_MEMORY_MESSAGES) {
      throw new IllegalStateException("maxMemoryMessages must be between 2 and 200");
    }
    Resource systemPrompt = resourceLoader.getResource(ragProperties.getSystemPrompt());
    if (!systemPrompt.exists() || !systemPrompt.isReadable()) {
      throw new IllegalStateException("RAG system prompt is not readable");
    }
    this.chatMemory = MessageWindowChatMemory.builder()
      .chatMemoryRepository(new InMemoryChatMemoryRepository())
      .maxMessages(maxMemoryMessages)
      .build();
    this.chatClient = chatClientBuilder
      .defaultSystem(systemPrompt)
      .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),
        retrievalAugmentationAdvisor)
      .build();
    log.info("RAG chat service initialized with bounded conversation memory");
  }

  public RagChatService(ChatClient chatClient, ChatMemory chatMemory) {
    this.chatClient = chatClient;
    this.chatMemory = chatMemory;
  }

  public RagChatResponse chat(ChatRequest request) {
    String conversationId = resolveConversationId(request.conversationId());
    ReentrantLock lock = lockFor(conversationId);
    lock.lock();
    List<Message> snapshot = List.copyOf(chatMemory.get(conversationId));
    try {
      ChatResponse response = chatClient.prompt()
        .user(request.input())
        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
        .call()
        .chatResponse();
      RagChatResponse result = toRagResponse(response);
      if (result.sources().isEmpty()) {
        replaceTurnWithRefusal(conversationId, snapshot, request.input());
      }
      log.info("RAG chat completed: conversationKey={}, sources={}",
        conversationKey(conversationId), result.sources().size());
      return result;
    } catch (RuntimeException | Error exception) {
      restoreMemory(conversationId, snapshot);
      log.warn("RAG chat failed: conversationKey={}, type={}",
        conversationKey(conversationId), exception.getClass().getSimpleName());
      throw exception;
    } finally {
      lock.unlock();
    }
  }

  public RagChatResponse toRagResponse(ChatResponse response) {
    if (response == null) {
      throw new IllegalStateException("Chat provider returned no response");
    }
    List<Document> documents = retrievedDocuments(response);
    if (documents.isEmpty()) {
      return new RagChatResponse(REFUSAL, List.of());
    }
    String answer = Optional.ofNullable(response.getResult())
      .map(Generation::getOutput)
      .map(AssistantMessage::getText)
      .orElse(null);
    if (!StringUtils.hasText(answer)) {
      throw new IllegalStateException("Chat provider returned no answer");
    }
    if (REFUSAL.equals(answer.strip())) {
      return new RagChatResponse(REFUSAL, List.of());
    }
    return new RagChatResponse(answer, sources(documents));
  }

  private List<Document> retrievedDocuments(ChatResponse response) {
    Object value = response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
    if (!(value instanceof List<?> values) || values.isEmpty()) {
      return List.of();
    }
    if (values.stream().anyMatch(valueItem -> !(valueItem instanceof Document))) {
      return List.of();
    }
    return values.stream().map(Document.class::cast).toList();
  }

  private List<RagSource> sources(List<Document> documents) {
    Set<RagSource> unique = new LinkedHashSet<>();
    for (Document document : documents) {
      String documentName = metadataText(document, IngestionMetadata.DOCUMENT_NAME);
      String chunkId = metadataText(document, IngestionMetadata.CHUNK_ID);
      if (!StringUtils.hasText(documentName) || !StringUtils.hasText(chunkId)) {
        throw new IllegalStateException("Retrieved document provenance is incomplete");
      }
      unique.add(new RagSource(documentName, chunkId));
    }
    return List.copyOf(unique);
  }

  private String metadataText(Document document, String key) {
    Object value = document.getMetadata().get(key);
    return value instanceof String text ? text : null;
  }

  private String resolveConversationId(String requestedId) {
    String resolved = StringUtils.hasText(requestedId)
      ? requestedId.trim()
      : DEFAULT_CONVERSATION_ID;
    if (resolved.length() > MAX_CONVERSATION_ID_LENGTH) {
      throw new IllegalArgumentException("Conversation identifier exceeds the supported limit");
    }
    return resolved;
  }

  private void replaceTurnWithRefusal(String conversationId,
                                      List<Message> snapshot,
                                      String input) {
    List<Message> committed = new ArrayList<>(snapshot);
    committed.add(new UserMessage(input));
    committed.add(new AssistantMessage(REFUSAL));
    chatMemory.clear(conversationId);
    chatMemory.add(conversationId, committed);
  }

  private void restoreMemory(String conversationId, List<Message> snapshot) {
    chatMemory.clear(conversationId);
    if (!snapshot.isEmpty()) {
      chatMemory.add(conversationId, snapshot);
    }
  }

  private ReentrantLock lockFor(String conversationId) {
    return conversationLocks[Math.floorMod(conversationId.hashCode(), conversationLocks.length)];
  }

  private String conversationKey(String conversationId) {
    return Integer.toUnsignedString(conversationId.hashCode(), 16);
  }
}
