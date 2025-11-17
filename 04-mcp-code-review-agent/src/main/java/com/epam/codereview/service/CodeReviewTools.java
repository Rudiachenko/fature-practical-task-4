package com.epam.codereview.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/*
  TODO Implement "tool" methods to identify programming language and retrieve corresponding code conventions to perform a code review
  Tips: use com.epam.codereviewagent.service.ConventionService to delegate corresponding functionality
 */
public class CodeReviewTools {

  /*
    TODO Create prompt to identify the programming language based on the provided code snippet
    Tips: specify the expected output format
   */
  private static final String PROGRAMMING_LANGUAGE_PROMPT = "";

  private final ChatModel chatModel;
  private final ConventionService conventionService;

  public CodeReviewTools(ChatModel chatModel, ConventionService conventionService) {
    this.chatModel = chatModel;
    this.conventionService = conventionService;
  }

  public String retrieveCodeLanguage(String codeSnippet) {
    return "";
  }

  public String retrieveCodeConvention(String language) {
    return "";
  }
}
