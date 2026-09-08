package com.epam.docqachatbot.support;

import com.epam.docqachatbot.support.HermeticRagTestConfiguration.RecordingChatModel;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration.RecordingEmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration for {@code ChromaVectorStoreWiringIT}: supplies deterministic {@link
 * RecordingChatModel}/{@link RecordingEmbeddingModel} doubles (so the test needs no live DIAL
 * call) while deliberately leaving {@code VectorStore} unconfigured, so the real, unmodified
 * {@code ChromaConfig} production wiring supplies it against a live containerized Chroma.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ChromaWiringTestConfiguration {

  @Bean
  @Primary
  RecordingChatModel deterministicChatModel() {
    return new RecordingChatModel();
  }

  @Bean
  @Primary
  RecordingEmbeddingModel deterministicEmbeddingModel() {
    return new RecordingEmbeddingModel();
  }
}
