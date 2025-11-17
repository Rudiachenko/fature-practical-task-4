package com.epam.prompting_llm.service;

import com.epam.prompting_llm.api.model.PromptRequest;
import com.epam.prompting_llm.api.model.PromptResponse;
import com.epam.prompting_llm.config.ChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class ChatProcessor {

  private final String modelName;
  private final ChatClient chatClient;

  public ChatProcessor(ChatModel chatModel, ChatProperties chatProperties) {
    this.modelName = chatProperties.getModelName();

    /*
      TODO Initialize chatMemory.
      Experiment with the message count in chat history.
    */
    ChatMemory chatMemory = null;

    /*
      TODO Initialize ChatClient.builder with system prompt and MessageChatMemoryAdvisor advisor
     */
    this.chatClient = ChatClient.builder(chatModel).build();
  }

  public PromptResponse sendMessage(PromptRequest request) {
    String userInput = request.input();
    String conversationId = StringUtils.hasText(request.conversationId())
      ? request.conversationId()
      : "default-conversation";

    log.info("Processing user input: {} for conversation: {}", userInput, conversationId);

    /*
      TODO Initialize chatOptions with corresponding chatOptions and temperature
     */
    AzureOpenAiChatOptions chatOptions = AzureOpenAiChatOptions.builder()
      .build();

    /*
      TODO Using chatClient, perform requests to an AI Model with corresponding user's prompt, chatOptions and conversationId
     */
    PromptResponse promptResponse = null;

    log.info("AI response: {}, tone: {} for conversation: {}", promptResponse.response(),
      promptResponse.tone(),
      conversationId);
    return promptResponse;
  }

}
