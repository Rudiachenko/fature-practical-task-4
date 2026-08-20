package com.epam.prompting_llm.service;

/** Centralizes deployment-name checks that affect request option compatibility. */
public final class ModelCapabilities {

  private static final String GPT_5_MODEL_PREFIX = "gpt-5";

  private ModelCapabilities() {
  }

  public static boolean isReasoningModel(String deploymentName) {
    // DIAL exposes the underlying model identifier as the deployment name.
    return deploymentName != null && deploymentName.startsWith(GPT_5_MODEL_PREFIX);
  }
}
