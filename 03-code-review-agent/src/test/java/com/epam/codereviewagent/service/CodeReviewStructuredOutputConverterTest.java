package com.epam.codereviewagent.service;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.Finding;
import com.epam.codereviewagent.api.model.Severity;
import com.epam.codereviewagent.exception.AgentOutputParsingException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hermetic, no-Spring-context, no-network unit tests for
 * {@link CodeReviewStructuredOutputConverter}, matching {@code 01-prompting-llm}'s
 * {@code ChatStructuredOutputConverterTest} shape. Includes the adversarial malformed-JSON
 * payloads named explicitly by this increment's implementation task (truncated JSON, JSON wrapped
 * in markdown fences, a JSON array where an object is expected, valid JSON with wrong field
 * types, embedded newlines/quotes, and a completely empty model response); the wholly-unknown
 * severity edge case ({@code "critical"}, still rejected); and, added in code review retry 1, a
 * bare top-level JSON scalar (string/number, High finding), a case-insensitively-accepted severity
 * value ({@code "HIGH"}, Medium finding, no longer rejected), trailing content after otherwise-valid
 * JSON (Low finding), and a fractional numeric line value (Low finding). See {@link Severity}'s own
 * Javadoc and {@code SeverityTest} for the full case/whitespace/null matrix at the type's own
 * boundary, not duplicated exhaustively here.
 */
class CodeReviewStructuredOutputConverterTest {

  private final CodeReviewStructuredOutputConverter converter = new CodeReviewStructuredOutputConverter();

  // ---------------------------------------------------------------------------------------
  // Valid input
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldRoundTripValidJsonIncludingNestedFindings() {
    // Arrange
    String json = """
      {
        "review": "Overall the file is in reasonable shape.",
        "findings": [
          {
            "file": "Foo.java",
            "startLine": 10,
            "endLine": 12,
            "rule": "no-magic-numbers",
            "severity": "medium",
            "explanation": "Literal 42 has no named meaning.",
            "recommendation": "Extract a named constant."
          }
        ],
        "truncated": false
      }
      """;

    // Act
    CodeReviewResponse response = converter.convert(json);

    // Assert
    assertThat(response.review()).isEqualTo("Overall the file is in reasonable shape.");
    assertThat(response.truncated()).isFalse();
    assertThat(response.findings()).hasSize(1);
    Finding finding = response.findings().get(0);
    assertThat(finding.file()).isEqualTo("Foo.java");
    assertThat(finding.startLine()).isEqualTo(10);
    assertThat(finding.endLine()).isEqualTo(12);
    assertThat(finding.rule()).isEqualTo("no-magic-numbers");
    assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
    assertThat(finding.explanation()).isEqualTo("Literal 42 has no named meaning.");
    assertThat(finding.recommendation()).isEqualTo("Extract a named constant.");
  }

  @Test
  void shouldRoundTripValidJson_whenReviewFieldContainsEmbeddedNewlinesAndQuotes() {
    // Arrange
    String json = """
      {
        "review": "Line one.\\nLine two with a \\"quoted\\" word.\\nLine three.",
        "findings": [],
        "truncated": false
      }
      """;

    // Act
    CodeReviewResponse response = converter.convert(json);

    // Assert
    assertThat(response.review()).isEqualTo("Line one.\nLine two with a \"quoted\" word.\nLine three.");
    assertThat(response.findings()).isEmpty();
  }

  @Test
  void shouldDefaultFindingsAndTruncated_whenOnlyReviewFieldIsPresent() {
    // Arrange
    String json = "{\"review\": \"Looks fine.\"}";

    // Act
    CodeReviewResponse response = converter.convert(json);

    // Assert
    assertThat(response.review()).isEqualTo("Looks fine.");
    assertThat(response.findings()).isEmpty();
    assertThat(response.truncated()).isFalse();
  }

