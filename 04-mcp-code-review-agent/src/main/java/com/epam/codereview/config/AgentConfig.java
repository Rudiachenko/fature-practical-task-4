package com.epam.codereview.config;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.epam.codereview.service.CodeReviewTools;
import com.epam.codereview.service.ConventionService;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentConfig {

  @Bean
  @Primary
  public ChatModel chatModel(OpenAIClientBuilder openAIClientBuilder, ToolCallingManager toolCallingManager) {
    return AzureOpenAiChatModel.builder()
      /*
        TODO Initialize the AzureOpenAiChatModel.builder()
       */
      .openAIClientBuilder(openAIClientBuilder)
      .build();
  }

  @Bean
  public ChatOptions chatOptions(
    ChatModel chatModel,
    ConventionService conventionService,
    SyncMcpToolCallbackProvider mcpToolCallbackProvider) {

    return AzureOpenAiChatOptions.builder()
      /*
        TODO Initialize the AzureOpenAiChatOptions.builder() with custom and MCP tool callbacks
        Tips: MCP tool callbacks can be retrieved from mcpToolCallbackProvider
       */
      .build();
  }
}
