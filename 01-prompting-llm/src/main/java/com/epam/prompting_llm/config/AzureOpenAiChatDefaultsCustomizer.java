package com.epam.prompting_llm.config;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Removes Spring AI's default temperature so request-level sampling settings remain authoritative.
 */
@Component
public class AzureOpenAiChatDefaultsCustomizer implements BeanPostProcessor {

  @Override
  public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
    if (bean instanceof AzureOpenAiChatProperties properties) {
      properties.getOptions().setTemperature(null);
    }
    return bean;
  }
}
