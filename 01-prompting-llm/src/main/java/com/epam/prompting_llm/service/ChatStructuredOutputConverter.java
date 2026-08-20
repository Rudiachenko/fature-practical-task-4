package com.epam.prompting_llm.service;

import com.epam.prompting_llm.api.model.StructuredChatResponse;
import com.epam.prompting_llm.exception.StructuredOutputException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Converts model JSON into the strict response contract and exposes its provider JSON schema.
 */
@Component
public class ChatStructuredOutputConverter
  implements StructuredOutputConverter<StructuredChatResponse> {

  private static final String RESPONSE_FORMAT_NAME = "structured_chat_response";
  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
    .findAndAddModules()
    .build();

  private final BeanOutputConverter<StructuredChatResponse> schemaConverter =
    new BeanOutputConverter<>(StructuredChatResponse.class);
  private final AzureOpenAiResponseFormat responseFormat = buildResponseFormat();

  @Override
  public StructuredChatResponse convert(String source) {
    if (!StringUtils.hasText(source)) {
      throw new StructuredOutputException();
    }

    try {
      StructuredChatResponse converted = OBJECT_MAPPER.readValue(source, StructuredChatResponse.class);
      return validate(converted);
    } catch (JsonProcessingException exception) {
      throw new StructuredOutputException(exception);
    }
  }

  @Override
  public @NonNull String getFormat() {
    return schemaConverter.getFormat();
  }

  public AzureOpenAiResponseFormat responseFormat() {
    return responseFormat;
  }

  private StructuredChatResponse validate(StructuredChatResponse converted) {
    if (converted == null || !StringUtils.hasText(converted.response()) || converted.tone() == null) {
      throw new StructuredOutputException();
    }
    return converted;
  }

  private AzureOpenAiResponseFormat buildResponseFormat() {
    return AzureOpenAiResponseFormat.builder()
      .type(AzureOpenAiResponseFormat.Type.JSON_SCHEMA)
      .jsonSchema(AzureOpenAiResponseFormat.JsonSchema.builder()
        .name(RESPONSE_FORMAT_NAME)
        .schema(schemaConverter.getJsonSchemaMap())
        .strict(true)
        .build())
      .build();
  }
}
