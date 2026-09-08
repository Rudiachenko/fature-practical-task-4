package com.epam.docqachatbot.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;

import static org.assertj.core.api.Assertions.assertThat;

class AzureOpenAiReasoningModelTemperatureCustomizerTest {

  private final AzureOpenAiReasoningModelTemperatureCustomizer customizer =
    new AzureOpenAiReasoningModelTemperatureCustomizer();

  @Test
  void shouldDefaultTemperatureAndLeaveTopPNull_whenPropertiesAreFreshlyConstructed() {
    // Arrange & Act
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();

    // Assert: verified real provider defaults (not assumed) before the customizer ever runs.
    assertThat(properties.getOptions().getTemperature()).isEqualTo(0.7);
    assertThat(properties.getOptions().getTopP()).isNull();
  }

  @Test
  void shouldNullTemperatureAndTopP_whenDeploymentNameIsGpt5Prefixed() {
    // Arrange
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();
    properties.getOptions().setDeploymentName("gpt-5-mini-2025-08-07");
    assertThat(properties.getOptions().getTemperature()).isEqualTo(0.7);

    // Act
    Object result = customizer.postProcessBeforeInitialization(properties, "azureOpenAiChatProperties");

    // Assert
    assertThat(result).isSameAs(properties);
    assertThat(properties.getOptions().getTemperature()).isNull();
    assertThat(properties.getOptions().getTopP()).isNull();
  }

  @Test
  void shouldKeepDefaultTemperatureAndTopP_whenDeploymentNameIsGpt4o() {
    // Arrange
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();
    properties.getOptions().setDeploymentName("gpt-4o");

    // Act
    customizer.postProcessBeforeInitialization(properties, "azureOpenAiChatProperties");

    // Assert
    assertThat(properties.getOptions().getTemperature()).isEqualTo(0.7);
    assertThat(properties.getOptions().getTopP()).isNull();
  }

  @Test
  void shouldKeepDefaultTemperatureAndTopP_whenDeploymentNameIsGpt41Nano() {
    // Arrange
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();
    properties.getOptions().setDeploymentName("gpt-4.1-nano-2025-04-14");

    // Act
    customizer.postProcessBeforeInitialization(properties, "azureOpenAiChatProperties");

    // Assert
    assertThat(properties.getOptions().getTemperature()).isEqualTo(0.7);
    assertThat(properties.getOptions().getTopP()).isNull();
  }

  @Test
  void shouldKeepDefaultTemperature_whenDeploymentNameIsNull() {
    // Arrange
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();
    properties.getOptions().setDeploymentName(null);

    // Act
    customizer.postProcessBeforeInitialization(properties, "azureOpenAiChatProperties");

    // Assert
    assertThat(properties.getOptions().getTemperature()).isEqualTo(0.7);
    assertThat(properties.getOptions().getTopP()).isNull();
  }

  @Test
  void shouldKeepDefaultTemperature_whenDeploymentNameIsBlank() {
    // Arrange
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();
    properties.getOptions().setDeploymentName("   ");

    // Act
    customizer.postProcessBeforeInitialization(properties, "azureOpenAiChatProperties");

    // Assert: the deployment-name check is a null-safe prefix match, not a blank-aware one, so a
    // blank (non-null, non-"gpt-5"-prefixed) name is defensively left untouched, same as any other
    // non-matching name.
    assertThat(properties.getOptions().getTemperature()).isEqualTo(0.7);
    assertThat(properties.getOptions().getTopP()).isNull();
  }

  @Test
  void shouldReturnBeanUnchanged_whenBeanIsNotAzureOpenAiChatProperties() {
    // Arrange
    Object bean = new Object();

    // Act
    Object result = customizer.postProcessBeforeInitialization(bean, "someOtherBean");

    // Assert
    assertThat(result).isSameAs(bean);
  }

  @Test
  void shouldMergeToNullTemperatureAndTopP_whenDeploymentNameIsGpt5Prefixed() {
    // Arrange: mirrors Module 1's own AzureOpenAiChatDefaultsCustomizerTest merge-level proof,
    // verifying the real Spring AI provider-default-merge behavior this fix depends on.
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();
    properties.getOptions().setDeploymentName("gpt-5-mini-2025-08-07");
    customizer.postProcessBeforeInitialization(properties, "azureOpenAiChatProperties");
    AzureOpenAiChatOptions runtimeOptions = AzureOpenAiChatOptions.builder()
      .deploymentName("gpt-5-mini-2025-08-07")
      .build();

    // Act
    AzureOpenAiChatOptions finalOptions = ModelOptionsUtils.merge(
      runtimeOptions, properties.getOptions(), AzureOpenAiChatOptions.class);

    // Assert
    assertThat(finalOptions.getTemperature()).isNull();
    assertThat(finalOptions.getTopP()).isNull();
  }

  @Test
  void shouldMergeToDefaultTemperatureAndNullTopP_whenDeploymentNameIsGpt4o() {
    // Arrange: proves gpt-4o's merged sampling behavior is byte-for-byte unchanged by this fix.
    AzureOpenAiChatProperties properties = new AzureOpenAiChatProperties();
    properties.getOptions().setDeploymentName("gpt-4o");
    customizer.postProcessBeforeInitialization(properties, "azureOpenAiChatProperties");
    AzureOpenAiChatOptions runtimeOptions = AzureOpenAiChatOptions.builder()
      .deploymentName("gpt-4o")
      .build();

    // Act
    AzureOpenAiChatOptions finalOptions = ModelOptionsUtils.merge(
      runtimeOptions, properties.getOptions(), AzureOpenAiChatOptions.class);

    // Assert
    assertThat(finalOptions.getTemperature()).isEqualTo(0.7);
    assertThat(finalOptions.getTopP()).isNull();
  }
}
