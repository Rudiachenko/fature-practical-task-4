package com.epam.docqachatbot.ingestion;

import com.epam.docqachatbot.config.DocumentProcessingProperties;
import org.springframework.ai.document.Document;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Review-only tool. It is not part of the application and is not compiled by the Maven build:
 * it lives under {@code 02-rag/evaluation/tools} precisely so that it cannot affect production
 * or test code, and it declares the {@code com.epam.docqachatbot.ingestion} package only to
 * reach that package's package-private reader and hashing helpers.
 *
 * <p>It rebuilds the ingestion manifest of one Markdown corpus offline, by driving the committed
 * ingestion pipeline ({@code DocumentReaderFactory} -&gt; {@code ProvenanceDocumentTransformer})
 * with the shipped {@code app.documents.processing} defaults. No embedding call, no vector store
 * and no network access are involved: {@code chunkId} is
 * {@code sha256(documentId | chunkIndex | chunkText | embeddingModel)}, so the manifest is a pure
 * function of the corpus bytes, the ingest location and the embedding-model name.
 *
 * <p>The output is deterministic and carries no timestamp or environment data, so re-running it
 * on the same corpus reproduces the committed artifact byte for byte.
 *
 * <p>Requires JDK 21. A {@code java} on {@code PATH} may be older, so pin the interpreter through
 * {@code JAVA_HOME}. Extract the classpath into a scratch directory outside the repository so the
 * checkout stays clean. Package first: a full {@code clean verify} leaves
 * {@code 02-rag/target/02-rag-0.0.1-SNAPSHOT-exec.jar} overwritten by a test fixture that
 * exercises the evaluation runner's failure path.
 *
 * <p>From the repository root, in PowerShell:
 * <pre>
 * .\mvnw.cmd -pl 02-rag clean package '-DskipTests'
 * $repo = (Get-Location).Path
 * $work = Join-Path $env:TEMP 'rag-manifest'
 * New-Item -ItemType Directory -Force $work | Out-Null
 * &amp; "$env:JAVA_HOME\bin\java.exe" -version    # must report 21
 * Push-Location $work
 * &amp; "$env:JAVA_HOME\bin\jar.exe" xf "$repo\02-rag\target\02-rag-0.0.1-SNAPSHOT-exec.jar" `
 *   BOOT-INF/lib BOOT-INF/classes
 * Pop-Location
 * &amp; "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 `
 *   -cp "$work\BOOT-INF\classes;$work\BOOT-INF\lib\*" -d "$work\out" `
 *   02-rag\evaluation\tools\ManifestDump.java
 * &amp; "$env:JAVA_HOME\bin\java.exe" `
 *   -cp "$work\out;$work\BOOT-INF\classes;$work\BOOT-INF\lib\*" `
 *   com.epam.docqachatbot.ingestion.ManifestDump `
 *   02-rag/EPAM_JavaSecureCodingGD.md file:02-rag/EPAM_JavaSecureCodingGD.md `
 *   EPAM_JavaSecureCodingGD.md text-embedding-3-small-1 `
 *   02-rag/evaluation/runs/20260901T201249Z-chunk-manifest.json
 * </pre>
 */
public final class ManifestDump {

  private static final char BACKSLASH = (char) 92;
  private static final char QUOTE = (char) 34;

