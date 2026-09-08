package com.epam.docqachatbot.config;

import com.epam.docqachatbot.ingestion.IngestionMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OptionalRagComponentsTest {

  private final ChatModel chatModel = prompt -> new ChatResponse(List.of(
    new Generation(new AssistantMessage("variant one\nvariant two"))));

  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
      .withUserConfiguration(OptionalComponentsConfiguration.class)
      .withBean(VectorStore.class, () -> mock(VectorStore.class))
      .withBean(ChatClient.Builder.class, () -> ChatClient.builder(chatModel));
  }

  @Test
  void shouldCreateCompressionTransformerAndWireAdvisor_whenCompressionIsEnabled() {
    contextRunner()
      .withPropertyValues("app.rag.query-transformation.compression-enabled=true")
      .run(context -> {
        assertThat(context).hasSingleBean(CompressionQueryTransformer.class);
        assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
      });
  }

  @Test
  void shouldCreateRewriteTransformerAndWireAdvisor_whenRewriteIsEnabled() {
    contextRunner()
      .withPropertyValues("app.rag.query-transformation.rewrite-enabled=true")
      .run(context -> {
        assertThat(context).hasSingleBean(RewriteQueryTransformer.class);
        assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
      });
  }

  @Test
  void shouldCreateConfiguredExpanderAndWireAdvisor_whenExpansionIsEnabled() {
    contextRunner()
      .withPropertyValues(
        "app.rag.query-expansion.enabled=true",
        "app.rag.query-expansion.number-of-queries=2",
        "app.rag.query-expansion.include-original=true")
      .run(context -> {
        assertThat(context).hasSingleBean(MultiQueryExpander.class);
        assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
        List<Query> expanded = context.getBean(QueryExpander.class).expand(new Query("original"));
        assertThat(expanded).extracting(Query::text)
          .containsExactly("original", "variant one", "variant two");
      });
  }

  @Test
  void shouldInvokeEnabledTransformerAndExpander_whenAdvisorRuntimePipelineRuns() {
    RagProperties properties = new RagProperties();
    AdvancedRagConfig config = new AdvancedRagConfig(properties);
    AtomicInteger transformerCalls = new AtomicInteger();
    AtomicInteger expanderCalls = new AtomicInteger();
    AtomicReference<String> retrievedQuery = new AtomicReference<>();
    QueryTransformer transformer = query -> {
      transformerCalls.incrementAndGet();
      return query.mutate().text("transformed").build();
    };
    QueryExpander expander = query -> {
      expanderCalls.incrementAndGet();
      return List.of(query.mutate().text("expanded").build());
    };
    DocumentRetriever retriever = query -> {
      retrievedQuery.set(query.text());
      return List.of();
    };
    QueryAugmenter augmenter = (query, documents) -> query;
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("enabledExpander", expander);
    RetrievalAugmentationAdvisor advisor = config.retrievalAugmentationAdvisor(
      retriever, new ConcatenationDocumentJoiner(), augmenter, List.of(transformer),
      beanFactory.getBeanProvider(QueryExpander.class), List.of());

    ChatClient.builder(chatModel).defaultAdvisors(advisor).build()
      .prompt().user("original").call().chatResponse();

    assertThat(transformerCalls).hasValue(1);
    assertThat(expanderCalls).hasValue(1);
    assertThat(retrievedQuery).hasValue("expanded");
  }

  @Test
  void shouldCreateMmrPostProcessorAndWireAdvisor_whenMmrIsEnabled() {
    contextRunner()
      .withPropertyValues("app.rag.retrieval.mmr-enabled=true")
      .run(context -> {
        assertThat(context).hasSingleBean(DocumentPostProcessor.class);
        assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
      });
  }

  @Test
  void shouldNotCreateMmrPostProcessor_whenMmrIsDisabled() {
    contextRunner()
      .run(context -> assertThat(context).doesNotHaveBean(DocumentPostProcessor.class));
  }

  @Test
  void shouldClampFinalTopKToTopK_whenMmrFinalTopKExceedsTopK() {
    // mmrFinalTopK is an absolute ceiling, so lowering topK below it is a sensible configuration,
    // not a broken one: the reranker simply cannot return more documents than retrieval supplied.
    // Starting up and narrowing to topK beats refusing to start.
    contextRunner()
      .withPropertyValues(
        "app.rag.retrieval.mmr-enabled=true",
        "app.rag.retrieval.top-k=3",
        "app.rag.retrieval.mmr-final-top-k=5")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(DocumentPostProcessor.class);

        List<Document> candidates = List.of(
          themed("a", 0.9, "DOC > THEME A > leaf"),
          themed("b", 0.8, "DOC > THEME B > leaf"),
          themed("c", 0.7, "DOC > THEME C > leaf"),
          themed("d", 0.6, "DOC > THEME D > leaf"));
        assertThat(context.getBean(DocumentPostProcessor.class)
          .process(new Query("probe"), candidates))
          .as("narrowed to topK, not to the larger configured mmrFinalTopK")
          .hasSize(3);
      });
  }

  private static Document themed(String id, double score, String headingPath) {
    return Document.builder()
      .id(id)
      .text(id)
      .score(score)
      .metadata(Map.of(IngestionMetadata.HEADING_PATH, headingPath))
      .build();
  }

  @Test
  void shouldReflectPostProcessedDocuments_whenAdvisorRuntimePipelineRuns() {
    RagProperties properties = new RagProperties();
    AdvancedRagConfig config = new AdvancedRagConfig(properties);
    Document kept = Document.builder().id("kept").text("kept").build();
    Document dropped = Document.builder().id("dropped").text("dropped").build();
    List<Document> retrieved = List.of(kept, dropped);
    AtomicReference<List<Document>> receivedByPostProcessor = new AtomicReference<>();
    DocumentPostProcessor postProcessor = (query, documents) -> {
      receivedByPostProcessor.set(documents);
      return List.of(kept);
    };
    DocumentRetriever retriever = query -> retrieved;
    QueryAugmenter augmenter = (query, documents) -> query;
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    RetrievalAugmentationAdvisor advisor = config.retrievalAugmentationAdvisor(
      retriever, new ConcatenationDocumentJoiner(), augmenter, List.of(),
      beanFactory.getBeanProvider(QueryExpander.class), List.of(postProcessor));

    ChatResponse response = ChatClient.builder(chatModel).defaultAdvisors(advisor).build()
      .prompt().user("original").call().chatResponse();

    assertThat(receivedByPostProcessor.get()).containsExactlyInAnyOrder(kept, dropped);
    assertThat((Object) response.getMetadata()
      .get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT))
      .isEqualTo(List.of(kept));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RagProperties.class)
  @Import(AdvancedRagConfig.class)
  static class OptionalComponentsConfiguration {
  }
}
