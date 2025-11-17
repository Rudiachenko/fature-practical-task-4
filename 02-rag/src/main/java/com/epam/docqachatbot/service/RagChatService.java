package com.epam.docqachatbot.service;

import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class RagChatService {

  private final ChatClient chatClient;

  public RagChatService(RetrievalAugmentationAdvisor retrievalAugmentationAdvisor,
                        RagProperties ragProperties,
                        ChatClient.Builder chatClientBuilder,
                        ResourceLoader resourceLoader) {
    /*
      TODO Initialize chatMemory.
      Use "ragProperties.getMaxMemoryMessages()" to set the messages count in chat history.
     */
    ChatMemory chatMemory = null;

    log.info("Initializing RAG Chat Service with Modular RetrievalAugmentationAdvisor");

    /*
      TODO Initialize chatClientBuilder with system prompt and corresponding advisors
      Use MessageChatMemoryAdvisor and RetrievalAugmentationAdvisor
     */
    this.chatClient = chatClientBuilder.build();

    log.info("✓ Modular RAG Chat Client initialized successfully");
  }

  public RagChatResponse chat(ChatRequest request) {
    String conversationId = StringUtils.hasText(request.conversationId())
      ? request.conversationId()
      : "default-conversation";

    log.debug("Processing chat request with conversationId: {}", conversationId);

    // Modular RAG Pipeline with RetrievalAugmentationAdvisor:
    // 
    // PRE-RETRIEVAL PHASE:
    // 1. Query Transformation: RewriteQueryTransformer clarifies the query (if enabled)
    // 2. Query Expansion: MultiQueryExpander generates query variations (if enabled)
    //
    // RETRIEVAL PHASE:
    // 3. Document Retrieval: VectorStoreDocumentRetriever performs semantic search
    //    - Uses similarity threshold and topK configuration
    //    - Applies filter expressions if configured
    //    - Retrieves relevant document chunks from vector store
    //
    // POST-RETRIEVAL PHASE:
    // 4. Document Joining: ConcatenationDocumentJoiner combines results
    //    - Merges documents from multiple queries (if expanded)
    //    - Removes duplicates (keeps first occurrence)
    //    - Preserves document scores
    //
    // GENERATION PHASE:
    // 5. Query Augmentation: ContextualQueryAugmenter adds context to prompt
    //    - Formats retrieved documents
    //    - Adds instructions for LLM
    //    - Handles empty context scenarios
    // 6. LLM Generation: Model generates response based on augmented prompt

    // You can dynamically override filter expression per request

    /*
      TODO Get familiar with org.springframework.ai.chat.client.DefaultChatClient
     */
    CallResponseSpec responseSpec = chatClient.prompt()
      // TODO Initialize responseSpec with AdvisorSpec, using conversationId as parameters
      // TODO Initialize responseSpec with user's prompt
      .call();

    ChatResponse response = Objects.requireNonNull(responseSpec.chatResponse(),
      "Chat response must not be null");

    String answer = null; // TODO Retrieve textual answer from response

    // Extract sources from advisor metadata
    List<String> sources = extractSourcesFromResponse(response);

    log.info("✓ Chat response generated successfully with {} source documents", sources.size());

    return new RagChatResponse(answer, sources);
  }

  /**
   * Extract document sources from the chat response metadata. The RetrievalAugmentationAdvisor
   * stores retrieved documents in the response metadata.
   */
  private List<String> extractSourcesFromResponse(ChatResponse response) {
    /*
      TODO Extract document sources from the chat response metadata.
     */
    return List.of();
  }
}

