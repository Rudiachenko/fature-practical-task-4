package com.epam.docqachatbot.ingestion;

import java.util.List;

public record IngestionSummary(
  int resourcesProcessed,
  int resourcesFailed,
  int chunksWritten,
  List<IngestionFailure> failures) {

  public IngestionSummary {
    failures = List.copyOf(failures);
  }
}
