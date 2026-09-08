package com.epam.docqachatbot.ingestion;

public record IngestionFailure(
  String location,
  String code) {
}
