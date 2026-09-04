package com.epam.codereviewagent.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Hermetic {@link ChatModel} test double, ported from {@code 02-rag}'s
 * {@code HermeticRagTestConfiguration.RecordingChatModel} idiom: records every {@link Prompt} it
 * receives (so tests can assert, among other things, that a captured prompt carries no tool
 * callbacks) and returns a deterministic, test-configurable canned response instead of ever
 * contacting a real model provider.
 */
public final class RecordingChatModel implements ChatModel {

  private final CopyOnWriteArrayList<Prompt> prompts = new CopyOnWriteArrayList<>();

  private volatile Function<Prompt, String> responseFunction = prompt -> "deterministic-response";

  @Override
  public ChatResponse call(Prompt prompt) {
    prompts.add(prompt);
    String response = responseFunction.apply(prompt);
    return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
  }

  /**
   * @return an immutable snapshot of every {@link Prompt} passed to {@link #call(Prompt)} so far,
   *         in call order
   */
  public List<Prompt> prompts() {
    return List.copyOf(prompts);
  }

  /** Clears recorded prompts; does not reset the configured response function. */
  public void reset() {
    prompts.clear();
  }

  /** Configures every subsequent {@link #call(Prompt)} to return the exact same fixed response. */
  public void setResponse(String response) {
    this.responseFunction = prompt -> response;
  }

  /**
   * Configures every subsequent {@link #call(Prompt)} to derive its response from the prompt
   * itself.
   */
  public void setResponseFunction(Function<Prompt, String> responseFunction) {
    this.responseFunction = responseFunction;
  }
}
