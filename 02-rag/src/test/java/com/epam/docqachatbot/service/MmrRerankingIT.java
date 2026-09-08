package com.epam.docqachatbot.service;

import com.epam.docqachatbot.DocQaChatbotApplication;
import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Increment 11's MMR reranker changes real, observable retrieval behaviour through the
 * actual advisor pipeline (ingestion -&gt; retrieval -&gt; {@code RetrievalAugmentationAdvisor}
 * -&gt; chat), hermetically (no live DIAL/Chroma).
 *
 * <p>The fixture has two headings: {@code Section A}, whose 4 chunks each densely repeat a
 * distinctive probe token ("zylospheric"), and {@code Section B}, whose one substantive chunk
 * mentions the same probe token exactly once among unrelated content, scoring far lower for a
 * query built from the probe token alone. A plain top-N retrieval (MMR disabled, {@code top-k}
 * set directly to the small final count) therefore collapses onto {@code Section A} alone —
 * the documented {@code FiveBulletSummary} single-theme-collapse failure at small scale — while
 * MMR, run over the same broader candidate pool and narrowed to the same final count, includes a
 * chunk from {@code Section B}.
 */
class MmrRerankingIT {

  private static final String PROBE_QUERY = "zylospheric";
  private static final List<String> FIXTURE_RESOURCE =
    List.of("classpath:documents/mmr-fixture.md");
  private static final Map<String, Object> FIXTURE_METADATA = Map.of("mmrFixture", true);

  @Nested
  @ActiveProfiles("test")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @SpringBootTest(
    classes = {DocQaChatbotApplication.class, HermeticRagTestConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.rag.retrieval.similarity-threshold=0.0",
      "app.rag.retrieval.top-k=3",
      "app.rag.retrieval.mmr-enabled=false",
      "app.documents.processing.chunking.tokens-per-chunk=40",
      "app.documents.processing.chunking.min-chunk-size-chars=10",
      "app.documents.processing.chunking.min-chunk-length-to-embed=5"
    })
  class WhenMmrIsDisabled {

    @LocalServerPort
    private int port;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private VectorStore vectorStore;

    @BeforeAll
    void ingestFixture() {
      ingestionService.ingestResources(FIXTURE_RESOURCE, FIXTURE_METADATA);
    }

    @Test
    void shouldReturnOnlyDominantHeadingChunks_whenPlainTopKRetrievesTheCandidatePool() {
      RagChatResponse response = chat(port, PROBE_QUERY, "mmr-disabled");
      Map<String, String> headingPathByChunkId = headingPathByChunkId(vectorStore);

      assertThat(response.sources()).isNotEmpty();
      assertThat(response.sources())
        .extracting(source -> headingPathByChunkId.get(source.chunkId()))
        .containsOnly("Section A");
    }
  }

  @Nested
  @ActiveProfiles("test")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @SpringBootTest(
    classes = {DocQaChatbotApplication.class, HermeticRagTestConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.rag.retrieval.similarity-threshold=0.0",
      "app.rag.retrieval.top-k=6",
      "app.rag.retrieval.mmr-enabled=true",
      "app.rag.retrieval.mmr-lambda=0.5",
      "app.rag.retrieval.mmr-final-top-k=3",
      "app.documents.processing.chunking.tokens-per-chunk=40",
      "app.documents.processing.chunking.min-chunk-size-chars=10",
      "app.documents.processing.chunking.min-chunk-length-to-embed=5"
    })
  class WhenMmrIsEnabled {

    @LocalServerPort
    private int port;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private VectorStore vectorStore;

    @BeforeAll
    void ingestFixture() {
      ingestionService.ingestResources(FIXTURE_RESOURCE, FIXTURE_METADATA);
    }

    @Test
    void shouldIncludeASecondHeadingChunk_whenMmrNarrowsTheSameBroaderCandidatePool() {
      RagChatResponse response = chat(port, PROBE_QUERY, "mmr-enabled");
      Map<String, String> headingPathByChunkId = headingPathByChunkId(vectorStore);

      assertThat(response.sources()).hasSize(3);
      assertThat(response.sources())
        .extracting(source -> headingPathByChunkId.get(source.chunkId()))
        .contains("Section B");
    }
  }

  private static Map<String, String> headingPathByChunkId(VectorStore vectorStore) {
    List<Document> allChunks = vectorStore.similaritySearch(SearchRequest.builder()
      .query(PROBE_QUERY)
      .topK(10)
      .similarityThreshold(0.0)
      .build());
    return allChunks.stream().collect(Collectors.toMap(
      document -> document.getMetadata().get(IngestionMetadata.CHUNK_ID).toString(),
      document -> document.getMetadata().get(IngestionMetadata.HEADING_PATH).toString()));
  }

  private static RagChatResponse chat(int port, String input, String conversationId) {
    return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build()
      .post().uri("/doc-qa/chat")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new ChatRequest(input, conversationId))
      .retrieve()
      .body(RagChatResponse.class);
  }
}
