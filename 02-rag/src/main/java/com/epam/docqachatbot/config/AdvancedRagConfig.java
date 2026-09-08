package com.epam.docqachatbot.config;

import com.epam.docqachatbot.retrieval.MaximalMarginalRelevanceDocumentPostProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    ObjectProvider<QueryExpander> queryExpanderProvider,
    List<DocumentPostProcessor> documentPostProcessors) {

    log.info("Configuring Modular RAG with document retrieval, joining, and query augmentation");

    RetrievalAugmentationAdvisor.Builder builder = RetrievalAugmentationAdvisor.builder()
      .documentRetriever(documentRetriever)
      .documentJoiner(documentJoiner)
      .queryAugmenter(queryAugmenter);
    if (!queryTransformers.isEmpty()) {
      builder.queryTransformers(queryTransformers);
    }
    queryExpanderProvider.ifAvailable(builder::queryExpander);
    if (!documentPostProcessors.isEmpty()) {
      builder.documentPostProcessors(documentPostProcessors);
    }
    RetrievalAugmentationAdvisor advisor = builder.build();

    log.info("Modular RAG pipeline configured successfully");
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

    VectorStoreDocumentRetriever.Builder builder = VectorStoreDocumentRetriever
      .builder()
      .vectorStore(vectorStore)
      .similarityThreshold(ragProperties.getRetrieval().getSimilarityThreshold())
      .topK(ragProperties.getRetrieval().getTopK());

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
   * MaximalMarginalRelevanceDocumentPostProcessor - deterministic, model-free heading-diversity
   * re-ranking (in the spirit of MMR; see the post-processor's own class-level javadoc for why
   * this is metadata-diversity, not textbook vector-space MMR).
   * <p>
   * Post-retrieval component that: - Narrows an already-retrieved candidate pool down to {@code
   * mmrFinalTopK} documents - Balances relevance ({@code Document.getScore()}) against thematic
   * diversity (the second {@code " > "}-delimited segment of the {@code headingPath} metadata -
   * the theme level, not the exact leaf heading) - Targets the documented single-theme collapse
   * where every retrieved chunk comes from one section
   * <p>
   * Enabled by default ({@code app.rag.retrieval.mmr-enabled=true}) over a {@code topK=20}
   * candidate pool narrowed to {@code mmrFinalTopK=5}, so a request still sees and cites five
   * chunks while the pool is broad enough to span more than one theme.
   * <p>
   * {@code mmrFinalTopK} is a ceiling, not a quota: narrowing to more documents than retrieval
   * supplied is meaningless rather than invalid, so a value above {@code topK} is clamped down to
   * {@code topK} and logged. {@code mmrFinalTopK} is an absolute number, so without the clamp,
   * lowering {@code topK} below it - which callers do routinely, and which every narrow-retrieval
   * test does - would fail context startup on a perfectly sensible configuration.
   */
  @Bean
  @ConditionalOnProperty(prefix = "app.rag.retrieval", name = "mmr-enabled", havingValue = "true")
  public DocumentPostProcessor maximalMarginalRelevanceDocumentPostProcessor(
    RagProperties ragProperties) {
    int topK = ragProperties.getRetrieval().getTopK();
    int configuredFinalTopK = ragProperties.getRetrieval().getMmrFinalTopK();
    int finalTopK = Math.min(configuredFinalTopK, topK);
    if (finalTopK != configuredFinalTopK) {
      log.warn("Clamping mmrFinalTopK from {} to topK={}: the reranker cannot return more "
        + "documents than retrieval supplied", configuredFinalTopK, topK);
    }
    log.info("Enabling MaximalMarginalRelevanceDocumentPostProcessor with finalTopK={}, lambda={}",
      finalTopK, ragProperties.getRetrieval().getMmrLambda());
    return new MaximalMarginalRelevanceDocumentPostProcessor(
      finalTopK, ragProperties.getRetrieval().getMmrLambda());
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
  public QueryAugmenter queryAugmenter(
    @Value("classpath:prompts/rag_context_prompt.st") Resource contextPrompt,
    @Value("classpath:prompts/rag_empty_context_prompt.st") Resource emptyContextPrompt) {
    log.info("Configuring ContextualQueryAugmenter with allowEmptyContext={}",
      ragProperties.getQuestionAnswer().isAllowEmptyContext());

    return ContextualQueryAugmenter.builder()
      .promptTemplate(promptTemplate(contextPrompt))
      .emptyContextPromptTemplate(promptTemplate(emptyContextPrompt))
      .allowEmptyContext(ragProperties.getQuestionAnswer().isAllowEmptyContext())
      .documentFormatter(AdvancedRagConfig::formatDelimitedContext)
      .build();
  }

  /**
   * Renders the retrieved documents as explicitly delimited passages instead of Spring AI's
   * default formatting.
   * <p>
   * The default {@code documentFormatter} on {@link ContextualQueryAugmenter} joins every
   * document's text with a single {@code System.lineSeparator()} and nothing else (verified by
   * decompiling the resolved {@code spring-ai-rag:1.1.2}), so several retrieved policy sections
   * arrive at the model as one undifferentiated block with no marker for where one section ends
   * and the next begins. That silently undermines the system prompt's own rules, which require the
   * model to reason about a <em>passage</em> as a unit: to state every distinct requirement "the
   * relevant passage" gives, and to ground each item of a fixed-count answer in "a different
   * passage". A model cannot honour a per-passage rule it cannot see the boundaries of.
   * <p>
   * The separator is deliberately an unlabelled rule rather than a numbered {@code [Source N]}
   * caption. A numbered caption was tried first and measured: it gave the model a citable label,
   * and 8 to 9 of 35 live answers echoed "(Source 2)" into user-visible text, which references
   * nothing the caller can see - the source list is returned as a separate structured field. A
   * plain rule supplies the same boundary with nothing to cite. Suppressing the echo with an extra
   * system-prompt rule was also tried and rejected: it is one more instruction competing for
   * attention, and the defect is better removed at its source.
   * <p>
   * Each document's text already begins with its own {@code Heading:} breadcrumb, so the separator
   * marks the boundary without duplicating the heading. This matters for this corpus specifically,
   * which contains adjacent sections that are routinely retrieved together and read as one passage
   * when undelimited - for example {@code 2.3.3 Define Wrappers Around Native Methods} and
   * {@code 2.4.7 Make Methods Private}, which both discuss making things private.
   * <p>
   * <b>Scope of the claim:</b> this is a coherence fix, justified by the mismatch between the
   * prompt's per-passage rules and an invisible boundary. It is <em>not</em> claimed to fix the
   * {@code Detail} rubric's intermittent incompleteness: measured at n=20, that answer is
   * rubric-complete about half the time either way (see {@code evaluation/RESULTS.md}).
   */
  static String formatDelimitedContext(List<Document> documents) {
    return documents.stream()
      .map(Document::getText)
      .collect(Collectors.joining("\n\n--------\n\n"));
  }

  private PromptTemplate promptTemplate(Resource resource) {
    try {
      return new PromptTemplate(resource.getContentAsString(StandardCharsets.UTF_8).strip());
    } catch (IOException exception) {
      throw new IllegalStateException("RAG prompt template could not be read", exception);
    }
  }
}

