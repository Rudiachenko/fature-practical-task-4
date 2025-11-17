package com.epam.codereviewagent.service;

import com.epam.codereviewagent.util.FileUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/*
  TODO Implement "tool" methods to read the file, retrieve the codebase context, programming language and corresponding code conventions to perform a code review
  Tips: use com.epam.codereviewagent.util.FileUtils and com.epam.codereviewagent.service.ConventionService to delegate corresponding functionality
 */
public class CodeReviewTools {

  // Prompt used to retrieve codebase context
  private static final String SYSTEM_MESSAGE = """
      You are a senior Java developer. You need to review the following code snippet and provide a concise summary of its functionality, key components, and any important details that would help in understanding the codebase. 
      Focus on the overall purpose of the code, its structure, and any notable patterns or practices used. 
      Avoid going into excessive detail; instead, aim to provide a clear and high-level overview that captures the essence of the code. 
      Here is the code snippet: %s
    """;

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

  public String readFile(String path) {
    return "";
  }

  public String getCodebaseContext(String codeSnippet) {
    return "";
  }

  public String retrieveCodeLanguage(String codeSnippet) {
    return "";
  }

  public String retrieveCodeConvention(String language) {
    return "";
  }
}
