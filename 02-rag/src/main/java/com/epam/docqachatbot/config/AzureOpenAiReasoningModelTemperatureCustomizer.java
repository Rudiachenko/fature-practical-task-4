package com.epam.docqachatbot.config;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Omits Spring AI's provider-default {@code temperature} and {@code topP} for {@code gpt-5}-family
 * ("reasoning") Azure OpenAI deployments, because DIAL rejects any value other than the model's own
 * default (temperature {@code 1}) for those deployments; a real DIAL probe against
 * {@code gpt-5-mini-2025-08-07} confirmed {@code HTTP 400 unsupported_value} for
 * {@code temperature=0.7} (Spring AI's default). {@code topP} was verified, by decompiling the
 * resolved {@code spring-ai-autoconfigure-model-azure-openai:1.1.2}/{@code spring-ai-azure-openai:1.1.2}
 * classes this project resolves, to already default to {@code null} on a fresh
 * {@link AzureOpenAiChatProperties} (its constructor only calls {@code .deploymentName(...)} and
 * {@code .temperature(DEFAULT_TEMPERATURE)} on the options builder, never {@code .topP(...)}); nulling
 * it here is a no-op for every deployment today, kept explicit as a guard against DIAL rejecting a
 * non-default {@code topP} for reasoning models the same way it rejects {@code temperature} (a class of
 * defect this module already hit once for {@code temperature} alone), and to document that both
 * sampling parameters are intentionally addressed together for this model family.
 * <p>
 * The nulling is deliberately scoped to {@code gpt-5}-prefixed deployment names only, not applied
 * unconditionally. Unlike {@code 01-prompting-llm}'s {@code AzureOpenAiChatDefaultsCustomizer} (which
 * nulls temperature unconditionally because that module's {@code SamplingOptionsResolver} then sets
 * temperature explicitly per request), {@code 02-rag} has no per-request sampling override — a
 * {@code ChatRequest} carries only {@code input}/{@code conversationId} — so an unconditional null
 * would silently move {@code gpt-4o}/{@code gpt-4.1-nano-2025-04-14} onto the provider's own default
 * (reportedly {@code 1.0}) instead of this module's configured {@code 0.7}, increasing answer variance
 * for grounded RAG responses with no compensating control. Conditioning on the deployment name keeps
 * those two deployments' sampling behavior byte-for-byte unchanged.
 * <p>
 * The {@code gpt-5} prefix rule is deliberately mirrored from {@code 01-prompting-llm}'s
 * already-verified {@code ModelCapabilities.isReasoningModel(String)} convention (DIAL exposes the
 * underlying model identifier as the deployment name), not independently invented. This class is a new,
 * independent implementation local to {@code 02-rag} — no cross-module dependency on
 * {@code 01-prompting-llm} is introduced.
 */
@Component
public class AzureOpenAiReasoningModelTemperatureCustomizer implements BeanPostProcessor {

  private static final String GPT_5_MODEL_PREFIX = "gpt-5";

  @Override
  public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
    if (bean instanceof AzureOpenAiChatProperties properties
      && isReasoningModel(properties.getOptions().getDeploymentName())) {
      properties.getOptions().setTemperature(null);
      properties.getOptions().setTopP(null);
    }
    return bean;
  }

  private static boolean isReasoningModel(String deploymentName) {
    return deploymentName != null && deploymentName.startsWith(GPT_5_MODEL_PREFIX);
  }
}
