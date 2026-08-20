package com.epam.prompting_llm.service;

import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;

public record ResolvedChatOptions(
  AzureOpenAiChatOptions options,
  Double temperature,
  Double topP,
  Integer maxTokens
) {

}
