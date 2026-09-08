package com.epam.docqachatbot.api.model;

public record ApiViolation(
  String field,
  String message) {
}
