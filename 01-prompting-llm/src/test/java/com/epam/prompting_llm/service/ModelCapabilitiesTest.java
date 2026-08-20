package com.epam.prompting_llm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCapabilitiesTest {

  @ParameterizedTest
  @ValueSource(strings = {
    "gpt-5-mini-2025-08-07",
    "gpt-5-nano-2025-08-07",
    "gpt-5-2025-08-07",
    "gpt-5.4-mini-2026-03-17",
    "gpt-5.4-nano-2026-03-17",
    "gpt-5.4-2026-03-05",
    "gpt-5.4-pro",
    "gpt-5.5-2026-04-24",
    "gpt-5.6-sol-2026-07-09",
    "gpt-5.6-terra-2026-07-09",
    "gpt-5.6-luna-2026-06-25"
  })
  void shouldIdentifyGpt5ReasoningModelDeployments(String deploymentName) {
    assertThat(ModelCapabilities.isReasoningModel(deploymentName)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"gpt-4o", "gpt-4.1-nano-2025-04-14"})
  void shouldNotTreatStandardChatModelsAsReasoningModels(String deploymentName) {
    assertThat(ModelCapabilities.isReasoningModel(deploymentName)).isFalse();
  }

  @Test
  void shouldHandleMissingDeploymentName() {
    assertThat(ModelCapabilities.isReasoningModel(null)).isFalse();
  }
}
