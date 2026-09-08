package com.epam.docqachatbot.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiChatProperties;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAiEmbeddingProperties;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorDeploymentReporterTest {

  private final ch.qos.logback.classic.Logger logger =
    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GeneratorDeploymentReporter.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void shouldLogBothResolvedDeployments_whenTheApplicationBecomesReady() {
    // Arrange: the values a placeholder with a fallback would have resolved to by this point.
    // Act
    reporter("gpt-5-mini-2025-08-07", "text-embedding-3-small-1").reportResolvedDeployments();

    // Assert: the resolved generator is stated by name, not inferred. An evaluation run cites this
    // line as the proof that it exercised the configured default rather than an override.
    assertThat(appender.list)
      .singleElement()
      .satisfies(event -> {
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
          .isEqualTo("Resolved generator deployment=gpt-5-mini-2025-08-07, "
            + "embedding deployment=text-embedding-3-small-1");
      });
  }

  @Test
  void shouldLogTheOverridingDeployment_whenSomethingOverridesTheConfiguredDefault() {
    // Arrange: an override can arrive as an environment variable, a system property or a
    // command-line argument, none of which is visible in application.yml.
    // Act
    reporter("gpt-4o", "text-embedding-3-small-1").reportResolvedDeployments();

    // Assert
    assertThat(appender.list).singleElement().satisfies(event ->
      assertThat(event.getFormattedMessage())
        .contains("Resolved generator deployment=gpt-4o"));
  }

  @Test
  void shouldReportGracefully_whenTheContextHasNoAzureOpenAiProperties() {
    // Arrange: a hermetic test context excludes the Azure OpenAI autoconfiguration entirely.
    GeneratorDeploymentReporter reporter =
      new GeneratorDeploymentReporter(absent(), absent());

    // Act / Assert: a diagnostic must never be the reason a context fails to start.
    reporter.reportResolvedDeployments();
    assertThat(appender.list).singleElement().satisfies(event ->
      assertThat(event.getFormattedMessage())
        .isEqualTo("Resolved generator deployment=<not an Azure OpenAI context>, "
          + "embedding deployment=<not an Azure OpenAI context>"));
  }

  private GeneratorDeploymentReporter reporter(String generator, String embedding) {
    AzureOpenAiChatProperties chat = new AzureOpenAiChatProperties();
    chat.getOptions().setDeploymentName(generator);
    AzureOpenAiEmbeddingProperties embeddingProperties = new AzureOpenAiEmbeddingProperties();
    embeddingProperties.getOptions().setDeploymentName(embedding);
    return new GeneratorDeploymentReporter(present(chat), present(embeddingProperties));
  }

  private static <T> ObjectProvider<T> present(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }

      @Override
      public T getIfAvailable() {
        return value;
      }
    };
  }

  private static <T> ObjectProvider<T> absent() {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        throw new IllegalStateException("no bean");
      }

      @Override
      public T getIfAvailable() {
        return null;
      }
    };
  }
}
