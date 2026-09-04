package com.epam.codereviewagent.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeReviewResponseTest {

  /**
   * Copied byte-for-byte (backslashes doubled only because this is now a Java text block, not a
   * reformulation) from {@code 03-code-review-agent/README.md}'s own "Response example" JSON
   * block (the ticket's own example text, not a paraphrase of it) — proves R16 backward
   * compatibility directly against what the README actually documents.
   */
  private static final String README_EXAMPLE_JSON = """
    {
      "review": "### Code Review for `CodeReviewReactAgent.java`\\n\\n#### Violations \
    Identified:\\n\\n1. **Naming Conventions**\\n   - Rule: \\"Never use prefix 'Code' for \
    classes.\\"\\n     - The class name `CodeReviewReactAgent` violates this rule since it uses \
    the prefix \\"Code\\".\\n     - Suggest renaming the class to `ReviewReactAgent` or \
    similar.\\n\\n2. **Class Organization**\\n   - Rule: \\"Import statements should be organized \
    and no wildcards.\\"\\n     - The import statements do not contain wildcards, so this is \
    compliant.\\n     - However, ensure imports are grouped logically (e.g., third-party libraries \
    vs. application-specific ones).\\n\\n3. **Comments and Documentation**\\n   - Rule: \\"Use \
    JavaDoc for all public classes, interfaces, and methods.\\"\\n     - The \
    `CodeReviewReactAgent` class and its `interact` method lack JavaDoc comments. Add JavaDoc that \
    includes `@param`, `@return`, and any relevant `@throws` tags.\\n\\n### Summary:\\nViolations \
    were found in naming conventions, class organization, comments/documentation, and method \
    design. Address these points to ensure compliance with Java coding conventions."
    }
    """;

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void shouldDeserializeReadmeExampleJsonSuccessfully_whenOnlyReviewFieldIsPresent()
    throws Exception {
    // Arrange (README_EXAMPLE_JSON is the literal ticket example, defined above)

    // Act
    CodeReviewResponse response =
      objectMapper.readValue(README_EXAMPLE_JSON, CodeReviewResponse.class);

    // Assert
    assertThat(response.review()).startsWith("### Code Review for `CodeReviewReactAgent.java`");
    assertThat(response.review()).contains("Naming Conventions", "Class Organization",
      "Comments and Documentation");
    assertThat(response.findings()).isNotNull().isEmpty();
    assertThat(response.truncated()).isFalse();
  }

  @Test
  void shouldNormalizeNullFindingsToEmptyList_whenConstructedDirectlyWithNullFindings() {
    // Arrange / Act
    CodeReviewResponse response = new CodeReviewResponse("review text", null, false);

    // Assert
    assertThat(response.findings()).isNotNull().isEmpty();
  }

  @Test
  void shouldDefaultFindingsAndTruncated_whenUsingTheSingleArgumentBackwardCompatibleConstructor() {
    // Arrange / Act
    CodeReviewResponse response = new CodeReviewResponse("review text only");

    // Assert
    assertThat(response.review()).isEqualTo("review text only");
    assertThat(response.findings()).isNotNull().isEmpty();
    assertThat(response.truncated()).isFalse();
  }

  @Test
  void shouldDefensivelyCopyFindings_whenConstructedWithAMutableList() {
    // Arrange
    List<Finding> mutableFindings = new ArrayList<>();
    mutableFindings.add(
      new Finding("Foo.java", 1, 2, "rule", Severity.HIGH, "explanation", "recommendation"));

    // Act
    CodeReviewResponse response = new CodeReviewResponse("review text", mutableFindings, false);
    mutableFindings.clear();

    // Assert: the response's own findings list is unaffected by mutating the source list afterward
    assertThat(response.findings()).hasSize(1);
    assertThatThrownBy(() -> response.findings().add(
      new Finding("Bar.java", 1, 1, "rule2", Severity.LOW, "explanation2", "recommendation2")))
      .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldFailToDeserialize_whenTopLevelJsonIsABareStringNotAnObject() {
    // Arrange: code review, retry 1, High finding. Before the single-argument convenience
    // constructor was annotated @JsonCreator(mode = DISABLED), Jackson auto-detected it as an
    // implicit delegating creator for records, so a bare top-level JSON string silently
    // deserialized into a "successful" response with review=<the string> and empty findings -
    // proven fixed here directly at the type's own boundary
    // (CodeReviewStructuredOutputConverterTest proves the same fix through the actual
    // convert(String) call path).
    String json = "\"just a string\"";

    // Act / Assert
    assertThatThrownBy(() -> objectMapper.readValue(json, CodeReviewResponse.class))
      .isInstanceOf(Exception.class);
  }

  @Test
  void shouldRoundTripFullResponse_whenFindingsAndTruncatedAreExplicitlyProvided()
    throws Exception {
    // Arrange
    CodeReviewResponse response = new CodeReviewResponse(
      "review text",
      List.of(new Finding("Foo.java", 10, 12, "no-magic-numbers", Severity.MEDIUM, "explanation",
        "recommendation")),
      true);

    // Act
    String json = objectMapper.writeValueAsString(response);
    CodeReviewResponse roundTripped = objectMapper.readValue(json, CodeReviewResponse.class);

    // Assert
    assertThat(roundTripped.review()).isEqualTo("review text");
    assertThat(roundTripped.truncated()).isTrue();
    assertThat(roundTripped.findings()).hasSize(1);
    assertThat(roundTripped.findings().get(0).file()).isEqualTo("Foo.java");
    assertThat(roundTripped.findings().get(0).startLine()).isEqualTo(10);
    assertThat(roundTripped.findings().get(0).endLine()).isEqualTo(12);
    assertThat(roundTripped.findings().get(0).severity()).isEqualTo(Severity.MEDIUM);
  }
}
