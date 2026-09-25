package com.epam.codereview.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class TokenUsageTest {

  private static final List<Generation> ONE_GENERATION =
    List.of(new Generation(new AssistantMessage("review text")));

  @Test
  void shouldReturnTheProviderReportedCounts_whenTheResponseCarriesUsage() {
    ChatResponse response = responseWithUsage(new DefaultUsage(812, 45, 857));

    TokenUsage tokenUsage = TokenUsage.from(response);

    assertThat(tokenUsage).isEqualTo(new TokenUsage(812, 45, 857));
  }

  @Test
  void shouldReturnZero_whenTheProviderReportedNoUsage() {
    ChatResponse response = new ChatResponse(ONE_GENERATION);

    TokenUsage tokenUsage = TokenUsage.from(response);

    assertThat(tokenUsage).isEqualTo(TokenUsage.ZERO);
  }

  @Test
  void shouldReturnZero_whenTheResponseHasNoMetadata() {
    ChatResponse response = new ChatResponse(ONE_GENERATION, null);

    TokenUsage tokenUsage = TokenUsage.from(response);

    assertThat(tokenUsage).isEqualTo(TokenUsage.ZERO);
  }

  @Test
  void shouldReturnZero_whenTheMetadataUsageIsNull() {
    ChatResponse response = responseWithUsage(null);

    TokenUsage tokenUsage = TokenUsage.from(response);

    assertThat(tokenUsage).isEqualTo(TokenUsage.ZERO);
  }

  @Test
  void shouldCountEachNullCountAsZero_whenTheProviderReportsOnlySomeCounts() {
    ChatResponse response = responseWithUsage(new CompletionOnlyUsage(45));

    TokenUsage tokenUsage = TokenUsage.from(response);

    assertThat(tokenUsage).isEqualTo(new TokenUsage(0, 45, 0));
  }

  @Test
  void shouldSumEachCountIndependently_whenAddingTwoUsages() {
    TokenUsage firstCall = new TokenUsage(812, 45, 857);
    TokenUsage secondCall = new TokenUsage(900, 20, 920);

    TokenUsage sum = firstCall.plus(secondCall);

    assertThat(sum).isEqualTo(new TokenUsage(1712, 65, 1777));
  }

  @Test
  void shouldLeaveTheUsageUnchanged_whenAddingItToZero() {
    TokenUsage callUsage = new TokenUsage(812, 45, 857);

    TokenUsage sum = TokenUsage.ZERO.plus(callUsage);

    assertThat(sum).isEqualTo(callUsage);
  }

  private static ChatResponse responseWithUsage(Usage usage) {
    return new ChatResponse(ONE_GENERATION, ChatResponseMetadata.builder().usage(usage).build());
  }

  /** A provider that reports a completion count but leaves the prompt and total counts null. */
  private record CompletionOnlyUsage(Integer completionTokens) implements Usage {

    @Override
    public Integer getPromptTokens() {
      return null;
    }

    @Override
    public Integer getCompletionTokens() {
      return completionTokens;
    }

    @Override
    public Integer getTotalTokens() {
      return null;
    }

    @Override
    public Object getNativeUsage() {
      return null;
    }
  }
}
