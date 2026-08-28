package com.epam.codereviewagent.service;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.api.model.Severity;
import com.epam.codereviewagent.exception.AgentOutputParsingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts a model's final-answer JSON text into a {@link CodeReviewResponse} and exposes the
 * provider-facing JSON schema ({@link #getFormat()}/{@link #responseFormat()}) used to constrain
 * that text in the first place.
 * <p>
 * Modeled directly on {@code 01-prompting-llm}'s {@code ChatStructuredOutputConverter}: schema
 * generation is delegated to Spring AI's {@link BeanOutputConverter} (with one targeted
 * correction — see "Real, empirically-discovered defect" below — not applied by
 * {@code 01-prompting-llm} because its own enum never needed it), while JSON parsing and
 * validation is performed with a separate, plain {@link ObjectMapper} so the exact failure mode
 * (blank input / malformed JSON / missing required field / an invariant violation inside a
 * nested {@code Finding}) can be consistently mapped onto {@link AgentOutputParsingException},
 * never left as a raw, uncaught exception escaping this class.
 * <p>
 * <b>Decompilation findings that informed this class (Architecture Note A2)</b> — verified by
 * decompiling the resolved {@code spring-ai-model-1.1.2.jar} and
 * {@code spring-ai-azure-openai-1.1.2.jar} (the exact versions this reactor resolves; same jars
 * Increment 3 already located under
 * {@code C:\Users\Fedir_Rudiachenko\.m2\repository\org\springframework\ai\...}) with
 * {@code javap -p -c}, not assumed from memory:
 * <ul>
 *   <li>{@code BeanOutputConverter}'s public surface is unchanged from what {@code 01-prompting-llm}
 *       already uses: {@code getFormat()}, {@code getJsonSchemaMap()} (returns
 *       {@code Map<String, Object>}, and — confirmed by decompiling {@code getJsonSchemaMap()}
 *       itself with {@code -c} — parses the schema string fresh via
 *       {@code objectMapper.readValue(jsonSchema, Map.class)} on <b>every call</b>, so each
 *       returned map is an independent, freely mutable {@code LinkedHashMap}; mutating one
 *       returned instance has no effect on the converter's own internal state or on any other
 *       call's result), and the {@code (Class<T>)}/{@code (Class<T>, ObjectMapper)} constructors
 *       — same class/method shapes as the already-proven pattern this converter mirrors,
 *       re-confirmed rather than re-guessed for this module/version.
 *   <li>{@code BeanOutputConverter.generateSchema()}'s own bytecode (decompiled with {@code -c})
 *       shows it registers victools' {@code JacksonModule} with only
 *       {@code JacksonOption.RESPECT_JSONPROPERTY_REQUIRED}/{@code RESPECT_JSONPROPERTY_ORDER},
 *       builds the generator with {@code SchemaVersion.DRAFT_2020_12} /
 *       {@code OptionPreset.PLAIN_JSON}, forces
 *       {@code Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT} (so every generated object node
 *       carries {@code "additionalProperties": false}), and — the operative fact for this
 *       increment's "review is marked required" acceptance criterion — installs a required-field
 *       check whose decompiled lambda body (
 *       {@code lambda$generateSchema$0(FieldScope): iconst_1; ireturn}) unconditionally returns
 *       {@code true}: every field of every generated schema is marked required, with no
 *       per-field opt-out annotation needed. This means {@code review} (and, incidentally,
 *       {@code findings}/{@code truncated}) are marked required "for free"; nothing extra was
 *       added to {@code CodeReviewResponse} to force it.
 *   <li><b>Real, empirically-discovered defect (not theoretical, found by actually running the
 *       generator and inspecting its output — a stronger check than bytecode reading alone):</b>
 *       the two {@code JacksonOption}s enabled above do <b>not</b> include
 *       {@code JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE} (confirmed to exist as a distinct,
 *       separate option by decompiling {@code jsonschema-module-jackson-4.38.0.jar}'s own
 *       {@code JacksonOption} enum — the exact version this reactor resolves, confirmed via
 *       {@code mvn dependency:tree -Dincludes=com.github.victools}). Without it, victools ignores
 *       {@link Severity}'s {@code @JsonValue} entirely when generating its {@code enum} schema
 *       node and instead emits the raw Java constant names
 *       ({@code ["BLOCKER","HIGH","MEDIUM","LOW","INFO"]}) — confirmed directly by printing
 *       {@code schemaConverter.getJsonSchemaMap()}'s actual output during implementation. Left
 *       uncorrected, this would be a self-contradictory contract: the schema would instruct the
 *       model to emit uppercase severity strings under Azure's {@code strict(true)} JSON-schema
 *       decoding while every other prompt/documentation surface in this system describes and
 *       expects the lowercase ticket values — a schema that no longer matches this contract's own
 *       documented shape, even though {@link Severity#fromJsonValue(String)} was later relaxed
 *       (code review, retry 1, Medium finding) to accept uppercase input regardless.
 *       {@code BeanOutputConverter} exposes no hook to add
 *       {@code JacksonOption}s to its internal generator, so this converter instead
 *       post-processes the generated schema map ({@link #correctSeverityEnumValues(Map)}),
 *       replacing the one {@code enum} array whose values are exactly the five {@link Severity}
 *       constant names with {@link Severity}'s own lowercase {@code jsonValue()} strings, in
 *       {@link Severity}'s declared order — a minimal, targeted fix rather than hand-rolling the
 *       whole schema generator. This correction is applied only to {@link #responseFormat()}'s
 *       schema map, since that is the JSON schema Increment 5's phase-2, tools-disabled call
 *       actually attaches to the model request (Architecture Note A5); {@link #getFormat()}
 *       continues to delegate to {@code BeanOutputConverter}'s own, unmodified output verbatim,
 *       since this system's two-phase design (A5) never embeds {@code getFormat()}'s text into a
 *       prompt at runtime — see {@code context/PROGRESS.md}'s Increment 4 entry for the full
 *       "Decisions made" writeup.
 *   <li>{@code AzureOpenAiResponseFormat}/{@code .Builder}/{@code .JsonSchema}/
 *       {@code .JsonSchema.Builder}/{@code .Type} (decompiled from
 *       {@code spring-ai-azure-openai-1.1.2.jar}) expose exactly the same builder methods
 *       {@code 01-prompting-llm} already uses ({@code type(Type.JSON_SCHEMA)},
 *       {@code jsonSchema(JsonSchema)}, {@code JsonSchema.builder().name(...).schema(Map).strict(Boolean)}) —
 *       re-confirmed for this module rather than copied on faith.
 * </ul>
 * <p>
 * <b>Malformed-model-JSON handling — a real, tested failure mode, not a theoretical one.</b>
 * {@link #convert(String)} never lets a raw Jackson exception (or any other {@code RuntimeException})
 * escape: blank/{@code null} input, syntactically invalid or truncated JSON, a bare top-level JSON
 * scalar (a string or number with no enclosing object — see
 * {@link CodeReviewResponse}'s own Javadoc for why this is disabled rather than silently accepted
 * as a "successful", zero-findings review — code review, retry 1, High finding), a JSON array where
 * an object is expected, valid JSON with the wrong field types (including a fractional numeric
 * value, e.g. {@code 1.5}, for an integer line-number field — code review, retry 1, Low finding),
 * trailing content after an otherwise-valid JSON value (e.g. a second concatenated JSON object, or
 * stray prose after the closing brace — code review, retry 1, Low finding), a missing required
 * {@code review} field, an out-of-enum {@code severity} value (case-insensitively and after
 * trimming whitespace — a wholly unknown value like {@code "critical"} is still rejected, but a
 * merely differently-cased variant like {@code "HIGH"} is now accepted, not rejected — see
 * {@link Severity}'s own Javadoc; code review, retry 1, Medium finding), and an impossible
 * {@code Finding} line range (non-positive line number, or {@code endLine < startLine} — see
 * {@link com.epam.codereviewagent.api.model.Finding}'s compact constructor) are all funneled into
 * {@link AgentOutputParsingException}. Content wrapped in markdown code fences (e.g. a
 * <code>```json</code> block) is deliberately <b>not</b> auto-stripped before parsing: Increment 5's
 * phase-2 call attaches this class's own {@link #responseFormat()} (a {@code strict(true)}
 * JSON-schema {@code response_format}), under which Azure OpenAI is not expected to wrap its
 * output in markdown at all; treating a fenced response as malformed (rather than silently
 * recovering from it) surfaces a real provider/format regression instead of masking it.
 * <p>
 * <b>Recorded, deliberately unchanged (code review, retry 1, Medium finding — an explicit
 * Increment 5 hand-off, not a silent gap):</b> parsing remains all-or-nothing — one malformed
 * {@code Finding} inside an otherwise-valid {@code findings} array discards the entire response
 * (the review text and every other, otherwise-valid finding), because Increment 4's own
 * plan-mandated acceptance criteria require rejection rather than silent per-finding defaulting.
 * See {@code context/PROGRESS.md}'s Increment 4 retry 1 entry and {@code context/PLAN.md}'s
 * Increment 5 section for the hand-off: Increment 5 must decide between retrying the phase-2 call
 * once, or dropping only the offending finding with a logged {@code WARN}, rather than discarding
 * the whole response.
 */
@Component
public class CodeReviewStructuredOutputConverter implements StructuredOutputConverter<CodeReviewResponse> {

  private static final String RESPONSE_FORMAT_NAME = "code_review_response";
  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
    .findAndAddModules()
    // Code review, retry 1, Low finding: without this, `{"review":"..."}\ntrailing text` and two
    // concatenated top-level objects both silently succeed using only the first parsed value,
    // inconsistent with leading prose being correctly rejected. See
    // CodeReviewStructuredOutputConverterTest's trailing-content tests.
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    // Code review, retry 1, Low finding: without this, a fractional line number (e.g.
    // "startLine": 1.5) is silently truncated to an int instead of being rejected, while an
    // equally-invalid non-numeric value ("not-a-number") was already correctly rejected. See
    // CodeReviewStructuredOutputConverterTest.shouldThrowAgentOutputParsingException_whenStartLineFieldIsAFractionalNumber.
    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
    .build();
  private static final List<String> SEVERITY_JSON_VALUES_IN_DECLARATION_ORDER = Arrays.stream(Severity.values())
    .map(Severity::jsonValue)
    .toList();
  private static final Set<String> SEVERITY_CONSTANT_NAMES = Arrays.stream(Severity.values())
    .map(Enum::name)
    .collect(Collectors.toUnmodifiableSet());

  private final BeanOutputConverter<CodeReviewResponse> schemaConverter =
    new BeanOutputConverter<>(CodeReviewResponse.class);
  private final AzureOpenAiResponseFormat responseFormat = buildResponseFormat();

  @Override
  public CodeReviewResponse convert(String source) {
    if (!StringUtils.hasText(source)) {
      throw new AgentOutputParsingException("Model returned a blank structured code review response");
    }

    try {
      CodeReviewResponse converted = OBJECT_MAPPER.readValue(source, CodeReviewResponse.class);
      return validate(converted);
    } catch (JsonProcessingException exception) {
      throw new AgentOutputParsingException("Model returned malformed structured code review JSON", exception);
    } catch (IllegalArgumentException exception) {
      // Defensive: catches any Finding/Severity invariant violation not already wrapped by
      // Jackson's own exception hierarchy above (e.g. if Jackson ever changes how it surfaces a
      // record canonical-constructor failure).
      throw new AgentOutputParsingException("Model returned an invalid structured code review response",
        exception);
    }
  }

  /**
   * {@inheritDoc}
   * <p>
   * <b>Warning: this returns {@link BeanOutputConverter}'s raw, <i>uncorrected</i> schema text —
   * {@code severity}'s {@code enum} values here are the uppercase Java constant names
   * ({@code "BLOCKER"}, {@code "HIGH"}, ...), not the lowercase values this contract's own
   * {@link Severity} deserializer actually accepts.</b> Only {@link #responseFormat()}'s schema map
   * receives the severity-enum correction (see this class's own top-level Javadoc, "Real,
   * empirically-discovered defect", and {@code correctSeverityEnumValues(Map)}). This method has no
   * production caller today, but if a future increment embeds this text directly into a live
   * prompt, it will silently reintroduce the uppercase/lowercase contract mismatch this class exists
   * to prevent — use {@link #responseFormat()} for anything that actually reaches a model call.
   */
  @Override
  public @NonNull String getFormat() {
    return schemaConverter.getFormat();
  }

  /**
   * The Azure OpenAI {@code response_format} carrying this contract's JSON schema with
   * {@code strict(true)}, for use by the phase-2, tools-disabled structured-output call
   * (Increment 5). Never combined with tool callbacks on the same {@code ChatOptions}, per
   * Architecture Note A5. Its schema map has {@code severity}'s {@code enum} corrected to the
   * five lowercase {@link Severity} values — see this class's own Javadoc, "Real,
   * empirically-discovered defect", for why that correction is necessary.
   */
  public AzureOpenAiResponseFormat responseFormat() {
    return responseFormat;
  }

  private CodeReviewResponse validate(CodeReviewResponse converted) {
    if (converted == null || !StringUtils.hasText(converted.review())) {
      throw new AgentOutputParsingException("Model response is missing the required 'review' field");
    }
    return converted;
  }

  private AzureOpenAiResponseFormat buildResponseFormat() {
    Map<String, Object> correctedSchema = correctSeverityEnumValues(schemaConverter.getJsonSchemaMap());
    return AzureOpenAiResponseFormat.builder()
      .type(AzureOpenAiResponseFormat.Type.JSON_SCHEMA)
      .jsonSchema(AzureOpenAiResponseFormat.JsonSchema.builder()
        .name(RESPONSE_FORMAT_NAME)
        .schema(correctedSchema)
        .strict(true)
        .build())
      .build();
  }

  /**
   * Walks the given, freshly-generated (and therefore freely mutable — see this class's own
   * Javadoc) schema map and replaces the one {@code enum} array whose values are exactly the five
   * {@link Severity} Java constant names ({@code BLOCKER}/{@code HIGH}/{@code MEDIUM}/{@code LOW}/
   * {@code INFO}) with {@link Severity}'s own lowercase {@code jsonValue()} strings, in
   * {@link Severity}'s declared order. Deliberately narrow (matches only a 5-element list equal to
   * the exact severity constant-name set) rather than a generic "lower-case every enum" pass,
   * since {@link Severity} is the only enum in this contract that needs correcting.
   * <p>
   * Package-private (not {@code private}), together with its two helpers below, specifically so
   * {@code CodeReviewStructuredOutputConverterTest} can exercise the guard clauses (wrong-size
   * list, right-size-but-different-content list, a non-{@code "enum"}-keyed node, arbitrary
   * nesting depth) directly against hand-built schema fragments: the real, {@code BeanOutputConverter}
   * -generated schema for {@code CodeReviewResponse} has exactly one {@code "enum"} node today, so
   * those guard branches are not otherwise reachable through the public {@link #responseFormat()}
   * path alone.
   * <p>
   * <b>Why this is content-based (matching the exact 5-element severity-constant-name set) rather
   * than path-based (walking to a known {@code $defs}/{@code $ref} location):</b> the current
   * schema, as generated by {@code BeanOutputConverter} for this contract, inlines every type
   * directly with no {@code $defs}/{@code $ref} indirection at all — {@code Severity} does not get
   * its own named, shared schema definition; its {@code enum} node is duplicated inline wherever it
   * is referenced. A content match is therefore both sufficient and simpler than a path match today.
   * <b>This would need revisiting</b> if a future increment introduces a shared record whose schema
   * generation starts using {@code $defs}/{@code $ref} (e.g. because {@code Severity}, or another
   * type, becomes reused across more than one top-level response contract) — in that case a single
   * {@code $defs}-keyed correction could become both more precise (correcting the definition once,
   * not at every inline occurrence) and necessary (if a future enum's constant names coincidentally
   * collide with another enum's, content-only matching could no longer disambiguate them).
   */
  static Map<String, Object> correctSeverityEnumValues(Map<String, Object> generatedSchema) {
    correctSeverityEnumValuesInPlace(generatedSchema);
    return generatedSchema;
  }

  @SuppressWarnings("unchecked")
  static void correctSeverityEnumValuesInPlace(Object node) {
    if (node instanceof Map<?, ?> rawMap) {
      Map<String, Object> map = (Map<String, Object>) rawMap;
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        Object value = entry.getValue();
        if ("enum".equals(entry.getKey()) && isSeverityConstantNameList(value)) {
          entry.setValue(new ArrayList<>(SEVERITY_JSON_VALUES_IN_DECLARATION_ORDER));
        } else {
          correctSeverityEnumValuesInPlace(value);
        }
      }
    } else if (node instanceof List<?> list) {
      for (Object element : list) {
        correctSeverityEnumValuesInPlace(element);
      }
    }
  }

  static boolean isSeverityConstantNameList(Object value) {
    if (!(value instanceof List<?> list) || list.size() != SEVERITY_CONSTANT_NAMES.size()) {
      return false;
    }
    Set<String> actual = list.stream().map(String::valueOf).collect(Collectors.toSet());
    return actual.equals(SEVERITY_CONSTANT_NAMES);
  }
}
