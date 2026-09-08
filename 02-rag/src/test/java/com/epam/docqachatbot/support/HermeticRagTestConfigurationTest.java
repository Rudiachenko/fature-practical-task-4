package com.epam.docqachatbot.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HermeticRagTestConfigurationTest {

  @Test
  void shouldProvideDeterministicModelsAndSearchableStore_whenConfigurationIsLoaded() {
    // Arrange
    try (AnnotationConfigApplicationContext context =
           new AnnotationConfigApplicationContext(HermeticRagTestConfiguration.class)) {
      ChatModel chatModel = context.getBean(ChatModel.class);
      EmbeddingModel embeddingModel = context.getBean(EmbeddingModel.class);
      VectorStore vectorStore = context.getBean(VectorStore.class);
      Document document = Document.builder()
        .id("chunk-1")
        .text("Prepared statements prevent SQL injection")
        .build();
      vectorStore.add(List.of(document));

      // Act
      String firstResponse = chatModel.call(new Prompt(new UserMessage("policy question")))
        .getResult().getOutput().getText();
      String secondResponse = chatModel.call(new Prompt(new UserMessage("policy question")))
        .getResult().getOutput().getText();
      float[] firstEmbedding = embeddingModel.embed("prepared statements");
      float[] secondEmbedding = embeddingModel.embed("prepared statements");
      List<Document> matches = vectorStore.similaritySearch(SearchRequest.builder()
        .query("prepared statements")
        .topK(1)
        .build());

      // Assert
      assertThat(firstResponse).isEqualTo("deterministic-response:policy question");
      assertThat(secondResponse).isEqualTo(firstResponse);
      assertThat(firstEmbedding).containsExactly(secondEmbedding).hasSize(64);
      assertThat(matches).extracting(Document::getId).containsExactly("chunk-1");
    }
  }

  @Test
  void shouldReturnStableNonZeroEmbedding_whenInputContainsNoTokens() {
    // Arrange
    try (AnnotationConfigApplicationContext context =
           new AnnotationConfigApplicationContext(HermeticRagTestConfiguration.class)) {
      EmbeddingModel embeddingModel = context.getBean(EmbeddingModel.class);

      // Act
      float[] embedding = embeddingModel.embed("   ");

      // Assert
      assertThat(embedding).hasSize(64);
      assertThat(embedding[0]).isEqualTo(1.0f);
    }
  }
}
