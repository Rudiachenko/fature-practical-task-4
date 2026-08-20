package com.epam.prompting_llm.service;

import com.epam.prompting_llm.api.model.PromptRequest;
import com.epam.prompting_llm.config.ChatProperties;
import com.epam.prompting_llm.exception.InvalidSamplingParametersException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplingOptionsResolverTest {

  private ChatProperties properties;
  private SamplingOptionsResolver resolver;

  @BeforeEach
  void setUp() {
    properties = new ChatProperties();
    properties.setModelName("test-deployment");
    properties.setDefaultTemperature(0.6);
    properties.setTopPFallbackTemperature(0.4);
    properties.setTopPSupported(true);
    resolver = new SamplingOptionsResolver(properties);
  }

  @Test
  void shouldApplyTemperatureOnlyWhenTemperatureProvided() {
    // Arrange
    PromptRequest request = request(1.5, null, 10);

    // Act
    ResolvedChatOptions resolved = resolver.resolve(request);

    // Assert
    assertThat(resolved.temperature()).isEqualTo(1.5);
    assertThat(resolved.topP()).isNull();
    assertThat(resolved.options().getTemperature()).isEqualTo(1.5);
    assertThat(resolved.options().getTopP()).isNull();
    assertThat(resolved.options().getMaxTokens()).isEqualTo(10);
    assertThat(resolved.options().getDeploymentName()).isEqualTo("test-deployment");
  }

  @Test
  void shouldApplyTopPOnlyWhenTopPProvidedAndSupported() {
    // Arrange
    PromptRequest request = request(null, 1.0, 20);

    // Act
    ResolvedChatOptions resolved = resolver.resolve(request);

    // Assert
    assertThat(resolved.temperature()).isNull();
    assertThat(resolved.topP()).isEqualTo(1.0);
    assertThat(resolved.options().getTemperature()).isNull();
    assertThat(resolved.options().getTopP()).isEqualTo(1.0);
    assertThat(resolved.maxTokens()).isEqualTo(20);
  }

  @Test
  void shouldApplyFallbackTemperatureWhenTopPIsUnsupported() {
    // Arrange
    properties.setTopPSupported(false);
    PromptRequest request = request(null, 0.9, null);

    // Act
    ResolvedChatOptions resolved = resolver.resolve(request);

    // Assert
    assertThat(resolved.temperature()).isEqualTo(0.4);
    assertThat(resolved.topP()).isNull();
    assertThat(resolved.options().getTemperature()).isEqualTo(0.4);
    assertThat(resolved.options().getTopP()).isNull();
  }

  @Test
  void shouldApplyDefaultTemperatureWhenSamplingParametersAreAbsent() {
    // Arrange
    PromptRequest request = request(null, null, null);

    // Act
    ResolvedChatOptions resolved = resolver.resolve(request);

    // Assert
    assertThat(resolved.temperature()).isEqualTo(0.6);
    assertThat(resolved.topP()).isNull();
    assertThat(resolved.options().getMaxTokens()).isNull();
  }

  @Test
  void shouldOmitSamplingParametersWhenReasoningModelUsesProviderDefaults() {
    // Arrange
    properties.setModelName("gpt-5.4-mini-2026-03-17");
    PromptRequest request = request(null, null, 100);

    // Act
    ResolvedChatOptions resolved = resolver.resolve(request);

    // Assert
    assertThat(resolved.temperature()).isNull();
    assertThat(resolved.topP()).isNull();
    assertThat(resolved.options().getTemperature()).isNull();
    assertThat(resolved.options().getTopP()).isNull();
    assertThat(resolved.options().getMaxTokens()).isEqualTo(100);
  }

  @Test
  void shouldRejectExplicitSamplingParametersWhenReasoningModelDoesNotSupportThem() {
    // Arrange
    properties.setModelName("gpt-5.4-mini-2026-03-17");

    // Act / Assert
    assertThatThrownBy(() -> resolver.resolve(request(0.7, null, 100)))
      .isInstanceOf(InvalidSamplingParametersException.class)
      .hasMessage("The selected reasoning model does not support temperature or topP");
    assertThatThrownBy(() -> resolver.resolve(request(null, 0.9, 100)))
      .isInstanceOf(InvalidSamplingParametersException.class)
      .hasMessage("The selected reasoning model does not support temperature or topP");
  }

  @Test
  void shouldRejectRequestWhenBothSamplingParametersAreProvided() {
    // Arrange
    PromptRequest request = request(0.7, 0.9, 100);

    // Act / Assert
    assertThatThrownBy(() -> resolver.resolve(request))
      .isInstanceOf(InvalidSamplingParametersException.class)
      .hasMessage("Only one of temperature and topP may be provided");
  }

  private PromptRequest request(Double temperature, Double topP, Integer maxTokens) {
    return new PromptRequest("message", "conversation-1", temperature, topP, maxTokens);
  }
}
