package com.epam.prompting_llm.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;

import static org.assertj.core.api.Assertions.assertThat;

class AzureOpenAiChatDefaultsCustomizerTest {

  @Test
  void shouldKeepOnlyTopPWhenProviderMergesRuntimeAndDefaultOptions() {
    // Arrange
    AzureOpenAiChatProperties providerProperties = new AzureOpenAiChatProperties();
    assertThat(providerProperties.getOptions().getTemperature()).isEqualTo(0.7);
    new AzureOpenAiChatDefaultsCustomizer()
      .postProcessBeforeInitialization(providerProperties, "azureOpenAiChatProperties");
    AzureOpenAiChatOptions runtimeOptions = AzureOpenAiChatOptions.builder()
      .deploymentName("test-deployment")
      .topP(0.9)
      .build();

    // Act
    AzureOpenAiChatOptions finalOptions = ModelOptionsUtils.merge(
      runtimeOptions, providerProperties.getOptions(), AzureOpenAiChatOptions.class);

    // Assert
    assertThat(finalOptions.getTopP()).isEqualTo(0.9);
    assertThat(finalOptions.getTemperature()).isNull();
  }
}
