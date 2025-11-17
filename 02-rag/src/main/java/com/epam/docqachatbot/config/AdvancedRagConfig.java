package com.epam.docqachatbot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Modular RAG Configuration using Spring AI RetrievalAugmentationAdvisor.
 * <p>
 * This configuration implements a fully modular RAG architecture with: - Pre-Retrieval: Query
 * transformation and expansion - Retrieval: Vector store document retrieval - Post-Retrieval:
 * Document joining and filtering - Generation: Contextual query augmentation
 * <p>
 * Based on Spring AI's modular RAG architecture inspired by "Modular RAG: Transforming RAG Systems
 * into LEGO-like Reconfigurable Frameworks".
 *
 * @see <a
 * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_retrievalaugmentationadvisor">Spring
 * AI Modular RAG Documentation</a>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdvancedRagConfig {

  private final RagProperties ragProperties;

  /**
   * RetrievalAugmentationAdvisor - Modular RAG advisor orchestrating the full RAG pipeline.
   * <p>
   * The advisor coordinates: 1. Pre-Retrieval: Query transformers and expanders process the user
   * query 2. Retrieval: DocumentRetriever fetches relevant documents from vector store 3.
   * Post-Retrieval: DocumentJoiner combines and deduplicates results 4. Generation: QueryAugmenter
   * adds context to the prompt 5. LLM generates the final response
   *
   * @see <a
   * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_retrievalaugmentationadvisor">RetrievalAugmentationAdvisor
   * Documentation</a>
   */
  @Bean
  public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
    DocumentRetriever documentRetriever,
    DocumentJoiner documentJoiner,
    QueryAugmenter queryAugmenter,
    List<QueryTransformer> queryTransformers,
    QueryExpander queryExpander) {

    log.info("Configuring Modular RAG with document retrieval, joining, and query augmentation");

    RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
      .documentRetriever(documentRetriever)
      /*
        TODO Initialize RetrievalAugmentationAdvisor.builder() with documentJoiner, queryAugmenter, queryTransformers,
         queryExpander and get familiar with responsibilities of corresponding components
       */
      .build();

    log.info("✓ Modular RAG pipeline configured successfully");
    return advisor;
  }

  /**
   * VectorStoreDocumentRetriever - Retrieves documents from vector store using semantic search.
   * <p>
   * Core component of the retrieval phase that: - Performs similarity search based on query
   * embeddings - Filters results by metadata expressions - Returns top-K most relevant documents
   *
   * @see <a
   * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_vectorstoredocumentretriever">VectorStoreDocumentRetriever
   * Documentation</a>
   */
  @Bean
  public DocumentRetriever documentRetriever(VectorStore vectorStore) {
    log.info("Configuring VectorStoreDocumentRetriever with similarityThreshold={}, topK={}",
      ragProperties.getRetrieval().getSimilarityThreshold(),
      ragProperties.getRetrieval().getTopK());

    /*
      TODO Initialize VectorStoreDocumentRetriever.Builder with similarityThreshold and topK
     */
    VectorStoreDocumentRetriever.Builder builder = VectorStoreDocumentRetriever
      .builder()
      .vectorStore(vectorStore);

    // Note: Filter expressions can be applied dynamically per request using:
    // VectorStoreDocumentRetriever.FILTER_EXPRESSION parameter

    return builder.build();
  }

  /**
   * CompressionQueryTransformer - Compresses conversation history into standalone query.
   * <p>
   * Pre-retrieval transformer that: - Takes conversation history and follow-up query - Compresses
   * them into a self-contained query - Makes retrieval context-aware of the conversation
   * <p>
   * Example: - History: "What is Spring Boot?" → "Spring Boot is a framework..." - Follow-up: "What
   * are its main features?" - Compressed: "What are the main features of Spring Boot?"
   * <p>
   * This ensures retrieval understands references like "it", "that", "them" by incorporating
   * conversation context.
   *
   * @see <a
   * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_compressionquerytransformer">CompressionQueryTransformer
   * Documentation</a>
   */
  @Bean
  @ConditionalOnProperty(prefix = "app.rag.query-transformation", name = "compression-enabled", havingValue = "true")
  public QueryTransformer compressionQueryTransformer(ChatClient.Builder chatClientBuilder) {
    log.info("Enabling CompressionQueryTransformer for conversation-aware retrieval");

    return CompressionQueryTransformer.builder()
      .chatClientBuilder(chatClientBuilder)
      .build();
  }

  /**
   * RewriteQueryTransformer - Uses LLM to rewrite queries for better retrieval results.
   * <p>
   * Pre-retrieval transformer that: - Clarifies verbose or ambiguous queries - Removes irrelevant
   * information - Reformulates questions for optimal vector search
   * <p>
   * Recommended to use with low temperature (0.0) for deterministic results.
   *
   * @see <a
   * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_rewritequerytransformer">RewriteQueryTransformer
   * Documentation</a>
   */
  @Bean
  @ConditionalOnProperty(prefix = "app.rag.query-transformation", name = "rewrite-enabled", havingValue = "true")
  public QueryTransformer rewriteQueryTransformer(ChatClient.Builder chatClientBuilder) {
    log.info("Enabling RewriteQueryTransformer for query optimization");

    return RewriteQueryTransformer.builder()
      .chatClientBuilder(chatClientBuilder)
      .build();
  }

  /**
   * MultiQueryExpander - Expands single query into multiple semantic variations.
   * <p>
   * Pre-retrieval expander that: - Generates semantically diverse query variations - Captures
   * different perspectives and phrasings - Increases chances of finding relevant documents -
   * Improves recall in retrieval
   * <p>
   * Each variation is used for separate retrieval, results are then joined.
   *
   * @see <a
   * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_multiqueryexpander">MultiQueryExpander
   * Documentation</a>
   */
  @Bean
  @ConditionalOnProperty(prefix = "app.rag.query-expansion", name = "enabled", havingValue = "true")
  public QueryExpander multiQueryExpander(ChatClient.Builder chatClientBuilder) {
    log.info("Enabling MultiQueryExpander with {} query variations, includeOriginal={}",
      ragProperties.getQueryExpansion().getNumberOfQueries(),
      ragProperties.getQueryExpansion().isIncludeOriginal());

    return MultiQueryExpander.builder()
      .chatClientBuilder(chatClientBuilder)
      .numberOfQueries(ragProperties.getQueryExpansion().getNumberOfQueries())
      .includeOriginal(ragProperties.getQueryExpansion().isIncludeOriginal())
      .build();
  }

  /**
   * ConcatenationDocumentJoiner - Combines documents from multiple queries/sources.
   * <p>
   * Post-retrieval component that: - Concatenates documents from multiple retrieval operations -
   * Removes duplicate documents (keeps first occurrence) - Preserves document scores - Handles
   * multi-query expansion results
   *
   * @see <a
   * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_concatenationdocumentjoiner">ConcatenationDocumentJoiner
   * Documentation</a>
   */
  @Bean
  public DocumentJoiner documentJoiner() {
    log.info("Configuring ConcatenationDocumentJoiner for multi-query result combination");
    return new ConcatenationDocumentJoiner();
  }

  /**
   * ContextualQueryAugmenter - Augments user query with retrieved document context.
   * <p>
   * Generation phase component that: - Merges user query with retrieved documents - Formats context
   * for LLM consumption - Handles empty context scenarios - Provides instructions to LLM for
   * context usage
   * <p>
   * By default, instructs model not to answer if no relevant context is found. Can be configured to
   * allow empty context for general questions.
   *
   * @see <a
   * href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html#_contextualqueryaugmenter">ContextualQueryAugmenter
   * Documentation</a>
   */
  @Bean
  public QueryAugmenter queryAugmenter() {
    log.info("Configuring ContextualQueryAugmenter with allowEmptyContext={}",
      ragProperties.getQuestionAnswer().isAllowEmptyContext());

    return ContextualQueryAugmenter.builder()
      .allowEmptyContext(ragProperties.getQuestionAnswer().isAllowEmptyContext())
      .build();
  }
}

