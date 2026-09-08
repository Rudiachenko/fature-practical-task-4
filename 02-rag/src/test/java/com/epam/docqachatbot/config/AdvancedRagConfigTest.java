package com.epam.docqachatbot.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvancedRagConfigTest {

  @Test
  void shouldApplyCanonicalThresholdAndTopK_whenRetrieverRuns() {
    RagProperties properties = new RagProperties();
    properties.getRetrieval().setSimilarityThreshold(0.72);
    properties.getRetrieval().setTopK(2);
    VectorStore vectorStore = mock(VectorStore.class);
    Document first = document("first", 0.91, "chunk-1");
    Document second = document("second", 0.74, "chunk-2");
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
      .thenReturn(List.of(first, second));
    DocumentRetriever retriever = new AdvancedRagConfig(properties).documentRetriever(vectorStore);

    List<Document> result = retriever.retrieve(new Query("secure input"));

    ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(request.capture());
    assertThat(request.getValue().getQuery()).isEqualTo("secure input");
    assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.72);
    assertThat(request.getValue().getTopK()).isEqualTo(2);
    assertThat(result).containsExactly(first, second);
    assertThat(result).allSatisfy(document -> {
      assertThat(document.getScore()).isGreaterThanOrEqualTo(0.72);
      assertThat(document.getMetadata()).containsKey("chunkId");
    });
  }

  @Test
  void shouldPreserveHighestScoredUniqueDocuments_whenJoiningDuplicateResults() {
    Document duplicate = document("duplicate", 0.95, "chunk-1");
    Document duplicateAgain = Document.builder().id(duplicate.getId()).text("changed")
      .score(0.5).metadata("chunkId", "changed").build();
    Document other = document("other", 0.8, "chunk-2");
    Query firstQuery = new Query("first");
    Query secondQuery = new Query("second");
    Map<Query, List<List<Document>>> retrieved = new LinkedHashMap<>();
    retrieved.put(firstQuery, List.of(List.of(duplicate, other)));
    retrieved.put(secondQuery, List.of(List.of(duplicateAgain)));
    DocumentJoiner joiner = new AdvancedRagConfig(new RagProperties()).documentJoiner();

    List<Document> joined = joiner.join(retrieved);

    assertThat(joined).containsExactly(duplicate, other);
    assertThat(joined.getFirst().getMetadata()).containsEntry("chunkId", "chunk-1");
    assertThat(joined.getFirst().getScore()).isEqualTo(0.95);
  }

  @Test
  void shouldExposeRetrievedDocumentsInAdvisorResponseMetadata_whenRuntimePipelineRuns() {
    RagProperties properties = new RagProperties();
    properties.getRetrieval().setSimilarityThreshold(0.6);
    properties.getRetrieval().setTopK(1);
    AdvancedRagConfig config = new AdvancedRagConfig(properties);
    VectorStore vectorStore = mock(VectorStore.class);
    Document hit = document("Use parameterized SQL queries.", 0.9, "chunk-1");
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));
    DocumentRetriever retriever = config.documentRetriever(vectorStore);
    DocumentJoiner joiner = config.documentJoiner();
    QueryAugmenter augmenter = config.queryAugmenter(
      new ClassPathResource("prompts/rag_context_prompt.st"),
      new ClassPathResource("prompts/rag_empty_context_prompt.st"));
    RetrievalAugmentationAdvisor advisor = config.retrievalAugmentationAdvisor(
      retriever, joiner, augmenter, List.of(),
      new StaticListableBeanFactory().getBeanProvider(
        org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class),
      List.of());
    AtomicInteger modelCalls = new AtomicInteger();
    ChatModel chatModel = prompt -> {
      modelCalls.incrementAndGet();
      return new ChatResponse(List.of(new Generation(new AssistantMessage("grounded"))));
    };

    ChatResponse response = ChatClient.builder(chatModel).defaultAdvisors(advisor).build()
      .prompt().user("How should SQL be built?").call().chatResponse();

    assertThat(response).isNotNull();
    assertThat((Object) response.getMetadata()
      .get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT))
      .isEqualTo(List.of(hit));
    ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(request.capture());
    assertThat(request.getValue().getTopK()).isEqualTo(1);
    assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.6);
    assertThat(modelCalls).hasValue(1);
  }

  @Test
  void shouldExposeEmptyDocumentContext_whenNoHitMeetsThreshold() {
    RagProperties properties = new RagProperties();
    AdvancedRagConfig config = new AdvancedRagConfig(properties);
    VectorStore vectorStore = mock(VectorStore.class);
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    RetrievalAugmentationAdvisor advisor = config.retrievalAugmentationAdvisor(
      config.documentRetriever(vectorStore), config.documentJoiner(),
      config.queryAugmenter(new ClassPathResource("prompts/rag_context_prompt.st"),
        new ClassPathResource("prompts/rag_empty_context_prompt.st")), List.of(),
      new StaticListableBeanFactory().getBeanProvider(
        org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class),
      List.of());
    ChatModel chatModel = prompt -> new ChatResponse(List.of(
      new Generation(new AssistantMessage(prompt.getUserMessage().getText()))));

    ChatResponse response = ChatClient.builder(chatModel).defaultAdvisors(advisor).build()
      .prompt().user("unknown").call().chatResponse();

    assertThat(response).isNotNull();
    assertThat((Object) response.getMetadata()
      .get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT))
      .isEqualTo(List.of());
    assertThat(response.getResult().getOutput().getText())
      .isEqualTo("I don't have enough information to answer this question.");
  }

  private Document document(String text, double score, String chunkId) {
    return Document.builder().id(chunkId).text(text).score(score)
      .metadata(Map.of("chunkId", chunkId, "documentName", "policy.md"))
      .build();
  }
}
