package com.epam.docqachatbot.service;

import com.epam.docqachatbot.config.AzureProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
public class AzureModelService {

  private final RestClient restClient;
  private final String apiKey;
  private final String endpoint;

  public AzureModelService(AzureProperties azureProperties) {
    String endpoint = azureProperties.getEndpoint();
    String apiKey = azureProperties.getApiKey();

    this.endpoint = StringUtils.hasText(endpoint) ? endpoint.strip() : null;
    this.apiKey = StringUtils.hasText(apiKey) ? apiKey.strip() : null;

    RestClient.Builder builder = RestClient.builder()
      .baseUrl(normalizeEndpoint(this.endpoint))
      .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    if (this.apiKey != null) {
      builder.defaultHeader("Api-Key", this.apiKey);
    }
    this.restClient = builder.build();
  }

  public String listModels() {
    if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(apiKey)) {
      throw new IllegalStateException("AZURE_OPEN_AI_ENDPOINT and AZURE_OPEN_AI_KEY must be configured to list models");
    }

    try {
      return restClient.get()
        .uri("/openai/models")
        .retrieve()
        .body(String.class);
    } catch (RestClientException ex) {
      log.error("Failed to fetch models from Azure endpoint {}", endpoint, ex);
      throw new IllegalStateException("Failed to fetch models from Azure OpenAI", ex);
    }
  }

  private String normalizeEndpoint(String value) {
    if (!StringUtils.hasText(value)) {
      return value;
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}


