package com.epam.docqachatbot.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Hashing {

  private Hashing() {
  }

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  public static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
