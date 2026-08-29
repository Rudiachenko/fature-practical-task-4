package com.epam.codereviewagent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Increment 8 — proves the evaluation assets under {@code evaluation/} actually have the exact
 * structure {@code context/PLAN.md}'s Increment 8 Acceptance Criteria require, per
 * {@code context/RETROSPECTIVE.md}'s lesson 7 ("a schema-validating test's coverage is defined by its
 * assertions, not its name"): every assertion below walks real JSON structure and checks concrete
 * values, never merely {@code objectMapper.readTree(...)} without a follow-up assertion.
 */
class EvaluationAssetsTest {

  private static final Path EXPERIMENTS = Path.of("evaluation", "experiments.json");
  private static final Path MODEL_COMPARISON_SCHEMA =
    Path.of("evaluation", "model-comparison-schema.json");
  private static final Path MODEL_COMPARISON_RUN_TEMPLATE =
    Path.of("evaluation", "model-comparison-run-template.json");
  private static final Path RESULTS = Path.of("evaluation", "RESULTS.md");
  private static final Path RUNBOOK = Path.of("RUNBOOK.md");
  private static final Path SCRIPT = Path.of("scripts", "run-experiments.ps1");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldDeclareExactlyTheThreeNamedDeployments_whenModelComparisonSchemaDeploymentEnumIsWalked()
    throws Exception {
    // Arrange
    JsonNode schema = objectMapper.readTree(MODEL_COMPARISON_SCHEMA.toFile());

    // Act: walk to the real structural location the schema declares the enum at, rather than
    // assuming a shape - modelRuns is an array schema, so the enum lives under items/properties.
    JsonNode deploymentEnum = schema.path("properties").path("modelRuns")
      .path("items").path("properties").path("deployment").path("enum");

    // Assert
    assertThat(deploymentEnum.isArray()).as("deployment enum node is a JSON array").isTrue();
    List<String> deployments = textValues(deploymentEnum);
    assertThat(deployments).containsExactlyInAnyOrder(
      "gpt-4o", "gpt-4.1-nano-2025-04-14", "gpt-5-mini-2025-08-07");
  }

  @Test
  void shouldConstrainReversedVerdictsDeploymentTheSameWay_whenModelComparisonSchemaIsWalked()
    throws Exception {
    // Arrange
    JsonNode schema = objectMapper.readTree(MODEL_COMPARISON_SCHEMA.toFile());

    // Act
    JsonNode deploymentEnum = schema.path("properties").path("reversedVerdicts")
      .path("items").path("properties").path("deployment").path("enum");

    // Assert: the two enum-bearing locations in this schema must never drift apart.
    assertThat(textValues(deploymentEnum)).containsExactlyInAnyOrder(
      "gpt-4o", "gpt-4.1-nano-2025-04-14", "gpt-5-mini-2025-08-07");
  }

  @Test
  void shouldEnumerateExperimentsOneThroughEightWithNoGapsAndNoDuplicates_whenExperimentsFixtureIsLoaded()
    throws Exception {
    // Arrange
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());

    // Act
    List<Integer> ids = StreamSupport.stream(experiments.path("experiments").spliterator(), false)
      .map(node -> node.path("id").asInt())
      .toList();