  private ManifestDump() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 5) {
      throw new IllegalArgumentException(
        "usage: ManifestDump <corpusPath> <ingestLocation> <documentName> "
          + "<embeddingModel> <outputPath>");
    }
    Path corpus = Path.of(args[0]);
    String location = args[1];
    String documentName = args[2];
    String embeddingModel = args[3];
    Path output = Path.of(args[4]);

    byte[] content = Files.readAllBytes(corpus);
    LoadedDocumentResource resource = new LoadedDocumentResource(
      location, documentName, DocumentFormat.fromLocation(location),
      content, Hashing.sha256(content));

    DocumentProcessingProperties properties = new DocumentProcessingProperties();
    properties.setEmbeddingModel(embeddingModel);
    DocumentProcessingProperties.Chunking chunking = properties.getChunking();

    List<Document> raw = new DocumentReaderFactory().create(resource).get();
    List<Document> chunks = new ProvenanceDocumentTransformer(properties)
      .transform(resource, raw, Map.of("evaluationCorpus", "epam-java-secure-coding-guidelines"));

    Files.createDirectories(output.toAbsolutePath().getParent());
    try (PrintWriter writer = new PrintWriter(
      Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
      writer.println("{");
      writer.println("  " + q("schemaVersion") + ": 1,");
      writer.println("  " + q("artifactType") + ": " + q("reconstructed-ingestion-manifest") + ",");
      writer.println("  " + q("generatedBy") + ": " + q("evaluation/tools/ManifestDump.java") + ",");
      writer.println("  " + q("deterministic") + ": true,");
      writer.println("  " + q("corpus") + ": " + q(corpus.getFileName().toString()) + ",");
      writer.println("  " + q("ingestionLocation") + ": " + q(location) + ",");
      writer.println("  " + q("documentName") + ": " + q(documentName) + ",");
      writer.println("  " + q("embeddingModel") + ": " + q(embeddingModel) + ",");
      writer.println("  " + q("documentId") + ": " + q(Hashing.sha256(location)) + ",");
      writer.println("  " + q("corpusFingerprint") + ": " + q(resource.fingerprint()) + ",");
      writer.println("  " + q("chunkIdFormula") + ": "
        + q("sha256(documentId | chunkIndex | chunkText | embeddingModel)") + ",");
      writer.println("  " + q("chunking") + ": {"
        + q("enabled") + ": " + chunking.isEnabled() + ", "
        + q("tokensPerChunk") + ": " + chunking.getTokensPerChunk() + ", "
        + q("minChunkSizeChars") + ": " + chunking.getMinChunkSizeChars() + ", "
        + q("minChunkLengthToEmbed") + ": " + chunking.getMinChunkLengthToEmbed() + ", "
        + q("maxNumChunks") + ": " + chunking.getMaxNumChunks() + ", "
        + q("keepSeparator") + ": " + chunking.isKeepSeparator() + "},");
      writer.println("  " + q("chunkCount") + ": " + chunks.size() + ",");
      writer.println("  " + q("chunks") + ": [");
      for (int i = 0; i < chunks.size(); i++) {
        Document chunk = chunks.get(i);
        String text = chunk.getText();
        writer.print("    {"
          + q("chunkIndex") + ": " + chunk.getMetadata().get(IngestionMetadata.CHUNK_INDEX)
          + ", " + q("chunkId") + ": " + q(chunk.getId())
          + ", " + q("documentName") + ": "
          + q(String.valueOf(chunk.getMetadata().get(IngestionMetadata.DOCUMENT_NAME)))
          + ", " + q("headingPath") + ": "
          + q(String.valueOf(chunk.getMetadata().get(IngestionMetadata.HEADING_PATH)))
          + ", " + q("contentSha256") + ": " + q(sha256(text))
          + ", " + q("textLength") + ": " + text.length() + "}");
        writer.println(i == chunks.size() - 1 ? "" : ",");
      }
      writer.println("  ]");
      writer.println("}");
    }
    System.out.println("chunks=" + chunks.size() + " -> " + output);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  private static String q(String value) {
    StringBuilder builder = new StringBuilder().append(QUOTE);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == QUOTE || c == BACKSLASH) {
        builder.append(BACKSLASH).append(c);
      } else if (c == '\n') {
        builder.append(BACKSLASH).append('n');
      } else if (c == '\r') {
        builder.append(BACKSLASH).append('r');
      } else if (c == '\t') {
        builder.append(BACKSLASH).append('t');
      } else if (c < 0x20) {
        builder.append(BACKSLASH).append('u').append(String.format("%04x", (int) c));
      } else {
        builder.append(c);
      }
    }
    return builder.append(QUOTE).toString();
  }
}
