package com.epam.codereviewagent.service;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.config.CodeReviewProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CodeReviewReactAgent {

  private final ChatModel chatModel;
  private final ChatOptions chatOptions;
  private final ToolCallingManager toolCallingManager;
  private final CodeReviewProperties codeReviewProperties;

  private String loadSystemPrompt() {
    try {
      return StreamUtils.copyToString(codeReviewProperties.getSystemPrompt().getInputStream(),
        StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load system prompt from: " + codeReviewProperties.getSystemPrompt(),
        e);
    }
  }

  public CodeReviewResponse interact(String userInput) {
    String systemPrompt = loadSystemPrompt();

    /*
      TODO Implement code review agent workflow
    */

    return null;
  }
}
