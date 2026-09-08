package com.epam.docqachatbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(
  prefix = "app.vectorstore",
  name = "provider",
  havingValue = "chroma",
  matchIfMissing = true)
public class ChromaConfig {

  @Bean
  public ChromaApi chromaApi(
    ChromaProperties chromaProperties,
    RestClient.Builder restClientBuilder,
    ObjectMapper objectMapper) {

    ChromaApi api = ChromaApi.builder()
      .baseUrl(chromaProperties.getBaseUrl())
      .restClientBuilder(restClientBuilder)
      .objectMapper(objectMapper)
      .build();

    if (StringUtils.hasText(chromaProperties.getApiKey())) {
      api = api.withKeyToken(chromaProperties.getApiKey());
    }

    return api;
  }

  @Bean
  public ChromaVectorStore chromaVectorStore(ChromaApi chromaApi, EmbeddingModel embeddingModel,
                                             ChromaProperties chromaProperties) {
    return ChromaVectorStore.builder(chromaApi, embeddingModel)
      .tenantName(chromaProperties.getTenantName())
      .databaseName(chromaProperties.getDatabaseName())
      .collectionName(chromaProperties.getCollectionName())
      .initializeSchema(true)
      .initializeImmediately(true)
      .build();
  }
}

