package com.epam.docqachatbot.service;

import com.epam.docqachatbot.DocQaChatbotApplication;
import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration.RecordingChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.UserMessage;
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

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
  classes = {DocQaChatbotApplication.class, HermeticRagTestConfiguration.class},
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "app.rag.retrieval.similarity-threshold=0.8",
    "app.rag.retrieval.top-k=2",
    "app.rag.max-memory-messages=4"
  })
class RagChatIT {

  @LocalServerPort
  private int port;

  @Autowired
  private DocumentIngestionService ingestionService;

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private RecordingChatModel chatModel;

  private RestClient restClient;
  private List<Document> expectedChunks;

  @BeforeAll
  void ingestPolicySubset() {
    ingestionService.ingestResources(
      List.of("file:EPAM_JavaSecureCodingGD.md"), Map.of("testCorpus", true));
    expectedChunks = vectorStore.similaritySearch(SearchRequest.builder()
      .query("Don't Store Secrets cryptographic keys passwords")
      .topK(2)
      .similarityThreshold(0.0)
      .build());
  }

  @BeforeEach
  void setUpClient() {
    chatModel.reset();
    restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  void shouldReturnGroundedAnswerAndActualRetrievedSources_whenPolicyQuestionIsSupported() {
    String policyQuestion = expectedChunks.getFirst().getText();
    List<Document> actualRetrievedChunks = vectorStore.similaritySearch(SearchRequest.builder()
      .query(policyQuestion).topK(2).similarityThreshold(0.8).build());
    RagChatResponse response = chat(policyQuestion, "grounded-conversation");

    assertThat(actualRetrievedChunks).isNotEmpty();
    assertThat(response.response()).startsWith("deterministic-response:");
    assertThat(response.sources()).isNotEmpty();
    assertThat(response.sources()).extracting(source -> source.chunkId())
      .containsExactlyElementsOf(actualRetrievedChunks.stream()
        .map(document -> document.getMetadata().get(IngestionMetadata.CHUNK_ID).toString())
        .distinct().toList());
    assertThat(response.sources()).allSatisfy(source ->
      assertThat(source.documentName()).isEqualTo("EPAM_JavaSecureCodingGD.md"));
    assertThat(actualRetrievedChunks).anySatisfy(document ->
      assertThat(document.getText()).contains("Don't store secrets", "cryptographic keys"));
  }

  @Test
  void shouldOverrideFabricatedAnswerWithExactRefusal_whenQuestionIsUnsupported() {
    String dissimilar = List.of("alpha", "bravo", "charlie", "delta").stream()
      .filter(token -> expectedChunks.stream().noneMatch(document ->
        document.getText().toLowerCase().contains(token)))
      .findFirst().orElseThrow();

    RagChatResponse response = chat(dissimilar, "unsupported-conversation");

    assertThat(response.response()).isEqualTo(RagChatService.REFUSAL);
    assertThat(response.sources()).isEmpty();
    assertThat(chatModel.prompts().getLast().getUserMessage().getText())
      .isEqualTo(RagChatService.REFUSAL);
  }

  @Test
  void shouldRetainBoundedHistoryPerConversationAndKeepConversationIdsIsolated() {
    chat("first-marker", "conversation-a");
    chat("second-marker", "conversation-a");
    var secondPrompt = chatModel.prompts().getLast();
    assertThat(secondPrompt.getInstructions())
      .filteredOn(UserMessage.class::isInstance)
      .extracting(message -> message.getText())
      .anyMatch(text -> text.equals("first-marker"));

    chat("isolated-marker", "conversation-b");
    var isolatedPrompt = chatModel.prompts().getLast();
    assertThat(isolatedPrompt.getInstructions())
      .filteredOn(UserMessage.class::isInstance)
      .extracting(message -> message.getText())
      .noneMatch(text -> text.equals("second-marker"));

    chat("third-marker", "conversation-a");
    chat("fourth-marker", "conversation-a");
    var boundedPrompt = chatModel.prompts().getLast();
    assertThat(boundedPrompt.getInstructions())
      .filteredOn(UserMessage.class::isInstance)
      .extracting(message -> message.getText())
      .noneMatch(text -> text.equals("first-marker"));
  }

  private RagChatResponse chat(String input, String conversationId) {
    return restClient.post().uri("/doc-qa/chat")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new ChatRequest(input, conversationId))
      .retrieve()
      .body(RagChatResponse.class);
  }
}
