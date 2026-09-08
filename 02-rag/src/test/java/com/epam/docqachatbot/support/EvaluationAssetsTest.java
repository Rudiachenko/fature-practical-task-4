package com.epam.docqachatbot.support;

import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.api.model.RagSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationAssetsTest {

  private static final Path SCRIPT = Path.of("scripts", "run-live-evaluation.ps1");
  private static final Path QUESTIONS = Path.of("evaluation", "questions.json");
  private static final Path EXPERIMENTS = Path.of("evaluation", "experiments.json");
  private static final Path RESULT_SCHEMA = Path.of("evaluation", "result-schema.json");
  private static final Path OBJECTIVE_REVIEW_SCHEMA =
    Path.of("evaluation", "objective-review-schema.json");
  private static final Path RUNS = Path.of("evaluation", "runs");
  private static final Set<String> VERDICT_VALUES = Set.of("PASS", "FAIL");
  private static final Set<String> SOURCE_ROLES = Set.of("direct", "contextual", "irrelevant");
  private static final Set<String> README_SCOPES = Set.of(
    "core-contract", "model-comparison", "experiment-default-config", "experiment-variant");
  /** The generator {@code application.yml} falls back to, and the one the evaluation harness must
   * exercise. Stated once here so a default change fails a test rather than passing silently. */
  private static final String SHIPPED_GENERATOR = "gpt-5-mini-2025-08-07";
  private static final Path RUNBOOK = Path.of("RUNBOOK.md");
  private static final Path POLICY = Path.of("EPAM_JavaSecureCodingGD.md");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldConformToTheirOwnSchemas_whenCommittedEvaluationArtifactsAreValidated()
    throws Exception {
    // Arrange: real JSON Schema validation, not hand-written structural assertions. The two are
    // not interchangeable - a hand-written check verifies what its author remembered, which is how
    // a review shipped with status "COMPLETE" against a schema whose const is "COMPLETED", and a
    // summary field named sourcePrecisionMeasurement where the schema requires
    // sourcePrecisionMetrics, while every assertion in this class passed.
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    JsonSchema reviewSchema = factory.getSchema(OBJECTIVE_REVIEW_SCHEMA.toUri());
    JsonSchema captureSchema = factory.getSchema(RESULT_SCHEMA.toUri());

    // Classify by content, not by filename. A filename convention drifts: a positive name filter
    // silently skips an artifact it does not recognise, and a negative one wrongly claims an
    // unrelated artifact is a capture. What a file *is* is decided by the fields it carries.
    List<Path> reviews = new java.util.ArrayList<>();
    List<Path> captures = new java.util.ArrayList<>();
    List<Path> other = new java.util.ArrayList<>();
    try (var files = Files.list(RUNS)) {
      for (Path artifact : files.sorted().toList()) {
        JsonNode node = objectMapper.readTree(artifact.toFile());
        if (node.hasNonNull("reviewType")) {
          reviews.add(artifact);
        } else if (node.has("fixedIndex") && node.has("results")) {
          captures.add(artifact);
        } else {
          other.add(artifact);
        }
      }
    }
    assertThat(reviews).as("committed objective reviews").isNotEmpty();
    assertThat(captures).as("committed raw captures").isNotEmpty();

    // Act / Assert
    for (Path review : reviews) {
      assertValidates(reviewSchema, review);
    }
    for (Path capture : captures) {
      assertValidates(captureSchema, capture);
    }

    // Assert: nothing was silently skipped. Every remaining artifact is one of the kinds this
    // module knowingly ships without a schema - a chunk manifest, a smoke review, or a retained
    // measurement study - so a new, unclassified artifact fails here instead of going unchecked.
    assertThat(other.stream().map(EvaluationAssetsTest::name))
      .allSatisfy(fileName -> assertThat(fileName).matches(
        ".*(-chunk-manifest|-live-smokes-review|-model-selection-study)\\.json"));

    // Assert: the validation is live, not vacuously passing. Break one required invariant of a
    // real artifact and the same call must reject it.
    JsonNode tampered = objectMapper.readTree(reviews.getFirst().toFile());
    ((com.fasterxml.jackson.databind.node.ObjectNode) tampered).put("status", "COMPLETE");
    assertThat(reviewSchema.validate(tampered))
      .as("a wrong status value must be rejected, or this test proves nothing")
      .isNotEmpty();
  }

  private static String name(Path path) {
    return path.getFileName().toString();
  }

  private void assertValidates(JsonSchema schema, Path artifact) throws Exception {
    Set<ValidationMessage> violations = schema.validate(objectMapper.readTree(artifact.toFile()));
    assertThat(violations)
      .as("%s must satisfy its schema; violations: %s", artifact, violations)
      .isEmpty();
  }

  @Test
  void shouldDeclareExactModelAndCaseAllowlists_whenRunnerIsInspected() throws Exception {
    // Arrange / Act
    String script = Files.readString(SCRIPT);

    // Assert
    assertThat(script).containsSubsequence(
      "$generatorDeployments = @(",
      "'gpt-4o'",
      "'gpt-4.1-nano-2025-04-14'",
      "'gpt-5-mini-2025-08-07'"
    );
    assertThat(script).contains(
      "[ValidateSet('All', 'Comparison', 'Experiments')]",
      "[ValidateSet('All', 'Simple', 'Detail', 'Synthesis', 'Analysis', 'FiveBulletSummary')]",
      "[ValidateSet('All', 'TopK', 'SimilarityThreshold', 'GroundingRefusalSources', "
        + "'QueryTransformationExpansion', 'QueryCompression', 'Reranking')]"
    );
    assertThat(script).doesNotContain("gpt-3.5", "gpt-4-turbo");
  }

  @Test
  void shouldExerciseTheShippedGeneratorRatherThanAHardCodedOne_whenRunnerIsInspected()
    throws Exception {
    // Arrange / Act
    String script = Files.readString(SCRIPT);
    String applicationYaml = Files.readString(
      Path.of("src", "main", "resources", "application.yml"));

    // Assert: the runner mirrors application.yml's generator fallback instead of pinning a name.
    // A hard-coded generator would silently keep measuring a model the application no longer
    // ships, which is exactly how a default change escapes evaluation.
    assertThat(script).contains("$shippedGeneratorDeployment = '" + SHIPPED_GENERATOR + "'");
    assertThat(applicationYaml)
      .contains("deployment-name: ${AZURE_OPEN_AI_DEPLOYMENT_NAME:" + SHIPPED_GENERATOR + "}");
    assertThat(script).contains(
      "Invoke-ApplicationRun -Deployment $shippedGeneratorDeployment",
      "$experimentDeployment = $shippedGeneratorDeployment");

    // Assert: the configured-default mode really removes every override, and records the
    // deployment the application reported rather than the one the harness asked for.
    assertThat(script).contains(
      "[switch]$UseConfiguredDefaultGenerator",
      "Remove-Item Env:\\AZURE_OPEN_AI_DEPLOYMENT_NAME -ErrorAction SilentlyContinue",
      "Invoke-ChatEvaluation -Deployment $script:resolvedDeployment");

    // Assert: the deployment argument is added only outside configured-default mode, so that mode
    // really passes nothing rather than passing the same value by another route.
    assertThat(script).containsSubsequence(
      "if (-not $UseConfiguredDefaultGenerator) {",
      "$arguments += \"--spring.ai.azure.openai.chat.options.deployment-name=$Deployment\"");
  }

  @Test
  void shouldEnforceIngestionAndCleanupControlFlow_whenRunnerAstIsInspected()
    throws Exception {
    // Arrange
    Path assertionScript = Path.of("src", "test", "resources",
      "assert-evaluation-runner-ast.ps1").toAbsolutePath();

    // Act
    ProcessResult process = runPowerShell(
      "-File", assertionScript.toString(), "-RunnerPath", SCRIPT.toAbsolutePath().toString());
    JsonNode evidence = objectMapper.readTree(process.output());

    // Assert
    assertThat(process.exitCode()).isZero();
    assertThat(evidence.path("parseErrorCount").asInt()).isZero();
    assertThat(evidence.path("applicationRunHasTryFinally").asBoolean()).isTrue();
    assertThat(evidence.path("ingestionBeforeEvaluationInRun").asBoolean()).isTrue();
    assertThat(evidence.path("applicationCleanupIsInFinally").asBoolean()).isTrue();
    assertThat(evidence.path("rootCleanupAndRestoreAreInFinally").asBoolean()).isTrue();
    assertThat(evidence.path("dedicatedIngestionExists").asBoolean()).isTrue();
    assertThat(evidence.path("dedicatedIngestionPrecedesGeneratorLoop").asBoolean()).isTrue();
    assertThat(evidence.path("generatorLoopCannotIngest").asBoolean()).isTrue();
    assertThat(evidence.path("fixedConfigIsPassedInsideStart").asBoolean()).isTrue();
    assertThat(evidence.path("fixedEmbeddingAndCollectionAreNeverAssigned").asBoolean()).isTrue();
  }

  @Test
  void shouldGenerateSchemaConformantSanitizedArtifact_whenContractModeRuns() throws Exception {
    // Arrange
    Path output = Path.of("target", "evaluation-contract", "artifact.json");
    Files.deleteIfExists(output);

    // Act
    ProcessResult process = runPowerShell(
      "-File", SCRIPT.toAbsolutePath().toString(),
      "-ContractTest", "-OutputPath", output.toAbsolutePath().toString());
    JsonNode artifact = objectMapper.readTree(output.toFile());
    JsonNode schema = objectMapper.readTree(RESULT_SCHEMA.toFile());

    // Assert
    assertThat(process.exitCode()).isZero();
    assertRequiredFields(artifact, schema.path("required"));
    assertThat(artifact.path("fixedIndex").path("ingestionCount").asInt()).isEqualTo(1);

    // Assert: every capture the runner produces states which generator it actually exercised and
    // whether that was the configured default. Without it, a capture cannot be told apart from one
    // taken under a model override, so it cannot be evidence for the shipped default.
    JsonNode generatorSelection = artifact.path("generatorSelection");
    assertRequiredFields(generatorSelection,
      schema.path("properties").path("generatorSelection").path("required"));
    assertThat(generatorSelection.path("usedConfiguredDefault").isBoolean()).isTrue();
    assertThat(generatorSelection.path("resolvedDeployment").asText()).isNotBlank();

    JsonNode result = artifact.path("results").get(0);
    assertRequiredFields(result,
      schema.path("properties").path("results").path("items").path("required"));
    assertRequiredFields(result.path("rubric"),
      schema.path("properties").path("results").path("items")
        .path("properties").path("rubric").path("required"));
    assertThat(result.path("status").asText()).isEqualTo("NOT_PROVEN");
    assertThat(result.path("sources").get(0).path("chunkId").asText()).isNotBlank();
    assertThat(result.path("rubric").path("sourceShapeValid").asBoolean()).isTrue();
    assertThat(result.path("rubric").path("agentReviewRequired").asBoolean()).isTrue();
    assertThat(result.path("rubric").path("sourceAttributionCorrect").isNull()).isTrue();
    JsonNode invalidResult = artifact.path("results").get(1);
    assertThat(invalidResult.path("status").asText()).isEqualTo("FAIL");
    assertThat(invalidResult.path("sources").get(0).path("chunkId").asText()).isBlank();
    assertThat(invalidResult.path("rubric").path("sourceShapeValid").asBoolean()).isFalse();
    assertThat(invalidResult.path("rubric").path("agentReviewRequired").asBoolean())
      .isFalse();
    assertThat(invalidResult.path("rubric").path("sourceAttributionCorrect").isNull()).isTrue();
    String artifactText = Files.readString(output);
    assertThat(artifactText)
      .contains("[REDACTED]")
      .doesNotContain("contract-test-secret", "raw provider error containing");

    // Assert: the secret is redacted even though it contains ' < > &, the four characters
    // Windows PowerShell's ConvertTo-Json escapes as backslash-u sequences. Redaction searches the
    // serialized text for the raw secret, so leaving those escapes in place would let a secret
    // containing any of them pass through into the artifact in full. Unescaping runs first.
    String raw = Files.readString(output);
    assertThat(raw)
      .as("no backslash-u escapes survive serialization")
      .doesNotContain("\\u0027", "\\u003c", "\\u003e", "\\u0026");
    assertThat(raw)
      .as("no fragment of the escaped secret leaks")
      .doesNotContain("value<and>more&here");
    assertThat(artifact.path("failure").path("details").asText())
      .isEqualTo("See the local process exit status; secret-bearing server logs were deleted.");
  }

  @Test
  void shouldWriteFailureArtifact_whenLiveSetupFailsBeforeAnyResultIsCaptured() throws Exception {
    // Arrange
    Path output = Path.of("target", "evaluation-contract", "empty-results-failure.json");
    Path executableJar = Path.of("target", "02-rag-0.0.1-SNAPSHOT-exec.jar");
    Files.createDirectories(executableJar.getParent());
    Files.writeString(executableJar, "not-started");
    Files.deleteIfExists(output);
    String command = "& { "
      + "$env:AZURE_OPEN_AI_KEY='test-key'; "
      + "$env:AZURE_OPEN_AI_ENDPOINT='https://example.invalid'; "
      + "$env:AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME='test-embedding'; "
      + "$env:CHROMA_BASE_URL='http://127.0.0.1:1'; "
      + "$env:CHROMA_COLLECTION='test-collection'; "
      + "& '" + SCRIPT.toAbsolutePath() + "' -Suite Comparison -OutputPath '"
      + output.toAbsolutePath() + "'; exit $LASTEXITCODE }";

    // Act
    ProcessResult process = runPowerShell("-Command", command);
    JsonNode artifact = objectMapper.readTree(output.toFile());

    // Assert
    assertThat(process.exitCode()).isNotZero();
    assertThat(artifact.path("status").asText()).isEqualTo("FAILED");
    assertThat(artifact.path("results")).isEmpty();
    assertThat(artifact.path("modelScores")).hasSize(3);
    assertThat(artifact.path("failure").path("details").asText())
      .isEqualTo("See the local process exit status; secret-bearing server logs were deleted.");
  }

  @Test
  void shouldDefineFivePolicyGroundedCasesAndScoringRubric_whenFixturesAreLoaded()
    throws Exception {
    // Arrange
    JsonNode questions = objectMapper.readTree(QUESTIONS.toFile());
    String policy = Files.readString(POLICY).toLowerCase(Locale.ROOT);

    // Act
    List<String> caseIds = StreamSupport.stream(questions.path("cases").spliterator(), false)
      .map(node -> node.path("id").asText())
      .toList();

    // Assert
    assertThat(caseIds).containsExactly(
      "Simple", "Detail", "Synthesis", "Analysis", "FiveBulletSummary");
    assertThat(questions.path("cases")).allSatisfy(caseNode -> {
      assertThat(caseNode.path("question").asText()).isNotBlank();
      assertThat(caseNode.path("evidence").size()).isGreaterThanOrEqualTo(3);
      assertThat(caseNode.path("rubric").path("passRule").asText()).isNotBlank();
      caseNode.path("evidence").forEach(evidence ->
        assertThat(policy).contains(evidence.asText().toLowerCase(Locale.ROOT)));
    });
    assertThat(questions.path("cases").get(4).path("rubric").path("exactBulletCount").asInt())
      .isEqualTo(5);
    assertThat(questions.path("modelScoring").path("minimum").asInt()).isEqualTo(1);
    assertThat(questions.path("modelScoring").path("maximum").asInt()).isEqualTo(5);
  }

  @Test
  void shouldDefineSevenBoundedExperiments_whenExperimentFixtureIsLoaded() throws Exception {
    // Arrange / Act
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());

    // Assert
    assertThat(experiments.path("experiments")).hasSize(7);
    assertThat(experimentIds(experiments)).containsExactly(
      "TopK", "SimilarityThreshold", "GroundingRefusalSources", "NoisyCorpus",
      "QueryTransformationExpansion", "QueryCompression", "Reranking");
    assertThat(intValues(experiments, "TopK", "topK")).containsExactly(1, 4, 20);
    assertThat(doubleValues(experiments, "SimilarityThreshold", "similarityThreshold"))
      .containsExactly(0.1, 0.5, 0.8);

    JsonNode refusal = findExperiment(experiments, "GroundingRefusalSources")
      .path("variants").get(1);
    assertThat(refusal.path("expectedResponse").asText())
      .isEqualTo("I don't have enough information to answer this question.");
    assertThat(refusal.path("expectedSources")).isEmpty();
    JsonNode supported = findExperiment(experiments, "GroundingRefusalSources")
      .path("variants").get(0);
    assertThat(supported.path("expectedOutcome").asText()).startsWith("The final review");
  }

  @Test
  void shouldDefineNoisyCorpusExperimentWithDistractorDocumentAndRefusalCase_whenExperimentFixtureIsLoaded()
    throws Exception {
    // Arrange / Act
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());
    JsonNode experiment = findExperiment(experiments, "NoisyCorpus");
    JsonNode variants = experiment.path("variants");

    // Assert: the corpus is the relevant document plus a genuinely off-topic distractor,
    // and the representative subset covers both a distractor-contamination check and the
    // out-of-corpus refusal check the task requires.
    assertThat(textValues(experiment.path("corpusDocuments"))).containsExactly(
      "EPAM_JavaSecureCodingGD.md",
      "02-rag/src/test/resources/documents/llm_context_document.pdf");
    assertThat(variants).hasSize(5);
    assertThat(StreamSupport.stream(variants.spliterator(), false)
      .map(variant -> variant.path("case").asText())
      .toList()).containsExactly(
        "Simple", "Synthesis", "FiveBulletSummary", "DistractorTopicProbe", "Unsupported");

    // Every variant is pinned at the baseline retrieval configuration, so the distractor
    // corpus is the only moving part relative to the clean-baseline run.
    variants.forEach(variant -> {
      assertThat(variant.path("topK").asInt()).isEqualTo(5);
      assertThat(variant.path("similarityThreshold").asDouble()).isEqualTo(0.3);
    });

    assertThat(variants.get(0).path("expectedDocumentName").asText())
      .isEqualTo("EPAM_JavaSecureCodingGD.md");
    assertThat(variants.get(3).path("expectedDocumentName").asText())
      .isEqualTo("llm_context_document.pdf");

    JsonNode refusalVariant = variants.get(4);
    assertThat(refusalVariant.path("expectedResponse").asText())
      .isEqualTo("I don't have enough information to answer this question.");
    assertThat(refusalVariant.path("expectedSources")).isEmpty();
  }

  @Test
  void shouldDefineQueryTransformationExpansionExperimentAgainstFiveBulletSummary_whenExperimentFixtureIsLoaded()
    throws Exception {
    // Arrange / Act
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());
    JsonNode experiment = findExperiment(experiments, "QueryTransformationExpansion");
    JsonNode variants = experiment.path("variants");

    // Assert: probes the documented FiveBulletSummary retrieval failure, not an arbitrary case.
    assertThat(experiment.path("questionCase").asText()).isEqualTo("FiveBulletSummary");
    assertThat(variants).hasSize(4);
    assertThat(StreamSupport.stream(variants.spliterator(), false)
      .map(variant -> variant.path("case").asText())
      .toList()).containsExactly("baseline", "expansionOnly", "rewriteOnly", "expansionAndRewrite");

    // topK/similarityThreshold are pinned across every variant so the toggle is the only
    // moving part, exactly as run-live-evaluation.ps1's dispatch block requires.
    variants.forEach(variant -> {
      assertThat(variant.path("topK").asInt()).isEqualTo(5);
      assertThat(variant.path("similarityThreshold").asDouble()).isEqualTo(0.3);
    });

    JsonNode baseline = variants.get(0);
    assertThat(baseline.path("compressionEnabled").asBoolean()).isFalse();
    assertThat(baseline.path("rewriteEnabled").asBoolean()).isFalse();
    assertThat(baseline.path("queryExpansionEnabled").asBoolean()).isFalse();

    JsonNode expansionOnly = variants.get(1);
    assertThat(expansionOnly.path("rewriteEnabled").asBoolean()).isFalse();
    assertThat(expansionOnly.path("queryExpansionEnabled").asBoolean()).isTrue();
    assertThat(expansionOnly.path("numberOfQueries").asInt()).isEqualTo(3);
    assertThat(expansionOnly.path("includeOriginal").asBoolean()).isTrue();

    JsonNode rewriteOnly = variants.get(2);
    assertThat(rewriteOnly.path("rewriteEnabled").asBoolean()).isTrue();
    assertThat(rewriteOnly.path("queryExpansionEnabled").asBoolean()).isFalse();

    JsonNode expansionAndRewrite = variants.get(3);
    assertThat(expansionAndRewrite.path("rewriteEnabled").asBoolean()).isTrue();
    assertThat(expansionAndRewrite.path("queryExpansionEnabled").asBoolean()).isTrue();
    assertThat(expansionAndRewrite.path("numberOfQueries").asInt()).isEqualTo(3);
  }

  @Test
  void shouldDefineQueryCompressionExperimentAsGenuineTwoTurnConversation_whenExperimentFixtureIsLoaded()
    throws Exception {
    // Arrange / Act
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());
    JsonNode experiment = findExperiment(experiments, "QueryCompression");
    JsonNode variants = experiment.path("variants");

    // Assert: exactly the compressionOff/compressionOn toggle, and every variant carries a
    // genuine two-turn pair where turn 2 is a purely elliptical follow-up — mechanically
    // proven by asserting it repeats none of the domain nouns turn 1 introduced, not merely
    // that two question strings exist.
    assertThat(variants).hasSize(2);
    assertThat(StreamSupport.stream(variants.spliterator(), false)
      .map(variant -> variant.path("case").asText())
      .toList()).containsExactly("compressionOff", "compressionOn");

    JsonNode compressionOff = variants.get(0);
    assertThat(compressionOff.path("compressionEnabled").asBoolean()).isFalse();
    JsonNode compressionOn = variants.get(1);
    assertThat(compressionOn.path("compressionEnabled").asBoolean()).isTrue();

    variants.forEach(variant -> {
      assertThat(variant.path("topK").asInt()).isEqualTo(5);
      assertThat(variant.path("similarityThreshold").asDouble()).isEqualTo(0.3);
      assertThat(variant.path("turn1Question").asText()).isNotBlank();
      assertThat(variant.path("turn2Question").asText()).isNotBlank();
      assertThat(variant.path("expectedDocumentName").asText())
        .isEqualTo("EPAM_JavaSecureCodingGD.md");

      String turn2Question = variant.path("turn2Question").asText().toLowerCase(Locale.ROOT);
      assertThat(turn2Question).doesNotContain("secret", "password", "cryptographic", "obfuscat");
    });
  }

  @Test
  void shouldDefineRerankingExperimentWithUnnarrowedComparatorAndOrdinaryQuestionCheck_whenExperimentFixtureIsLoaded()
    throws Exception {
    // Arrange / Act
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());
    JsonNode experiment = findExperiment(experiments, "Reranking");
    JsonNode variants = experiment.path("variants");
    String script = Files.readString(SCRIPT);

    // Assert: probes the documented FiveBulletSummary retrieval failure, comparing the plain
    // topK=5 baseline and the broader topK=20 pool narrowed to mmrFinalTopK=5 by the
    // Increment 14 theme-key heading-diversity post-processor against an un-narrowed topK=20
    // ceiling comparator, plus a committed ordinary-question degradation check.
    assertThat(experiment.path("questionCase").asText()).isEqualTo("FiveBulletSummary");
    assertThat(variants).hasSize(4);
    assertThat(StreamSupport.stream(variants.spliterator(), false)
      .map(variant -> variant.path("case").asText())
      .toList()).containsExactly(
        "baseline", "mmrEnabled", "topK20Unnarrowed", "ordinaryQuestionMmrEnabled");

    JsonNode baseline = variants.get(0);
    assertThat(baseline.path("topK").asInt()).isEqualTo(5);
    assertThat(baseline.path("similarityThreshold").asDouble()).isEqualTo(0.3);
    assertThat(baseline.path("mmrEnabled").asBoolean()).isFalse();
    assertThat(baseline.path("questionCase").isMissingNode()).isTrue();

    JsonNode mmrEnabled = variants.get(1);
    assertThat(mmrEnabled.path("topK").asInt()).isEqualTo(20);
    assertThat(mmrEnabled.path("similarityThreshold").asDouble()).isEqualTo(0.3);
    assertThat(mmrEnabled.path("mmrEnabled").asBoolean()).isTrue();
    assertThat(mmrEnabled.path("mmrFinalTopK").asInt()).isEqualTo(5);
    assertThat(mmrEnabled.path("mmrLambda").asDouble()).isEqualTo(0.5);
    assertThat(mmrEnabled.path("questionCase").isMissingNode()).isTrue();

    // The formal, harness-captured version of Increment 13's manual "topK=20, MMR off, all 20
    // chunks straight to generation" probe: no narrowing at all.
    JsonNode topK20Unnarrowed = variants.get(2);
    assertThat(topK20Unnarrowed.path("topK").asInt()).isEqualTo(20);
    assertThat(topK20Unnarrowed.path("similarityThreshold").asDouble()).isEqualTo(0.3);
    assertThat(topK20Unnarrowed.path("mmrEnabled").asBoolean()).isFalse();
    assertThat(topK20Unnarrowed.path("mmrFinalTopK").isMissingNode()).isTrue();
    assertThat(topK20Unnarrowed.path("mmrLambda").isMissingNode()).isTrue();
    assertThat(topK20Unnarrowed.path("questionCase").isMissingNode()).isTrue();

    // The formal, harness-captured version of Increment 13's manual ordinary-question probe:
    // the same mmrEnabled configuration, but against the Simple question via a per-variant
    // questionCase override rather than the experiment-level FiveBulletSummary default.
    JsonNode ordinaryQuestionMmrEnabled = variants.get(3);
    assertThat(ordinaryQuestionMmrEnabled.path("questionCase").asText()).isEqualTo("Simple");
    assertThat(ordinaryQuestionMmrEnabled.path("topK").asInt()).isEqualTo(20);
    assertThat(ordinaryQuestionMmrEnabled.path("similarityThreshold").asDouble()).isEqualTo(0.3);
    assertThat(ordinaryQuestionMmrEnabled.path("mmrEnabled").asBoolean()).isTrue();
    assertThat(ordinaryQuestionMmrEnabled.path("mmrFinalTopK").asInt()).isEqualTo(5);
    assertThat(ordinaryQuestionMmrEnabled.path("mmrLambda").asDouble()).isEqualTo(0.5);

    // Structural proxy proving the per-variant questionCase override code path is genuinely
    // wired into the committed runner, not merely declared in the fixture.
    assertThat(script).contains("variant.questionCase");
  }

  @Test
  void shouldRequireSanitizedObservableFieldsAndTechnicalStates_whenSchemaIsInspected()
    throws Exception {
    // Arrange / Act
    JsonNode schema = objectMapper.readTree(RESULT_SCHEMA.toFile());

    // Assert
    assertThat(textValues(schema.path("required"))).containsExactlyInAnyOrder(
      "schemaVersion", "status", "generatedAtUtc", "fixedIndex", "results", "modelScores");
    JsonNode resultRequired = schema.path("properties").path("results")
      .path("items").path("required");
    assertThat(textValues(resultRequired)).contains(
      "deployment", "case", "question", "config", "latencyMs", "httpStatus",
      "status", "response", "sources", "retrievalScoresAvailable", "rubric");
    JsonNode rubricRequired = schema.path("properties").path("results").path("items")
      .path("properties").path("rubric").path("required");
    assertThat(textValues(rubricRequired)).contains(
      "pass", "notes", "sourceFaithful", "refusalCorrect", "sourceShapeValid",
      "manifestMatchVerified", "contentRelevanceVerified", "agentReviewRequired",
      "sourceAttributionCorrect");
    assertThat(textValues(schema.path("properties").path("results").path("items")
      .path("properties").path("status").path("enum")))
      .containsExactly("PASS", "FAIL", "NOT_PROVEN");
  }

  @Test
  void shouldScopeRequirementVerdictAndRequirePerChunkPrecision_whenObjectiveReviewSchemaIsInspected()
    throws Exception {
    // Arrange / Act
    JsonNode schema = objectMapper.readTree(OBJECTIVE_REVIEW_SCHEMA.toFile());
    JsonNode item = schema.path("properties").path("results").path("items");
    JsonNode itemProperties = item.path("properties");

    // Assert: the review must resolve its source verdicts against a committed manifest and
    // must declare itself an agent review rather than a human one.
    assertThat(textValues(schema.path("required"))).contains(
      "sourceRunArtifact", "chunkManifestArtifact", "evidenceBasis",
      "axisDefinitions", "results", "summary", "modelScores");
    assertThat(schema.path("properties").path("reviewType").path("const").asText())
      .isEqualTo("agent-review");

    // Assert: the manifest claim is reproducible, not merely asserted.
    assertThat(textValues(schema.path("properties").path("evidenceBasis").path("required")))
      .contains("chunkIdFormula", "reconstruction", "reproduction", "verification", "notRun");
    assertThat(textValues(schema.path("properties").path("evidenceBasis")
      .path("properties").path("reproduction").path("required")))
      .contains("repositoryCommit", "toolchain", "tool", "commands", "expectedOutcome");
    // A build-output digest varies per package, so it is recorded as provenance and must never
    // be a reproducibility precondition.
    assertThat(textValues(schema.path("properties").path("evidenceBasis")
      .path("properties").path("reproduction").path("required")))
      .doesNotContain("jarSha256", "jarPath");

    // Assert: every axis that applies to every result is decisive and unconditionally required.
    assertThat(textValues(item.path("required"))).contains(
      "resultNumber", "configuration", "readmeScope", "readmeScopeReason",
      "answerStatus", "sourceTraceabilityStatus", "sourceCoverageStatus", "sourcePrecision",
      "rationale");
    List.of("answerStatus", "sourceTraceabilityStatus", "sourceCoverageStatus",
      "requirementStatus").forEach(axis ->
      assertThat(textValues(itemProperties.path(axis).path("enum")))
        .as("axis %s", axis).containsExactly("PASS", "FAIL"));

    // Assert: readmeScope names which README obligation a result exercises, and a deliberately
    // non-default variant carries no requirement verdict at all.
    assertThat(textValues(itemProperties.path("readmeScope").path("enum"))).containsExactly(
      "core-contract", "model-comparison", "experiment-default-config", "experiment-variant");
    assertThat(textValues(item.path("required"))).doesNotContain("requirementStatus");
    JsonNode scopeRule = item.path("allOf").get(0);
    assertThat(scopeRule.path("if").path("properties").path("readmeScope").path("const")
      .asText()).isEqualTo("experiment-variant");
    assertThat(textValues(scopeRule.path("then").path("not").path("required")))
      .containsExactly("requirementStatus");
    assertThat(textValues(scopeRule.path("else").path("required")))
      .containsExactly("requirementStatus");

    // Assert: precision is measured per returned chunk, never as a bare count or a verdict.
    JsonNode precision = itemProperties.path("sourcePrecision");
    assertThat(textValues(precision.path("required"))).contains(
      "returnedSourceCount", "directlySupportingSourceCount",
      "contextuallyRelevantSourceCount", "irrelevantSourceCount", "precisionAtK",
      "classifiedSources");
    assertThat(precision.path("properties").has("status")).isFalse();
    JsonNode classified = precision.path("properties").path("classifiedSources").path("items");
    assertThat(textValues(classified.path("required"))).contains(
      "chunkId", "chunkIndex", "documentName", "headingPath", "manifestMatch", "role", "note");
    assertThat(textValues(classified.path("properties").path("role").path("enum")))
      .containsExactly("direct", "contextual", "irrelevant");
    assertThat(classified.path("properties").path("manifestMatch").path("const").asBoolean())
      .isTrue();

    // Assert: README compliance is tallied per obligation, with no combined pass rate, and an
    // experiment variant contributes no requirement tally.
    JsonNode summaryRequired = schema.path("properties").path("summary").path("required");
    assertThat(textValues(summaryRequired)).contains(
      "resultsByReadmeScope", "readmeRequirementCompliance", "sourcePrecisionMetrics");
    JsonNode compliance = schema.path("properties").path("summary")
      .path("properties").path("readmeRequirementCompliance");
    assertThat(textValues(compliance.path("required")))
      .containsExactlyInAnyOrder("note", "groups");
    JsonNode groupItem = compliance.path("properties").path("groups").path("items");
    assertThat(textValues(groupItem.path("required"))).doesNotContain("requirementTally");
    JsonNode groupRule = groupItem.path("allOf").get(0);
    assertThat(groupRule.path("if").path("properties").path("readmeScope").path("const")
      .asText()).isEqualTo("experiment-variant");
    assertThat(textValues(groupRule.path("then").path("not").path("required")))
      .containsExactly("requirementTally");
    assertThat(textValues(groupRule.path("else").path("required")))
      .containsExactly("requirementTally");
    assertThat(Files.readString(OBJECTIVE_REVIEW_SCHEMA))
      .doesNotContain("NOT_PROVEN", "REVIEW_REQUIRED");
  }

  @Test
  void shouldResolveEveryVerdictAgainstItsManifest_whenCommittedObjectiveReviewsAreInspected()
    throws Exception {
    // Arrange
    List<Path> reviews;
    try (var files = Files.list(RUNS)) {
      reviews = files
        .filter(path -> path.getFileName().toString().endsWith("-objective-review.json"))
        .sorted()
        .toList();
    }

    // Assert: the module ships at least one review conforming to the current contract.
    assertThat(reviews).as("committed objective review artifacts").isNotEmpty();

    for (Path reviewPath : reviews) {
      // Act
      JsonNode review = objectMapper.readTree(reviewPath.toFile());
      JsonNode manifest = objectMapper.readTree(
        Path.of(review.path("chunkManifestArtifact").asText()).toFile());
      Set<String> manifestIds = StreamSupport
        .stream(manifest.path("chunks").spliterator(), false)
        .map(chunk -> chunk.path("chunkId").asText())
        .collect(java.util.stream.Collectors.toSet());
      JsonNode capture = objectMapper.readTree(
        Path.of(review.path("sourceRunArtifact").asText()).toFile());

      // Assert: identity and completeness against the immutable capture.
      assertThat(review.path("schemaVersion").asInt()).isEqualTo(2);
      assertThat(review.path("reviewType").asText()).isEqualTo("agent-review");
      assertThat(review.path("results")).hasSameSizeAs(capture.path("results"));
      assertThat(manifestIds).isNotEmpty();

      List<Integer> resultNumbers = StreamSupport
        .stream(review.path("results").spliterator(), false)
        .map(result -> result.path("resultNumber").asInt())
        .toList();
      assertThat(resultNumbers).doesNotHaveDuplicates()
        .containsExactlyElementsOf(
          java.util.stream.IntStream.rangeClosed(1, capture.path("results").size())
            .boxed().toList());

      int scopedResults = 0;
      Map<String, Integer> scopeTallies = new HashMap<>();
      for (JsonNode result : review.path("results")) {
        int number = result.path("resultNumber").asInt();
        JsonNode captured = capture.path("results").get(number - 1);
        String label = reviewPath.getFileName() + " result " + number;

        assertThat(result.path("deployment").asText()).as(label)
          .isEqualTo(captured.path("deployment").asText());
        assertThat(result.path("case").asText()).as(label)
          .isEqualTo(captured.path("case").asText());

        List.of("answerStatus", "sourceTraceabilityStatus", "sourceCoverageStatus")
          .forEach(axis -> assertThat(result.path(axis).asText()).as(label + " " + axis)
            .isIn(VERDICT_VALUES));

        String scope = result.path("readmeScope").asText();
        assertThat(scope).as(label).isIn(README_SCOPES);
        if ("experiment-variant".equals(scope)) {
          assertThat(result.has("requirementStatus"))
            .as(label + " must not carry a README verdict for a non-default variant")
            .isFalse();
        } else {
          scopedResults++;
          assertThat(result.path("requirementStatus").asText()).as(label).isIn(VERDICT_VALUES);
          scopeTallies.merge(
            scope + "|" + result.path("requirementStatus").asText(), 1, Integer::sum);
        }

        JsonNode precision = result.path("sourcePrecision");
        JsonNode classified = precision.path("classifiedSources");
        int returned = precision.path("returnedSourceCount").asInt();
        assertThat(returned).as(label).isEqualTo(captured.path("sources").size());
        assertThat(classified).as(label).hasSize(returned);

        long direct = 0;
        long contextual = 0;
        for (int i = 0; i < classified.size(); i++) {
          JsonNode source = classified.get(i);
          assertThat(source.path("chunkId").asText()).as(label + " source " + i)
            .isEqualTo(captured.path("sources").get(i).path("chunkId").asText())
            .isIn(manifestIds);
          assertThat(source.path("manifestMatch").asBoolean()).as(label).isTrue();
          assertThat(source.path("role").asText()).as(label).isIn(SOURCE_ROLES);
          assertThat(source.path("note").asText()).as(label).isNotBlank();
          direct += "direct".equals(source.path("role").asText()) ? 1 : 0;
          contextual += "contextual".equals(source.path("role").asText()) ? 1 : 0;
        }
        assertThat(direct).as(label)
          .isEqualTo(precision.path("directlySupportingSourceCount").asLong());
        assertThat(contextual).as(label)
          .isEqualTo(precision.path("contextuallyRelevantSourceCount").asLong());
        assertThat(direct + contextual + precision.path("irrelevantSourceCount").asLong())
          .as(label).isEqualTo(returned);

        if (returned == 0) {
          assertThat(precision.path("precisionAtK").isNull()).as(label).isTrue();
        } else {
          assertThat(precision.path("precisionAtK").asDouble()).as(label)
            .isEqualTo((double) Math.round((double) (direct + contextual) / returned * 1000) / 1000);
        }
      }

      // Assert: every per-obligation tally is exactly what the per-result verdicts say, and no
      // combined figure is published across obligations.
      int tallied = 0;
      for (JsonNode group : review.path("summary").path("readmeRequirementCompliance")
          .path("groups")) {
        String scope = group.path("readmeScope").asText();
        if ("experiment-variant".equals(scope)) {
          assertThat(group.has("requirementTally")).as(scope).isFalse();
          continue;
        }
        JsonNode groupTally = group.path("requirementTally");
        assertThat(groupTally.path("pass").asInt()).as(scope + " pass")
          .isEqualTo(scopeTallies.getOrDefault(scope + "|PASS", 0));
        assertThat(groupTally.path("fail").asInt()).as(scope + " fail")
          .isEqualTo(scopeTallies.getOrDefault(scope + "|FAIL", 0));
        tallied += groupTally.path("pass").asInt() + groupTally.path("fail").asInt();
      }
      assertThat(tallied).isEqualTo(scopedResults);

      assertThat(Files.readString(reviewPath))
        .doesNotContain("NOT_PROVEN", "REVIEW_REQUIRED");
    }
  }

  @Test
  void shouldDocumentExactSerializedDtoAndCurrentCommands_whenRunbookIsInspected()
    throws Exception {
    // Arrange
    String runbook = Files.readString(RUNBOOK);
    String serialized = objectMapper.writeValueAsString(new RagChatResponse(
      "grounded",
      List.of(new RagSource("EPAM_JavaSecureCodingGD.md", "chunk-1"))
    ));

    // Act / Assert
    assertThat(serialized).containsOnlyOnce("\"response\"")
      .contains("\"sources\":[{\"documentName\":", "\"chunkId\":");
    assertThat(runbook).contains(
      "\"response\":",
      "\"documentName\": \"EPAM_JavaSecureCodingGD.md\"",
      "\"chunkId\": \"deterministic-chunk-id\"",
      ".\\mvnw.cmd -pl 02-rag test",
      ".\\mvnw.cmd -pl 02-rag integration-test",
      ".\\mvnw.cmd -pl 02-rag clean verify",
      "After the run, an agent records a justified",
      "evaluation/objective-review-schema.json",
      "evaluation/tools/ManifestDump.java"
    );
    assertThat(runbook).doesNotContain("\"answer\":");
    assertThat(runbook).doesNotContain("`NOT_PROVEN`", "retained `NOT_PROVEN`");
    assertThat(runbook).contains(
      "DELETE /doc-qa/documents?ids=",
      "DELETE /doc-qa/documents?documentName=",
      "EMPTY_DELETE_REQUEST",
      "AMBIGUOUS_DELETE_SELECTOR",
      "INVALID_DELETE_SELECTOR");
    assertThat(runbook).doesNotContain("Document deletion is not implemented.", "compatibility stub");
  }

  private ProcessResult runPowerShell(String... arguments) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add("powershell.exe");
    command.add("-NoProfile");
    command.add("-ExecutionPolicy");
    command.add("Bypass");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command)
      .redirectErrorStream(true)
      .start();
    boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
    assertThat(finished).as("PowerShell contract process finished within 20 seconds").isTrue();
    String output = new String(process.getInputStream().readAllBytes(),
      java.nio.charset.StandardCharsets.UTF_8).trim();
    return new ProcessResult(process.exitValue(), output);
  }

  private void assertRequiredFields(JsonNode value, JsonNode requiredNames) {
    textValues(requiredNames).forEach(name ->
      assertThat(value.has(name)).as("required JSON field %s", name).isTrue());
  }

  private Set<String> experimentIds(JsonNode root) {
    return StreamSupport.stream(root.path("experiments").spliterator(), false)
      .map(node -> node.path("id").asText())
      .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  private List<Integer> intValues(JsonNode root, String experimentId, String field) {
    return StreamSupport.stream(findExperiment(root, experimentId).path("variants").spliterator(), false)
      .map(node -> node.path(field).asInt())
      .toList();
  }

  private List<Double> doubleValues(JsonNode root, String experimentId, String field) {
    return StreamSupport.stream(findExperiment(root, experimentId).path("variants").spliterator(), false)
      .map(node -> node.path(field).asDouble())
      .toList();
  }

  private JsonNode findExperiment(JsonNode root, String id) {
    return StreamSupport.stream(root.path("experiments").spliterator(), false)
      .filter(node -> id.equals(node.path("id").asText()))
      .findFirst()
      .orElseThrow();
  }

  private List<String> textValues(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false)
      .map(JsonNode::asText)
      .toList();
  }

  private record ProcessResult(int exitCode, String output) {
  }
}
