package com.epam.codereviewagent.runner;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import com.epam.codereviewagent.service.ExecutiveSummarySubAgent;
import com.epam.codereviewagent.support.SafeLogFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Gates {@link ExecutiveSummarySubAgent} behind a program argument (R14 / Increment 7) so it never
 * fires during ordinary {@code POST /code-review} server operation, per {@code context/TICKET.md}'s
 * own Ambiguities section: this is the only place {@link ExecutiveSummarySubAgent#summarize} is ever
 * called anywhere in this module — {@link com.epam.codereviewagent.controller.CodeReviewController}
 * and {@link com.epam.codereviewagent.service.CodeReviewReactAgent} have no reference to it at all,
 * so a failure inside the sub-agent can never reach, fail, or corrupt the primary
 * {@code POST /code-review} response: the two are structurally decoupled entry points, not merely
 * decoupled by a try/catch.
 *
 * <p><b>Triggering.</b> Pass {@code --executive-summary-input=<path>} as a program argument (e.g.
 * {@code java -jar app.jar --executive-summary-input=review.json}, or
 * {@code ./mvnw -pl 03-code-review-agent spring-boot:run
 * -Dspring-boot.run.arguments=--executive-summary-input=review.json}). {@code <path>} must name a
 * file containing a single JSON document deserializable as {@link CodeReviewResponse} (the same
 * shape {@code POST /code-review} returns). The resulting executive summary is printed to
 * {@link System#out} (the production default sink; see this class's own constructor for how a test
 * substitutes a captured sink instead). If the argument is absent, {@link #run(ApplicationArguments)}
 * returns immediately: zero {@link ExecutiveSummarySubAgent} calls, zero output, so ordinary server
 * startup is completely unaffected — proved at the unit level (see {@code ExecutiveSummaryRunnerTest}'s
 * "no matching argument" cases) and, at the full-context level, by this bean being successfully
 * created and wired during {@code HermeticApplicationContextIT}'s context refresh in this session
 * (confirmed directly in the Failsafe log: bean instantiation for the whole application, including
 * this class, completes before that IT's own pre-existing, already-documented
 * {@code Selector.open()}/loopback-socket sandbox limitation is ever reached — see this increment's
 * {@code context/PROGRESS.md} entry for the exact evidence; that IT itself is expected to reach 200 OK
 * unchanged in an environment where the embedded Tomcat connector can actually start).
 *
 * <p><b>Error handling.</b> Every failure this method can encounter — the input file missing or
 * unreadable, its content not being valid {@link CodeReviewResponse} JSON, or the sub-agent's own
 * {@link ExecutiveSummarySubAgent#summarize} call failing (a blank model response, a provider error,
 * or the prompt resource itself being unreadable) — is caught in one deliberately broad
 * {@code catch (Exception e)} block: this method is the outermost boundary of a synchronous,
 * one-shot CLI code path with no {@code @RestControllerAdvice}-equivalent layer above it, unlike
 * {@code CodeReviewController} (Increment 6). Letting any exception escape {@link
 * #run(ApplicationArguments)} would abort the whole Spring Boot startup sequence with a raw stack
 * trace; this method instead logs at {@code ERROR} and prints one clear, static message, per this
 * increment's own Task 3.
 *
 * <p><b>Why this class builds its own local {@link ObjectMapper} rather than injecting a
 * Spring-managed one (a real defect found and fixed during this increment, not a stylistic
 * choice).</b> This reactor's {@code spring-boot-starter-jackson} (Spring Boot 4.0.2, confirmed via
 * {@code mvn dependency:tree}) auto-configures a Jackson <b>3</b> {@code tools.jackson.databind.ObjectMapper}
 * bean, not the classic Jackson 2 {@link com.fasterxml.jackson.databind.ObjectMapper} this module's
 * {@code api.model} records are written against - so a constructor parameter of type {@code
 * com.fasterxml.jackson.databind.ObjectMapper} has no matching Spring bean at all and fails context
 * refresh with {@code NoSuchBeanDefinitionException} (reproduced directly:
 * {@code HermeticApplicationContextIT} failed exactly this way the first time this class was wired
 * with {@code @Autowired ObjectMapper}, before this fix). {@link
 * com.epam.codereviewagent.service.CodeReviewStructuredOutputConverter} (Increment 4) already
 * established the working pattern this class now follows: build a local, non-Spring-managed Jackson
 * 2 {@link ObjectMapper} directly, never rely on dependency injection for it.
 */
@Component
@Slf4j
public class ExecutiveSummaryRunner implements ApplicationRunner {

  static final String INPUT_ARGUMENT_NAME = "executive-summary-input";

  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

  private final ExecutiveSummarySubAgent executiveSummarySubAgent;
  private final PrintStream out;

  @Autowired
  public ExecutiveSummaryRunner(ExecutiveSummarySubAgent executiveSummarySubAgent) {
    this(executiveSummarySubAgent, System.out);
  }

  /**
   * Test-only constructor: substitutes a captured {@link PrintStream} sink instead of {@link
   * System#out}, so tests never need {@code System.setOut(...)} (global mutable state that would
   * break parallel test execution) to observe what this runner prints.
   */
  ExecutiveSummaryRunner(ExecutiveSummarySubAgent executiveSummarySubAgent, PrintStream out) {
    this.executiveSummarySubAgent = executiveSummarySubAgent;
    this.out = out;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!args.containsOption(INPUT_ARGUMENT_NAME)) {
      log.debug("No --{} argument present; ordinary server startup, executive-summary runner is a no-op.",
        INPUT_ARGUMENT_NAME);
      return;
    }

    List<String> values = args.getOptionValues(INPUT_ARGUMENT_NAME);
    String inputPath = (values == null || values.isEmpty()) ? null : values.get(0);
    if (!StringUtils.hasText(inputPath)) {
      log.error("The --{} argument was provided with no file path value.", INPUT_ARGUMENT_NAME);
      out.println("ERROR: --" + INPUT_ARGUMENT_NAME + " requires a file path, e.g. --"
        + INPUT_ARGUMENT_NAME + "=path/to/review.json");
      return;
    }

    try {
      String json = Files.readString(Path.of(inputPath), StandardCharsets.UTF_8);
      CodeReviewResponse response = OBJECT_MAPPER.readValue(json, CodeReviewResponse.class);
      String summary = executiveSummarySubAgent.summarize(response);
      out.println(summary);
    } catch (Exception exception) {
      log.error("Failed to produce an executive summary from input file '{}': exceptionType={}",
        SafeLogFormatter.format(inputPath), exception.getClass().getSimpleName(), exception);
      out.println("ERROR: Failed to produce an executive summary from '" + inputPath
        + "'. See the application log for details.");
    }
  }
}
