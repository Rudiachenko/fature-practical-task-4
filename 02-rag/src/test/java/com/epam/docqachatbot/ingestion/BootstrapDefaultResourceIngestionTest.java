package com.epam.docqachatbot.ingestion;

import com.epam.docqachatbot.DocQaChatbotApplication;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration;
import com.epam.docqachatbot.support.HermeticRagTestConfiguration.RecordingEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the ticket's own documented start command, {@code ./mvnw -pl 02-rag spring-boot:run},
 * can bootstrap-ingest the EPAM policy using the unmodified production default.
 *
 * <p>Only {@code app.documents.default.bootstrap-enabled} is overridden here; {@code
 * app.documents.default.resources} is left completely untouched, so this test resolves the
 * real {@code DocumentsProperties.resources}/{@code application.yml} default value. Maven
 * Surefire's default test working directory is the module basedir ({@code 02-rag/}), the same
 * cwd {@code spring-boot:run} produces, so this is representative of the real production
 * bootstrap path without forking a process or manipulating {@code user.dir}.
 *
 * <p>If the {@code 02-rag/} prefix is ever reintroduced into the default resource location, the
 * bootstrap ingestion below silently fails (a {@code DOCUMENT_NOT_FOUND} resource failure, with
 * {@code bootstrap-fail-fast} left at its default {@code false}) and this test fails because no
 * chunk is ever embedded or indexed.
 */
@ActiveProfiles("test")
@SpringBootTest(
  classes = {DocQaChatbotApplication.class, HermeticRagTestConfiguration.class},
  webEnvironment = SpringBootTest.WebEnvironment.NONE,
  properties = "app.documents.default.bootstrap-enabled=true")
class BootstrapDefaultResourceIngestionTest {

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private RecordingEmbeddingModel embeddingModel;

  @Test
  void shouldIngestRealEpamPolicyOnStartup_whenBootstrapEnabledWithUnmodifiedProductionDefault() {
    // Assert: ApplicationReadyEvent already fired during context startup above, triggering
    // DocumentIngestionService.ensureSampleData() against the real, unmodified
    // app.documents.default.resources production default resolved from this process's cwd.
    assertThat(embeddingModel.documentEmbeddingCount()).isPositive();

    List<Document> matches = vectorStore.similaritySearch(SearchRequest.builder()
      .query("cryptographic keys passwords hostile JVM secrets")
      .topK(5)
      .similarityThreshold(0.0)
      .build());

    assertThat(matches).isNotEmpty();
    assertThat(matches).anySatisfy(document -> assertThat(document.getMetadata())
      .containsEntry(IngestionMetadata.DOCUMENT_NAME, "EPAM_JavaSecureCodingGD.md"));
  }
}
