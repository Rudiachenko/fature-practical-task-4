package com.epam.docqachatbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiEmbeddingProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs which generator and embedding deployments the application actually resolved, once, when it
 * becomes ready.
 * <p>
 * Both deployment names come from a placeholder with a fallback
 * ({@code ${AZURE_OPEN_AI_DEPLOYMENT_NAME:...}}), so the value in {@code application.yml} is the
 * configured default only when nothing overrides it - and an override can arrive as an environment
 * variable, a system property or a command-line argument, none of which is visible in the file.
 * Without this line the only way to tell which model answered is to infer it from the answers,
 * which is not evidence.
 * <p>
 * This exists for two concrete reasons. Operationally, an operator reading the startup log can see
 * the deployment a running instance is billing against without an exposed {@code /actuator/env}
 * (this module exposes only {@code health}). For evaluation, it is the auditable proof that a live
 * run exercised the <em>configured</em> default rather than an override the harness happened to
 * set: {@code evaluation/RESULTS.md} cites this line for exactly that.
 */
@Component
public class GeneratorDeploymentReporter {

  private static final Logger log = LoggerFactory.getLogger(GeneratorDeploymentReporter.class);

  private final ObjectProvider<AzureOpenAiChatProperties> chatProperties;
  private final ObjectProvider<AzureOpenAiEmbeddingProperties> embeddingProperties;

  /**
   * Both dependencies are optional on purpose. A hermetic test context replaces the Azure OpenAI
   * models with test doubles and excludes their autoconfiguration, so these properties are absent
   * there. A diagnostic must never be the reason a context fails to start.
   */
  public GeneratorDeploymentReporter(ObjectProvider<AzureOpenAiChatProperties> chatProperties,
    ObjectProvider<AzureOpenAiEmbeddingProperties> embeddingProperties) {
    this.chatProperties = chatProperties;
    this.embeddingProperties = embeddingProperties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reportResolvedDeployments() {
    AzureOpenAiChatProperties chat = chatProperties.getIfAvailable();
    AzureOpenAiEmbeddingProperties embedding = embeddingProperties.getIfAvailable();
    if (chat == null || embedding == null) {
      log.info("Resolved generator deployment=<not an Azure OpenAI context>, "
        + "embedding deployment=<not an Azure OpenAI context>");
      return;
    }
    log.info("Resolved generator deployment={}, embedding deployment={}",
      chat.getOptions().getDeploymentName(),
      embedding.getOptions().getDeploymentName());
  }
}
