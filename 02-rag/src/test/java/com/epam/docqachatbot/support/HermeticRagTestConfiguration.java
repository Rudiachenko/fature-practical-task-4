package com.epam.docqachatbot.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

@TestConfiguration(proxyBeanMethods = false)
public class HermeticRagTestConfiguration {

  @Bean
  @Primary
  RecordingChatModel deterministicChatModel() {
    return new RecordingChatModel();
  }

  public static final class RecordingChatModel implements ChatModel {

    private final CopyOnWriteArrayList<Prompt> prompts = new CopyOnWriteArrayList<>();

    @Override
    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt);
      String userText = prompt.getUserMessage().getText();
      String response = userText.equals(
        "I don't have enough information to answer this question.")
        ? "fabricated unsupported answer"
        : "deterministic-response:" + userText;
      return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
    }

    public List<Prompt> prompts() {
      return List.copyOf(prompts);
    }

    public void reset() {
      prompts.clear();
    }
  }

  @Bean
  @Primary
  RecordingEmbeddingModel deterministicEmbeddingModel() {
    return new RecordingEmbeddingModel();
  }

  @Bean
  @Primary
  VectorStore inMemoryVectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
  }

  public static final class RecordingEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 64;
    private final AtomicInteger documentEmbeddingCount = new AtomicInteger();
    private final AtomicInteger queryEmbeddingCount = new AtomicInteger();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      queryEmbeddingCount.addAndGet(request.getInstructions().size());
      List<Embedding> embeddings = java.util.stream.IntStream
        .range(0, request.getInstructions().size())
        .mapToObj(index -> new Embedding(vector(request.getInstructions().get(index)), index))
        .toList();
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      documentEmbeddingCount.incrementAndGet();
      return vector(document.getText());
    }

    @Override
    public int dimensions() {
      return DIMENSIONS;
    }

    public int documentEmbeddingCount() {
      return documentEmbeddingCount.get();
    }

    public int queryEmbeddingCount() {
      return queryEmbeddingCount.get();
    }

    public void resetCounts() {
      documentEmbeddingCount.set(0);
      queryEmbeddingCount.set(0);
    }

    private float[] vector(String text) {
      float[] vector = new float[DIMENSIONS];
      Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
        .filter(token -> !token.isBlank())
        .forEach(token -> {
          int hash = token.hashCode();
          int index = Math.floorMod(hash, DIMENSIONS);
          vector[index] += (hash & 1) == 0 ? 1.0f : -1.0f;
        });
      double magnitude = Math.sqrt(Arrays.stream(toDouble(vector))
        .map(value -> value * value)
        .sum());
      if (magnitude == 0) {
        vector[0] = 1.0f;
        return vector;
      }
      for (int index = 0; index < vector.length; index++) {
        vector[index] /= (float) magnitude;
      }
      return vector;
    }

    private double[] toDouble(float[] values) {
      double[] result = new double[values.length];
      for (int index = 0; index < values.length; index++) {
        result[index] = values[index];
      }
      return result;
    }
  }
}
