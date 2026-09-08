package com.epam.docqachatbot.config;

import com.epam.docqachatbot.DocQaChatbotApplication;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import com.epam.docqachatbot.service.DocumentIngestionService;
import com.epam.docqachatbot.support.ChromaWiringTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the real, unmodified {@link ChromaConfig} production wiring
 * (RestClient.Builder -> ChromaApi -> ChromaVectorStore) boots against a live containerized
 * Chroma and completes a real HTTP round-trip. Unlike {@code HermeticApplicationContextIT},
 * this test does not override {@code VectorStore}, so it is the only test in the suite that can
 * catch a regression in the class of defect fixed in Increment 1 (a missing
 * {@code RestClient.Builder} bean breaking {@code ChromaConfig.chromaApi(...)}).
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
  classes = {DocQaChatbotApplication.class, ChromaWiringTestConfiguration.class},
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChromaVectorStoreWiringIT {

  @Container
  static final GenericContainer<?> CHROMA = new GenericContainer<>(
    DockerImageName.parse("ghcr.io/chroma-core/chroma:1.0.0"))
    .withExposedPorts(8000)
    .waitingFor(Wait.forHttp("/api/v2/heartbeat")
      .forStatusCode(200)
      .withStartupTimeout(Duration.ofMinutes(2)));

  @DynamicPropertySource
  static void chromaProperties(DynamicPropertyRegistry registry) {
    registry.add("app.vectorstore.provider", () -> "chroma");
    registry.add("app.vectorstore.chroma.base-url", () ->
      "http://" + CHROMA.getHost() + ":" + CHROMA.getMappedPort(8000));
  }

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private DocumentIngestionService ingestionService;

  @Test
  void shouldExposeRealChromaBeans_whenChromaProviderIsActiveAgainstLiveContainer() {
    // Act / Assert
    assertThat(applicationContext.getBeansOfType(ChromaApi.class)).hasSize(1);
    assertThat(applicationContext.getBeansOfType(ChromaVectorStore.class)).hasSize(1);
    assertThat(vectorStore).isInstanceOf(ChromaVectorStore.class);
  }

  @Test
  void shouldRoundTripThroughRealChromaHttp_whenIngestingAndSearchingAFixture() {
    // Act
    ingestionService.ingestResources(
      List.of("classpath:documents/ingestion-sample.txt"),
      Map.of("sourceType", "chroma-wiring-it"));
    List<Document> matches = vectorStore.similaritySearch(SearchRequest.builder()
      .query("Validate untrusted input security sensitive operations")
      .topK(5)
      .similarityThreshold(0.0)
      .build());

    // Assert
    assertThat(matches).isNotEmpty();
    assertThat(matches).anySatisfy(document -> {
      assertThat(document.getText()).contains("Validate untrusted input");
      assertThat(document.getMetadata())
        .containsEntry(IngestionMetadata.DOCUMENT_NAME, "ingestion-sample.txt");
      assertThat(document.getMetadata().get(IngestionMetadata.CHUNK_ID))
        .isEqualTo(document.getId());
    });
  }
}
