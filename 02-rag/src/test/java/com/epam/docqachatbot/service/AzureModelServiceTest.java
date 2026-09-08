package com.epam.docqachatbot.service;

import com.epam.docqachatbot.config.AzureProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AzureModelServiceTest {

  @Test
  void shouldCreateServiceWithoutConfiguredCredentials_andRejectModelLookup() {
    AzureProperties properties = new AzureProperties();
    properties.setEndpoint(null);
    properties.setApiKey(null);

    assertThatCode(() -> new AzureModelService(properties)).doesNotThrowAnyException();
    AzureModelService service = new AzureModelService(properties);
    assertThatIllegalStateException().isThrownBy(service::listModels)
      .withMessage("AZURE_OPEN_AI_ENDPOINT and AZURE_OPEN_AI_KEY must be configured to list models");
  }
}
