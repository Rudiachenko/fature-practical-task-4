package com.epam.docqachatbot.support;

import com.epam.docqachatbot.DocQaChatbotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
  classes = {DocQaChatbotApplication.class, HermeticRagTestConfiguration.class},
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HermeticApplicationContextIT {

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private VectorStore vectorStore;

  @Test
  void shouldLoadFullApplicationOffline_whenHermeticProfileUsesInMemoryVectorStore() {
    // Act / Assert
    assertThat(vectorStore).isInstanceOf(SimpleVectorStore.class);
    assertThat(applicationContext.getBeansOfType(ChromaApi.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(ChromaVectorStore.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(QueryExpander.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(QueryTransformer.class)).isEmpty();
  }
}
