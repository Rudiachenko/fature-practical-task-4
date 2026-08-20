package com.epam.prompting_llm.service;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * Buffers memory writes for the current thread until a model turn is explicitly committed.
 */
public final class TransactionalChatMemory implements ChatMemory {

  private final ChatMemory delegate;
  private final ThreadLocal<PendingTransaction> currentTransaction = new ThreadLocal<>();

  public TransactionalChatMemory(ChatMemory delegate) {
    this.delegate = delegate;
  }

  public void begin(String conversationId) {
    Assert.hasText(conversationId, "conversationId must not be blank");
    Assert.state(currentTransaction.get() == null, "A chat-memory transaction is already active");
    currentTransaction.set(new PendingTransaction(conversationId, new ArrayList<>()));
  }

  public void commit() {
    PendingTransaction transaction = requiredTransaction();
    try {
      if (!transaction.messages().isEmpty()) {
        delegate.add(transaction.conversationId(), List.copyOf(transaction.messages()));
      }
    } finally {
      currentTransaction.remove();
    }
  }

  public void rollback() {
    currentTransaction.remove();
  }

  @Override
  public void add(@NonNull String conversationId, @NonNull List<Message> messages) {
    PendingTransaction transaction = currentTransaction.get();
    if (transaction != null && transaction.conversationId().equals(conversationId)) {
      transaction.messages().addAll(messages);
      return;
    }
    delegate.add(conversationId, messages);
  }

  @Override
  public @NonNull List<Message> get(@NonNull String conversationId) {
    List<Message> messages = new ArrayList<>(delegate.get(conversationId));
    PendingTransaction transaction = currentTransaction.get();
    if (transaction != null && transaction.conversationId().equals(conversationId)) {
      messages.addAll(transaction.messages());
    }
    return List.copyOf(messages);
  }

  @Override
  public void clear(@NonNull String conversationId) {
    PendingTransaction transaction = currentTransaction.get();
    Assert.state(transaction == null || !transaction.conversationId().equals(conversationId),
      "Cannot clear chat memory while a transaction is active");
    delegate.clear(conversationId);
  }

  private PendingTransaction requiredTransaction() {
    PendingTransaction transaction = currentTransaction.get();
    Assert.state(transaction != null, "No chat-memory transaction is active");
    return transaction;
  }

  private record PendingTransaction(String conversationId, List<Message> messages) {
  }
}
