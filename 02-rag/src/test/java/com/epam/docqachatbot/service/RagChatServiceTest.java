package com.epam.docqachatbot.service;

import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.api.model.RagSource;
import com.epam.docqachatbot.ingestion.IngestionMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagChatServiceTest {

  @Test
  void shouldReturnAnswerAndOrderedUniqueSources_whenContextHasValidProvenance() {
    RagChatService service = service();
    Document first = document("first", "policy.md", "chunk-1");
    Document duplicate = document("duplicate", "policy.md", "chunk-1");
    Document second = document("second", "guide.md", "chunk-2");

    RagChatResponse result = service.toRagResponse(response(
      "Use parameterized queries.", List.of(first, duplicate, second)));

    assertThat(result.response()).isEqualTo("Use parameterized queries.");
    assertThat(result.sources()).containsExactly(
      new RagSource("policy.md", "chunk-1"), new RagSource("guide.md", "chunk-2"));
  }

  @Test
  void shouldReturnExactRefusalDespiteFabricatedText_whenContextIsAbsentEmptyNullOrWrong() {
    RagChatService service = service();

    assertRefusal(service.toRagResponse(response("fabricated", null)));
    assertRefusal(service.toRagResponse(response("fabricated", List.of())));
    assertRefusal(service.toRagResponse(response("fabricated", "not-documents")));
    assertRefusal(service.toRagResponse(response("fabricated", List.of("not-a-document"))));
  }

  @Test
  void shouldForceEmptySources_whenResponseTextEqualsExactRefusalDespiteRetrievedDocuments() {
    RagChatService service = service();
    Document irrelevantFirst = document("unrelated Java access-control content", "policy.md", "chunk-1");
    Document irrelevantSecond = document("more unrelated content", "policy.md", "chunk-2");

    RagChatResponse result = service.toRagResponse(response(
      RagChatService.REFUSAL, List.of(irrelevantFirst, irrelevantSecond)));

    assertRefusal(result);
  }

  @Test
  void shouldNormalizeToCanonicalRefusalAndForceEmptySources_whenResponseTextHasSurroundingWhitespace() {
    RagChatService service = service();
    Document irrelevant = document("unrelated content", "policy.md", "chunk-1");

    RagChatResponse result = service.toRagResponse(response(
      "  " + RagChatService.REFUSAL + "  ", List.of(irrelevant)));

    assertRefusal(result);
  }

  @Test
  void shouldKeepNonEmptySources_whenResponseTextIsAParaphrasedDeclineRatherThanTheExactRefusal() {
    RagChatService service = service();
    Document hit = document("relevant content", "policy.md", "chunk-1");

    RagChatResponse result = service.toRagResponse(response(
      "I cannot answer this from the given context.", List.of(hit)));

    assertThat(result.response()).isEqualTo("I cannot answer this from the given context.");
    assertThat(result.sources()).containsExactly(new RagSource("policy.md", "chunk-1"));
  }

  @Test
  void shouldFailSafely_whenRetrievedDocumentProvenanceIsIncomplete() {
    RagChatService service = service();
    Document missingChunk = Document.builder().text("context")
      .metadata(IngestionMetadata.DOCUMENT_NAME, "policy.md").build();

    assertThatThrownBy(() -> service.toRagResponse(response("answer", List.of(missingChunk))))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Retrieved document provenance is incomplete");
  }

  @Test
  void shouldFailSafely_whenModelResponseOrGroundedAnswerIsMissing() {
    RagChatService service = service();
    Document hit = document("context", "policy.md", "chunk-1");

    assertThatThrownBy(() -> service.toRagResponse(null))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Chat provider returned no response");
    assertThatThrownBy(() -> service.toRagResponse(response(null, List.of(hit))))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Chat provider returned no answer");
  }

  @Test
  void shouldUseResolvedConversationIdOriginalInputAndCommitReturnedRefusal_whenChatRuns() {
    ChatMemory memory = memory(6);
    AtomicReference<String> currentUser = new AtomicReference<>();
    ChatModel model = prompt -> {
      currentUser.set(prompt.getUserMessage().getText());
      return response("fabricated", null);
    };
    RagChatService service = new RagChatService(client(model, memory), memory);

    RagChatResponse result = service.chat(new ChatRequest("original input", "  session-a  "));

    assertRefusal(result);
    assertThat(currentUser).hasValue("original input");
    assertThat(memory.get("session-a")).hasSize(2);
    assertThat(memory.get("session-a").getLast()).isInstanceOf(AssistantMessage.class)
      .extracting(message -> ((AssistantMessage) message).getText())
      .isEqualTo(RagChatService.REFUSAL);
    assertThat(memory.get("  session-a  ")).isEmpty();
  }

  @Test
  void shouldRestorePriorMemoryAndKeepConversationIsolation_whenModelFails() {
    ChatMemory memory = memory(6);
    AtomicBoolean fail = new AtomicBoolean();
    ChatModel model = prompt -> {
      if (fail.get()) {
        throw new IllegalStateException("secret provider failure");
      }
      return response("fabricated", null);
    };
    RagChatService service = new RagChatService(client(model, memory), memory);
    service.chat(new ChatRequest("first", "conversation-a"));
    service.chat(new ChatRequest("other", "conversation-b"));
    List<org.springframework.ai.chat.messages.Message> before =
      List.copyOf(memory.get("conversation-a"));
    fail.set(true);

    assertThatThrownBy(() -> service.chat(new ChatRequest("failed", "conversation-a")))
      .isInstanceOf(IllegalStateException.class);
    assertThat(memory.get("conversation-a")).containsExactlyElementsOf(before);
    assertThat(memory.get("conversation-b")).hasSize(2);
  }

  @Test
  void shouldRestorePriorMemory_whenFullChatHasNullOrBlankOutputOrInvalidProvenance() {
    assertRollback(null);
    assertRollback(response(null, List.of(document("context", "policy.md", "chunk-1"))));
    assertRollback(response("   ", List.of(document("context", "policy.md", "chunk-1"))));
    Document incomplete = Document.builder().text("context")
      .metadata(IngestionMetadata.DOCUMENT_NAME, "policy.md").build();
    assertRollback(response("grounded", List.of(incomplete)));
  }

  @Test
  void shouldSerializeSameConversationTurnsWithoutLosingHistory_whenCallsOverlap()
    throws Exception {
    ChatMemory memory = memory(8);
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    AtomicBoolean secondEnteredEarly = new AtomicBoolean();
    ChatModel model = prompt -> {
      if (first.compareAndSet(true, false)) {
        firstEntered.countDown();
        await(releaseFirst);
      } else if (releaseFirst.getCount() > 0) {
        secondEnteredEarly.set(true);
      }
      return response("fabricated", null);
    };
    RagChatService service = new RagChatService(client(model, memory), memory);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<RagChatResponse> firstTurn = executor.submit(
        () -> service.chat(new ChatRequest("first", "shared")));
      assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
      Future<RagChatResponse> secondTurn = executor.submit(() -> {
        secondStarted.countDown();
        return service.chat(new ChatRequest("second", "shared"));
      });
      assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(150);
      assertThat(secondEnteredEarly).isFalse();
      releaseFirst.countDown();
      assertRefusal(firstTurn.get(2, TimeUnit.SECONDS));
      assertRefusal(secondTurn.get(2, TimeUnit.SECONDS));
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(memory.get("shared")).extracting(message -> message.getText())
      .containsExactly("first", RagChatService.REFUSAL, "second", RagChatService.REFUSAL);
  }

  @Test
  void shouldAllowDifferentConversationIdsToProgressConcurrently_whenCallsOverlap()
    throws Exception {
    ChatMemory memory = memory(8);
    CountDownLatch bothEntered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    ChatModel model = prompt -> {
      bothEntered.countDown();
      await(release);
      return response("fabricated", null);
    };
    RagChatService service = new RagChatService(client(model, memory), memory);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<RagChatResponse> first = executor.submit(
        () -> service.chat(new ChatRequest("one", "conversation-1")));
      Future<RagChatResponse> second = executor.submit(
        () -> service.chat(new ChatRequest("two", "conversation-2")));
      assertThat(bothEntered.await(2, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      assertRefusal(first.get(2, TimeUnit.SECONDS));
      assertRefusal(second.get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    assertThat(memory.get("conversation-1")).hasSize(2);
    assertThat(memory.get("conversation-2")).hasSize(2);
  }

  @Test
  void shouldEvictOldTurns_whenMemoryWindowIsExceeded() {
    ChatMemory memory = memory(4);
    ChatModel model = prompt -> response("fabricated", null);
    RagChatService service = new RagChatService(client(model, memory), memory);

    service.chat(new ChatRequest("first", "bounded"));
    service.chat(new ChatRequest("second", "bounded"));
    service.chat(new ChatRequest("third", "bounded"));

    assertThat(memory.get("bounded")).hasSize(4);
    assertThat(memory.get("bounded")).extracting(message -> message.getText())
      .doesNotContain("first")
      .contains("second", "third");
  }

  private RagChatService service() {
    ChatMemory memory = memory(4);
    return new RagChatService(client(prompt -> response("unused", null), memory), memory);
  }

  private ChatClient client(ChatModel model, ChatMemory memory) {
    return ChatClient.builder(model)
      .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
      .build();
  }

  private ChatMemory memory(int maxMessages) {
    return MessageWindowChatMemory.builder()
      .chatMemoryRepository(new InMemoryChatMemoryRepository())
      .maxMessages(maxMessages)
      .build();
  }

  private Document document(String text, String documentName, String chunkId) {
    return Document.builder().text(text).metadata(Map.of(
      IngestionMetadata.DOCUMENT_NAME, documentName,
      IngestionMetadata.CHUNK_ID, chunkId)).build();
  }

  private ChatResponse response(String text, Object context) {
    ChatResponse.Builder builder = ChatResponse.builder();
    if (text != null) {
      builder.generations(List.of(new Generation(new AssistantMessage(text))));
    } else {
      builder.generations(List.of());
    }
    if (context != null) {
      builder.metadata(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, context);
    }
    return builder.build();
  }

  private void assertRefusal(RagChatResponse response) {
    assertThat(response.response()).isEqualTo(RagChatService.REFUSAL);
    assertThat(response.sources()).isEmpty();
  }

  private void assertRollback(ChatResponse failingResponse) {
    ChatMemory memory = memory(6);
    AtomicBoolean fail = new AtomicBoolean();
    ChatModel model = prompt -> fail.get() ? failingResponse : response("fabricated", null);
    RagChatService service = new RagChatService(client(model, memory), memory);
    service.chat(new ChatRequest("committed", "rollback"));
    List<org.springframework.ai.chat.messages.Message> snapshot = List.copyOf(memory.get("rollback"));
    fail.set(true);

    assertThatThrownBy(() -> service.chat(new ChatRequest("rejected", "rollback")))
      .isInstanceOf(IllegalStateException.class);
    assertThat(memory.get("rollback")).containsExactlyElementsOf(snapshot);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test latch");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for test latch", exception);
    }
  }
}
