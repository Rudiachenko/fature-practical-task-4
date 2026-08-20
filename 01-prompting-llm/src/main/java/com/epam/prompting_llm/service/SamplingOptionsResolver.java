package com.epam.prompting_llm.service;

import com.epam.prompting_llm.api.model.PromptRequest;
import com.epam.prompting_llm.config.ChatProperties;
import com.epam.prompting_llm.exception.InvalidSamplingParametersException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.stereotype.Component;

/** Resolves mutually exclusive sampling controls and provider-specific fallback behavior. */
@Component
@RequiredArgsConstructor
public class SamplingOptionsResolver {

  private static final String UNSUPPORTED_REASONING_MODEL_SAMPLING_MESSAGE =
    "The selected reasoning model does not support temperature or topP";

  private final ChatProperties chatProperties;

  public ResolvedChatOptions resolve(PromptRequest request) {
    validateSamplingParameters(request);
    boolean reasoningModel = ModelCapabilities.isReasoningModel(chatProperties.getModelName());
    validateModelCompatibility(request, reasoningModel);

    AzureOpenAiChatOptions.Builder optionsBuilder = baseOptions(request);
    return reasoningModel
      ? reasoningModelOptions(optionsBuilder, request.maxTokens())
      : samplingOptions(optionsBuilder, request);
  }

  private void validateSamplingParameters(PromptRequest request) {
    if (request.temperature() != null && request.topP() != null) {
      throw new InvalidSamplingParametersException();
    }
  }

  private void validateModelCompatibility(PromptRequest request, boolean reasoningModel) {
    if (reasoningModel && (request.temperature() != null || request.topP() != null)) {
      throw new InvalidSamplingParametersException(
        UNSUPPORTED_REASONING_MODEL_SAMPLING_MESSAGE);
    }
  }

  private AzureOpenAiChatOptions.Builder baseOptions(PromptRequest request) {
    return AzureOpenAiChatOptions.builder()
      .deploymentName(chatProperties.getModelName())
      .maxTokens(request.maxTokens());
  }

  private ResolvedChatOptions reasoningModelOptions(
    AzureOpenAiChatOptions.Builder optionsBuilder,
    Integer maxTokens) {
    return resolvedOptions(optionsBuilder, null, null, maxTokens);
  }

  private ResolvedChatOptions samplingOptions(
    AzureOpenAiChatOptions.Builder optionsBuilder,
    PromptRequest request) {
    if (request.temperature() != null) {
      return temperatureOptions(optionsBuilder, request.temperature(), request.maxTokens());
    }
    if (request.topP() == null) {
      return temperatureOptions(
        optionsBuilder, chatProperties.getDefaultTemperature(), request.maxTokens());
    }
    if (chatProperties.isTopPSupported()) {
      optionsBuilder.topP(request.topP());
      return resolvedOptions(optionsBuilder, null, request.topP(), request.maxTokens());
    }
    return temperatureOptions(
      optionsBuilder, chatProperties.getTopPFallbackTemperature(), request.maxTokens());
  }

  private ResolvedChatOptions temperatureOptions(
    AzureOpenAiChatOptions.Builder optionsBuilder,
    Double temperature,
    Integer maxTokens) {
    optionsBuilder.temperature(temperature);
    return resolvedOptions(optionsBuilder, temperature, null, maxTokens);
  }

  private ResolvedChatOptions resolvedOptions(
    AzureOpenAiChatOptions.Builder optionsBuilder,
    Double temperature,
    Double topP,
    Integer maxTokens) {
    return new ResolvedChatOptions(optionsBuilder.build(), temperature, topP, maxTokens);
  }
}