  // ---------------------------------------------------------------------------------------
  // Blank / empty input
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentOutputParsingException_whenInputIsBlank() {
    // Act / Assert
    assertThatThrownBy(() -> converter.convert("   "))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenInputIsACompletelyEmptyModelResponse() {
    // Act / Assert
    assertThatThrownBy(() -> converter.convert(""))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenInputIsNull() {
    // Act / Assert
    assertThatThrownBy(() -> converter.convert(null))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  // ---------------------------------------------------------------------------------------
  // Malformed / adversarial JSON
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentOutputParsingException_whenJsonIsSyntacticallyInvalid() {
    // Act / Assert
    assertThatThrownBy(() -> converter.convert("this is not json at all"))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenJsonIsTruncatedMidObject() {
    // Arrange: a real, plausible truncation - the model's output got cut off mid-string
    String json = "{\"review\": \"This review was cut off because the model ran out of";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenJsonIsWrappedInMarkdownCodeFences() {
    // Arrange: this converter deliberately does not auto-strip markdown fences - see its own
    // Javadoc "Malformed-model-JSON handling" section for why.
    String json = """
      ```json
      {"review": "Looks fine.", "findings": [], "truncated": false}
      ```
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenTopLevelJsonIsAnArrayInsteadOfAnObject() {
    // Arrange
    String json = "[\"review\", \"findings\", \"truncated\"]";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenTopLevelJsonIsABareString() {
    // Arrange: code review, retry 1, High finding. Before CodeReviewResponse's single-argument
    // convenience constructor was annotated @JsonCreator(mode = DISABLED), Jackson auto-detected
    // it as an implicit delegating creator for records, so this bare string silently deserialized
    // into a "successful" response with review="just a string" and an empty findings list - the
    // worst outcome class, indistinguishable from a genuine "no issues found" result.
    String json = "\"just a string\"";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenTopLevelJsonIsABareNumber() {
    // Arrange: same delegating-creator vector as the bare-string case above, exercised with a
    // bare JSON number instead of a string.
    String json = "42";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenFindingsFieldIsAStringInsteadOfAnArray() {
    // Arrange: valid JSON, but wrong field type for `findings`
    String json = "{\"review\": \"Looks fine.\", \"findings\": \"not-an-array\", \"truncated\": false}";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenStartLineFieldIsAStringInsteadOfANumber() {
    // Arrange: valid JSON, but wrong field type for a nested Finding's `startLine`
    String json = """
      {
        "review": "Looks fine.",
        "findings": [
          {
            "file": "Foo.java",
            "startLine": "not-a-number",
            "endLine": 5,
            "rule": "rule",
            "severity": "low",
            "explanation": "explanation",
            "recommendation": "recommendation"
          }
        ],
        "truncated": false
      }
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenStartLineFieldIsAFractionalNumber() {
    // Arrange: code review, retry 1, Low finding. Before ACCEPT_FLOAT_AS_INT was disabled on the
    // converter's ObjectMapper, "startLine": 1.5 silently truncated to 1 instead of being
    // rejected, inconsistent with the already-correctly-rejected non-numeric case above.
    String json = """
      {
        "review": "Looks fine.",
        "findings": [
          {
            "file": "Foo.java",
            "startLine": 1.5,
            "endLine": 5,
            "rule": "rule",
            "severity": "low",
            "explanation": "explanation",
            "recommendation": "recommendation"
          }
        ],
        "truncated": false
      }
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  // ---------------------------------------------------------------------------------------
  // Trailing content after an otherwise-valid JSON value (code review, retry 1, Low finding).
  // Leading prose is already correctly rejected (see shouldThrowAgentOutputParsingException_
  // whenJsonIsSyntacticallyInvalid above); trailing content must be rejected the same way,
  // instead of being silently discarded.
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentOutputParsingException_whenValidJsonIsFollowedByTrailingProseText() {
    // Arrange
    String json = "{\"review\": \"Looks fine.\"}\nSome trailing explanation.";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenTwoJsonObjectsAreConcatenated() {
    // Arrange: without FAIL_ON_TRAILING_TOKENS, this would silently succeed using only the first
    // object, discarding the second without any signal to the caller.
    String json = "{\"review\": \"first\"}{\"review\": \"second\"}";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  // ---------------------------------------------------------------------------------------
  // Missing required field
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentOutputParsingException_whenReviewFieldIsMissing() {
    // Arrange
    String json = "{\"findings\": [], \"truncated\": false}";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenReviewFieldIsBlank() {
    // Arrange
    String json = "{\"review\": \"   \", \"findings\": [], \"truncated\": false}";

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  // ---------------------------------------------------------------------------------------
  // Out-of-enum severity - "critical" must not crash the response path. Case handling
  // ("HIGH") is covered below, separately, since the code review (retry 1, Medium finding)
  // changed its disposition from "reject" to "accept case-insensitively" - see Severity's own
  // Javadoc and SeverityTest for the full case/whitespace matrix at the type's own boundary.
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentOutputParsingException_whenSeverityIsAWhollyUnknownValue() {
    // Arrange
    String json = """
      {
        "review": "Looks fine.",
        "findings": [
          {"file": "Foo.java", "startLine": 1, "endLine": 1, "rule": "r", "severity": "critical",
           "explanation": "e", "recommendation": "rec"}
        ],
        "truncated": false
      }
      """;

    // Act / Assert: rejected, not silently defaulted to some severity
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldAcceptDifferentlyCasedSeverityValue_whenSeverityIsAnUppercaseVariantOfAValidValue() {
    // Arrange: code review, retry 1, Medium finding - real models routinely emit capitalized
    // severities; combined with this contract's all-or-nothing parsing, rejecting a mere casing
    // difference discarded the whole review over a cosmetic mismatch. Now accepted, mapped onto
    // the matching Severity constant, not rejected.
    String json = """
      {
        "review": "Looks fine.",
        "findings": [
          {"file": "Foo.java", "startLine": 1, "endLine": 1, "rule": "r", "severity": "HIGH",
           "explanation": "e", "recommendation": "rec"}
        ],
        "truncated": false
      }
      """;

    // Act
    CodeReviewResponse response = converter.convert(json);

    // Assert
    assertThat(response.findings()).hasSize(1);
    assertThat(response.findings().get(0).severity()).isEqualTo(Severity.HIGH);
  }

  // ---------------------------------------------------------------------------------------
  // Impossible Finding line ranges, end-to-end through the converter (Finding's own invariants
  // are unit-tested directly in FindingTest; these prove the converter's exception-wrapping path
  // also handles a compact-constructor failure surfacing from inside a nested JSON array).
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldThrowAgentOutputParsingException_whenAFindingReportsAZeroStartLine() {
    // Arrange
    String json = """
      {
        "review": "Looks fine.",
        "findings": [
          {"file": "Foo.java", "startLine": 0, "endLine": 1, "rule": "r", "severity": "low",
           "explanation": "e", "recommendation": "rec"}
        ],
        "truncated": false
      }
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  @Test
  void shouldThrowAgentOutputParsingException_whenAFindingReportsEndLineBeforeStartLine() {
    // Arrange
    String json = """
      {
        "review": "Looks fine.",
        "findings": [
          {"file": "Foo.java", "startLine": 10, "endLine": 5, "rule": "r", "severity": "low",
           "explanation": "e", "recommendation": "rec"}
        ],
        "truncated": false
      }
      """;

    // Act / Assert
    assertThatThrownBy(() -> converter.convert(json))
      .isInstanceOf(AgentOutputParsingException.class);
  }

  // ---------------------------------------------------------------------------------------
  // Generated schema shape
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldExposeNonBlankFormatText_containingTheReviewFieldAndARequiredMarker() {
    // Act
    String format = converter.getFormat();

    // Assert
    assertThat(format).isNotBlank();
    assertThat(format).contains("\"review\"");
    assertThat(format).contains("\"required\"");
  }

  @Test
  void shouldBuildResponseFormatOfTypeJsonSchemaWithStrictModeEnabled() {
    // Act
    AzureOpenAiResponseFormat format = converter.responseFormat();

    // Assert
    assertThat(format.getType()).isEqualTo(AzureOpenAiResponseFormat.Type.JSON_SCHEMA);
    assertThat(format.getJsonSchema().getName()).isEqualTo("code_review_response");
    assertThat(format.getJsonSchema().getStrict()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldMarkReviewAsRequiredInTheGeneratedSchemaMap() {
    // Act
    Map<String, Object> schema = converter.responseFormat().getJsonSchema().getSchema();

    // Assert
    assertThat(schema).containsKey("required");
    assertThat((List<Object>) schema.get("required")).contains("review");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldConstrainSeverityToExactlyTheFiveAllowedLowercaseValues_inTheGeneratedSchemaMap() {
    // Act: walk to properties.findings.items.properties.severity.enum, the actual nesting the
    // real generator produces (see CodeReviewStructuredOutputConverter's own Javadoc for how this
    // was determined by inspecting the real, running generator's output rather than assumed).
    Map<String, Object> schema = converter.responseFormat().getJsonSchema().getSchema();
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    Map<String, Object> findings = (Map<String, Object>) properties.get("findings");
    Map<String, Object> items = (Map<String, Object>) findings.get("items");
    Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
    Map<String, Object> severity = (Map<String, Object>) itemProperties.get("severity");
    List<Object> severityEnum = (List<Object>) severity.get("enum");

    // Assert
    assertThat(severityEnum).containsExactlyInAnyOrder("blocker", "high", "medium", "low", "info");
    assertThat(severityEnum).doesNotContain("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
  }

  // ---------------------------------------------------------------------------------------
  // Direct tests of the package-private severity-enum schema correction helper. The real,
  // BeanOutputConverter-generated schema for CodeReviewResponse has exactly one "enum" node
  // today (severity's), so the guard-clause branches below (wrong size, right size but different
  // content, a differently-keyed node, arbitrary nesting) are not reachable through the public
  // responseFormat()/getFormat() path alone - these hand-built fragments exercise them directly.
  // ---------------------------------------------------------------------------------------

  @Test
  void shouldReplaceEnumArray_whenItExactlyMatchesTheFiveSeverityConstantNames() {
    // Arrange
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "string");
    node.put("enum", List.of("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"));

    // Act
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(node);

    // Assert
    assertThat((List<Object>) node.get("enum")).containsExactly("blocker", "high", "medium", "low", "info");
  }

  @Test
  void shouldReplaceEnumArray_whenTheFiveSeverityConstantNamesAreInADifferentOrder() {
    // Arrange
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("enum", List.of("INFO", "LOW", "MEDIUM", "HIGH", "BLOCKER"));

    // Act
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(node);

    // Assert: replaced with the canonical, Severity-declared order, not the input's order
    assertThat((List<Object>) node.get("enum")).containsExactly("blocker", "high", "medium", "low", "info");
  }

  @Test
  void shouldLeaveEnumArrayUnchanged_whenItHasFewerThanFiveElements() {
    // Arrange
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("enum", List.of("BLOCKER", "HIGH"));

    // Act
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(node);

    // Assert
    assertThat((List<Object>) node.get("enum")).containsExactly("BLOCKER", "HIGH");
  }

  @Test
  void shouldLeaveEnumArrayUnchanged_whenItHasFiveElementsButDifferentContent() {
    // Arrange: same size as the severity constant set, but not the severity constant set itself
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("enum", List.of("RED", "GREEN", "BLUE", "YELLOW", "PURPLE"));

    // Act
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(node);

    // Assert
    assertThat((List<Object>) node.get("enum")).containsExactly("RED", "GREEN", "BLUE", "YELLOW", "PURPLE");
  }

  @Test
  void shouldLeaveNonEnumKeyedListUnchanged_evenWhenItMatchesTheSeverityConstantNames() {
    // Arrange: the exact severity names, but not under the "enum" key
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("notEnum", List.of("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"));

    // Act
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(node);

    // Assert
    assertThat((List<Object>) node.get("notEnum")).containsExactly("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
  }

  @Test
  void shouldCorrectEnumArrayAtArbitraryNestingDepth_insideMapsAndListsCombined() {
    // Arrange: properties -> findings -> items[0] -> severity -> enum, mirroring the real shape's
    // depth but hand-built and reduced to only what's needed to prove recursion works.
    Map<String, Object> severityNode = new LinkedHashMap<>();
    severityNode.put("enum", List.of("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"));
    Map<String, Object> items = new LinkedHashMap<>();
    items.put("oneOf", List.of(severityNode));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("properties", Map.of("findings", items));

    // Act
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(root);

    // Assert
    assertThat((List<Object>) severityNode.get("enum")).containsExactly("blocker", "high", "medium", "low", "info");
  }

  @Test
  void shouldNotThrow_whenNodeIsNeitherAMapNorAList() {
    // Act / Assert: recursion base case for a scalar leaf value
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace("just a string");
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(42);
    CodeReviewStructuredOutputConverter.correctSeverityEnumValuesInPlace(null);
  }

  @Test
  void shouldReturnFalse_whenValueIsNotAList() {
    // Act / Assert
    assertThat(CodeReviewStructuredOutputConverter.isSeverityConstantNameList("not-a-list")).isFalse();
    assertThat(CodeReviewStructuredOutputConverter.isSeverityConstantNameList(null)).isFalse();
  }

  @Test
  void shouldReturnTheSameMapInstance_whenCorrectingSeverityEnumValuesAtTheTopLevel() {
    // Arrange
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("enum", List.of("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"));

    // Act
    Map<String, Object> result = CodeReviewStructuredOutputConverter.correctSeverityEnumValues(schema);

    // Assert
    assertThat(result).isSameAs(schema);
    assertThat((List<Object>) result.get("enum")).containsExactly("blocker", "high", "medium", "low", "info");
  }
}
