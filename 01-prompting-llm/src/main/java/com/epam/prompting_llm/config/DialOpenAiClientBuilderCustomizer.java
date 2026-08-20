package com.epam.prompting_llm.config;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.BinaryData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAIClientBuilderCustomizer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;

/** Adapts Azure OpenAI requests to the subset of chat-completion fields accepted by DIAL. */
@Component
public class DialOpenAiClientBuilderCustomizer implements AzureOpenAIClientBuilderCustomizer {

  @Override
  public void customize(OpenAIClientBuilder openAIClientBuilder) {
    openAIClientBuilder.addPolicy(new UnsupportedChatCompletionsParametersPolicy());
  }

  /** Removes unsupported optional fields without changing unrelated requests or invalid bodies. */
  public static final class UnsupportedChatCompletionsParametersPolicy implements HttpPipelinePolicy {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final List<String> UNSUPPORTED_FIELDS = List.of("logprobs", "top_logprobs");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
      sanitize(context.getHttpRequest());
      return next.process();
    }

    @Override
    public HttpResponse processSync(HttpPipelineCallContext context, HttpPipelineNextSyncPolicy next) {
      sanitize(context.getHttpRequest());
      return next.processSync();
    }

    public void sanitize(HttpRequest request) {
      if (!isChatCompletionsRequest(request)) {
        return;
      }

      BinaryData body = request.getBodyAsBinaryData();
      if (body == null) {
        return;
      }

      try {
        JsonNode jsonNode = OBJECT_MAPPER.readTree(body.toBytes());
        if (!(jsonNode instanceof ObjectNode objectNode)) {
          return;
        }

        boolean changed = removeUnsupportedFields(objectNode);
        if (changed) {
          request.setBody(BinaryData.fromString(OBJECT_MAPPER.writeValueAsString(objectNode)));
        }
      } catch (IOException ignored) {
        // Keep the original body when it is not parseable JSON.
      }
    }

    public boolean isChatCompletionsRequest(HttpRequest request) {
      return request.getHttpMethod() == HttpMethod.POST
        && request.getUrl().getPath() != null
        && request.getUrl().getPath().contains(CHAT_COMPLETIONS_PATH);
    }

    private boolean removeUnsupportedFields(ObjectNode objectNode) {
      boolean changed = false;
      for (String unsupportedField : UNSUPPORTED_FIELDS) {
        changed |= objectNode.remove(unsupportedField) != null;
      }
      return changed;
    }
  }
}
