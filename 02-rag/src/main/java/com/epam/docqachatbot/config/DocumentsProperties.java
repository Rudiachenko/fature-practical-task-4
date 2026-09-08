package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.documents.default")
public class DocumentsProperties {

  private boolean bootstrapEnabled = false;
  private boolean bootstrapFailFast = false;

  /**
   * List of default document resource locations to ingest on startup. Relative to the process
   * working directory, which is the module basedir ({@code 02-rag/}) when started via the
   * ticket's own canonical start command, {@code ./mvnw -pl 02-rag spring-boot:run}. When
   * starting the executable JAR from the repository root instead, override this property (see
   * {@code 02-rag/RUNBOOK.md}'s "Build and run" section).
   */
  private List<String> resources = List.of("file:EPAM_JavaSecureCodingGD.md");
}