    // Assert: exact, ordered 1..8 - a real structural assertion, not "the file parses".
    assertThat(ids).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    assertThat(ids).doesNotHaveDuplicates();
  }

  @Test
  void shouldGiveEveryExperimentAHermeticStatusAndALiveEvaluationStatus_whenExperimentsFixtureIsLoaded()
    throws Exception {
    // Arrange
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());

    // Act / Assert: every experiment entry must declare a disposition for both halves, so no
    // experiment can silently omit one - each hermeticStatus.status is one of the declared values,
    // and every experiment declares a non-blank name/trigger/whatToObserve (the ticket's own columns).
    experiments.path("experiments").forEach(experiment -> {
      assertThat(experiment.path("name").asText()).isNotBlank();
      assertThat(experiment.path("trigger").asText()).isNotBlank();
      assertThat(experiment.path("whatToObserve").asText()).isNotBlank();
      assertThat(experiment.path("hermeticStatus").path("status").asText())
        .isIn("PROVEN", "NOT_APPLICABLE");
      assertThat(experiment.path("liveEvaluation").path("status").asText())
        .isIn("REQUIRES_OPERATOR", "NOT_APPLICABLE");
    });
  }

  @Test
  void shouldCiteAtLeastOneTestMethodForEveryProvenHermeticExperiment_whenExperimentsFixtureIsLoaded()
    throws Exception {
    // Arrange
    JsonNode experiments = objectMapper.readTree(EXPERIMENTS.toFile());

    // Act / Assert: a status of PROVEN without a citation would be exactly the "confidently wrong
    // self-reporting" failure mode context/RETROSPECTIVE.md names - this proves every PROVEN entry
    // actually names a concrete test class/method, not just an unfounded claim.
    experiments.path("experiments").forEach(experiment -> {
      if ("PROVEN".equals(experiment.path("hermeticStatus").path("status").asText())) {
        String citation = experiment.path("hermeticStatus").path("citation").asText();
        assertThat(citation).as("citation for experiment id=%s", experiment.path("id").asInt())
          .isNotBlank()
          .contains("Test");
      }
    });
  }

  @Test
  void shouldDeclareTheFiveTicketRequiredCategories_whenModelComparisonSchemaCategoriesEnumIsWalked()
    throws Exception {
    // Arrange
    JsonNode schema = objectMapper.readTree(MODEL_COMPARISON_SCHEMA.toFile());

    // Act
    JsonNode categoryEnum = schema.path("properties").path("categories").path("items").path("enum");

    // Assert
    assertThat(textValues(categoryEnum)).containsExactlyInAnyOrder(
      "Naming", "CodeStructure", "BestPractices", "CodeQuality", "CommonAntiPatterns");
  }

  @Test
  void shouldRequireStatusAndVerdictOnEveryModelRunEntry_whenModelComparisonSchemaIsWalked()
    throws Exception {
    // Arrange
    JsonNode schema = objectMapper.readTree(MODEL_COMPARISON_SCHEMA.toFile());

    // Act
    JsonNode required = schema.path("properties").path("modelRuns").path("items").path("required");

    // Assert
    assertThat(textValues(required)).contains("deployment", "runNumber", "status", "verdict");
  }

  @Test
  void shouldContainSixModelRunEntriesCoveringEachDeploymentTwiceAllUnpopulated_whenRunTemplateIsLoaded()
    throws Exception {
    // Arrange: read the schema's own deployment enum rather than re-hardcoding it here, so this test
    // fails loudly if the template and the schema it must validate against ever drift apart.
    JsonNode schema = objectMapper.readTree(MODEL_COMPARISON_SCHEMA.toFile());
    List<String> namedDeployments = textValues(schema.path("properties").path("modelRuns")
      .path("items").path("properties").path("deployment").path("enum"));
    JsonNode template = objectMapper.readTree(MODEL_COMPARISON_RUN_TEMPLATE.toFile());
    JsonNode modelRuns = template.path("modelRuns");
    List<JsonNode> runs = StreamSupport.stream(modelRuns.spliterator(), false).toList();

    // Act
    List<String> deployments = runs.stream().map(run -> run.path("deployment").asText()).toList();
    Map<String, List<Integer>> runNumbersByDeployment = runs.stream()
      .collect(Collectors.groupingBy(run -> run.path("deployment").asText(),
        Collectors.mapping(run -> run.path("runNumber").asInt(), Collectors.toList())));

    // Assert: exactly 6 entries total, exactly 2 (run 1 and run 2) per each of the schema's 3 named
    // deployments - a template with a missing or duplicated deployment/run pairing must fail here.
    assertThat(modelRuns.isArray()).as("modelRuns is a JSON array").isTrue();
    assertThat(runs).hasSize(6);
    assertThat(deployments).containsExactlyInAnyOrderElementsOf(
      namedDeployments.stream().flatMap(deployment -> Stream.of(deployment, deployment)).toList());
    for (String deployment : namedDeployments) {
      assertThat(runNumbersByDeployment.get(deployment))
        .as("run numbers present for deployment %s", deployment)
        .containsExactlyInAnyOrder(1, 2);
    }

    // Assert: every score/verdict field starts unpopulated, so nobody can later mistake this template
    // for real, filled-in results.
    runs.forEach(run -> {
      String deployment = run.path("deployment").asText();
      assertThat(run.path("status").asText()).as("status for %s", deployment).isEqualTo("PENDING");
      assertThat(run.path("verdict").isNull()).as("verdict is null for %s", deployment).isTrue();
      assertThat(run.path("requestTimestampUtc").isNull())
        .as("requestTimestampUtc is null for %s", deployment).isTrue();
      assertThat(run.path("notes").isNull()).as("notes is null for %s", deployment).isTrue();
      assertThat(run.path("genericTemplatedClaimsFlagged")).as("no flagged claims for %s", deployment)
        .isEmpty();
      assertThat(run.path("blindSpots")).as("no blind spots for %s", deployment).isEmpty();
      run.path("findingsByCategory").fields().forEachRemaining(category ->
        assertThat(category.getValue())
          .as("findings for category %s on %s", category.getKey(), deployment).isEmpty());
    });
    assertThat(template.path("status").asText()).isEqualTo("REQUIRES_OPERATOR");
    assertThat(template.path("sourceFile").isNull()).as("sourceFile is unset").isTrue();
  }

  @Test
  void shouldRunPowerShellHarnessCleanlyInHermeticOnlyModeAndReportZeroFailures_whenSkippingTheBuild()
    throws Exception {
    // Arrange: -SkipBuild reuses whatever Surefire reports already exist from this very test run
    // (Surefire is already executing, so target/surefire-reports is necessarily populated by the
    // time this test itself runs) - proving the script's own parsing logic against real report XML,
    // not a synthetic fixture.
    List<String> command = List.of(
      "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
      "-File", SCRIPT.toAbsolutePath().toString(),
      "-HermeticOnly", "-SkipBuild");

    // Act
    Process process = new ProcessBuilder(command)
      .directory(Path.of(".").toAbsolutePath().toFile())
      .redirectErrorStream(true)
      .start();
    boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
    String output = new String(process.getInputStream().readAllBytes(),
      java.nio.charset.StandardCharsets.UTF_8);

    // Assert: assert positively on the script's own zero-failure count rather than a finite list of
    // excluded substrings, which would silently miss FAIL: 6-9 and only catch FAIL: 10-19 by
    // coincidental substring overlap with the excluded values - this proves zero failures, not merely
    // the absence of five specific counts.
    assertThat(finished).as("PowerShell harness finished within 60 seconds").isTrue();
    assertThat(output).contains("Hermetic citations checked:");
    assertThat(output).contains("FAIL: 0;");
  }

  @Test
  void shouldDocumentTheReadmeRunbookSplitAndTheSecurityPreCheckOrdering_whenRunbookIsInspected()
    throws Exception {
    // Arrange
    String runbook = Files.readString(RUNBOOK);

    // Assert
    assertThat(runbook).contains(
      "README.md` is the original, unmodified assignment text",
      "app.code-review.max-iterations",
      "app.code-review.max-file-chars",
      "app.code-review.repository-root",
      "@Min(1)",
      "executive-summary-auto-trigger-enabled",
      "--executive-summary-input=<path>",
      "PATH_SECURITY_VIOLATION",
      "AI_PROVIDER_FAILURE",
      "INTERNAL_ERROR");
    assertThat(runbook).contains(
      "returns 400 with zero model calls, not after a wasted review attempt");
  }

  @Test
  void shouldDelimitOneSectionPerExperimentAndTheR12SubtaskWithoutSilentGaps_whenResultsIsInspected()
    throws Exception {
    // Arrange
    String results = Files.readString(RESULTS);

    // Assert: one heading per experiment number 1-8 (the plan's own Acceptance Criteria), plus R12
    // and R11 sections, each present and non-fabricated (REQUIRES OPERATOR is stated explicitly
    // wherever a live run did not happen).
    for (int experimentNumber = 1; experimentNumber <= 8; experimentNumber++) {
      assertThat(results).as("a delimited section for experiment #%d", experimentNumber)
        .contains("Experiment #" + experimentNumber + " —");
    }
    assertThat(results).contains(
      "R12 — Model-choice-justification subtask",
      "R11 — GitLab Merge Request",
      "REQUIRES OPERATOR");
    assertThat(results).doesNotContain("TODO", "TBD", "N/A pending investigation");
  }

  private List<String> textValues(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false)
      .map(JsonNode::asText)
      .toList();
  }
}
