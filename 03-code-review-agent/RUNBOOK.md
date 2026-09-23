# Module 3 Runbook: Code Review Agent

This is the application runbook for Module 3 — build, run, configuration, and API reference for the
implementation. `README.md` is the original, unmodified assignment text; this file documents what was
actually built to satisfy it. This mirrors the same split already established for Module 2
(`02-rag/README.md` vs `02-rag/RUNBOOK.md`) — `README.md` is never edited by any increment.

An LLM-powered, tool-using ReAct agent reviews a single file or directory inside a configured
repository-root security boundary. It explores the repository, reads file content, retrieves
pre-loaded coding conventions, gets a lightweight LLM summary of the code, and computes deterministic
structural metrics — all via six real `@Tool`-annotated methods — before producing a dual-output review:
a human-readable summary and a machine-readable, severity-tagged findings array. Every conclusion is
evidence-based: if the agent never successfully reads a file, a runtime guard forces an honest
"no evidence gathered" result regardless of what the model itself claims.

## Prerequisites

- Java 21
- EPAM VPN access to DIAL, for any live run against a real Azure OpenAI/DIAL deployment
- Existing DIAL credentials in process environment variables

## Configuration

Set the following before starting the application (matches `README.md`'s own Setup section exactly —
these are the ticket's own required variable names, R15):

```powershell
$env:AZURE_OPEN_AI_KEY = '<dial-key>'
$env:AZURE_OPEN_AI_ENDPOINT = 'https://ai-proxy.lab.epam.com'
$env:AZURE_OPEN_AI_DEPLOYMENT_NAME = 'gpt-4o'
```

`AZURE_OPEN_AI_DEPLOYMENT_NAME` is switchable to either of the two other named deployments with no code
change (R15) — used by the R12 model-comparison subtask (see `evaluation/RESULTS.md`):

```powershell
# $env:AZURE_OPEN_AI_DEPLOYMENT_NAME = 'gpt-4.1-nano-2025-04-14'
# $env:AZURE_OPEN_AI_DEPLOYMENT_NAME = 'gpt-5-mini-2025-08-07'
```

### `app.code-review.*` properties (`CodeReviewProperties`)

| Property | Default | Meaning |
|---|---|---|
| `app.code-review.repository-root` | `.` (process working directory) | Root directory that bounds **every** file/directory access the agent's tools can perform. Every relative `userInput` the agent is asked to review, and every `readFile`/`exploreRepository` tool call the model makes, is resolved and validated against this root — see "Security boundary" below. |
| `app.code-review.max-iterations` | `8` | Maximum number of ReAct-loop tool-calling rounds before the agent aborts with `AgentIterationLimitExceededException` rather than return a partial/fabricated answer (Experiment #7's guard). **Validated with `@Min(1)`** (`jakarta.validation.constraints.Min`, `@Validated` on `CodeReviewProperties`): a configured value `<= 0` fails Spring Boot **startup itself** (JSR-303 configuration-properties binding validation), not silently at request time on every single review. |
| `app.code-review.max-file-chars` | `20000` | Maximum number of characters returned from a single file read before the content is truncated (with a visible marker appended, and the response's `truncated` field forced `true` regardless of what the model itself reports — Experiment #6). |
| `app.code-review.system-prompt` | `classpath:prompts/code-review-system-prompt.md` | The main ReAct agent's system prompt. |
| `app.code-review.executive-summary-prompt` | `classpath:prompts/executive-summary-system-prompt.md` | The executive-summary sub-agent's own, separate system prompt. |
| `app.code-review.executive-summary-auto-trigger-enabled` | `true` | See "Executive summary" below. |
| `app.conventions.resources` | `[classpath:documents/java-convention.md, classpath:documents/python-convention.md]` | Pre-loaded coding-convention documents; only `java`/`python` are currently loaded (Experiment #3 — any other language honestly reports "no convention found"). |

No hardcoded configuration value exists anywhere in `src/main` for any of the above — every one is a
`CodeReviewProperties` field with an externalized default in `application.yml`.

### Security boundary (`RepositoryPathResolver`)

Every path this module ever touches — the top-level `userInput` and every `readFile`/`exploreRepository`
tool argument the model itself supplies — passes through one deterministic rule, independently testable
and independently proven (R2, R3):

1. Reject blank input and any input containing a NUL character.
2. Reject **any absolute path** outright — Windows drive-absolute, Windows drive-relative (`C:foo`), UNC
   (`\\server\share\x`), POSIX leading `/`, and an alternate-data-stream-shaped colon
   (`file.txt:stream`). The ticket's own wording requires a *relative* path (R2); absolute input is a
   security violation, not merely "outside root".
3. Resolve `root.resolve(relativeInput).normalize()`; anything that escapes the canonical root (e.g.
   `../../etc/passwd`) is rejected.
4. These checks always run and reject **before** any filesystem-existence check, so a rejected path
   never distinguishes "exists but forbidden" from "does not exist" — no information leak (see the HTTP
   status table below: `400` never reveals whether anything exists outside the root; `404` only ever
   reveals non-existence *inside* the already-permitted root).
5. If the path passes security and does not exist / is not a regular file, a different, distinguishable
   outcome results (see `FILE_NOT_FOUND` below) than a security violation.
6. On a host with symlink-creation privilege, a symlink inside the root pointing outside it is also
   rejected (canonicalized with `toRealPath()` and re-checked); on a host without that privilege (the
   common case on an unelevated Windows developer machine), this specific check is honestly
   capability-skipped in the test suite, never silently weakened to an unconditional pass.

**The controller itself enforces this boundary before any model call.** `CodeReviewController` calls
`RepositoryPathResolver#validateSecurityBoundary(userInput)` as its first statement, before
`CodeReviewReactAgent#interact` is ever invoked — a path-traversal or absolute-path `userInput` returns
`400`/`PATH_SECURITY_VIOLATION` deterministically, with **zero** `ChatModel` calls spent on it (proven,
not merely status-checked: `CodeReviewControllerTest` asserts
`verify(reviewReactAgent, never()).interact(any())` for this case). This check only inspects the
*shape* of the input (existence/kind is deliberately not checked here), so an ordinary relative file
path, a relative directory path, and a syntactically valid-but-nonexistent path all still reach the
agent unchanged.

## Build and run

From the repository root:

```powershell
.\mvnw.cmd -pl 03-code-review-agent test
.\mvnw.cmd -pl 03-code-review-agent integration-test
.\mvnw.cmd -pl 03-code-review-agent clean verify
```

### Canonical start command

```powershell
.\mvnw.cmd -pl 03-code-review-agent spring-boot:run
```

This matches `README.md`'s own documented start command and sets the process working directory to the
module directory (`03-code-review-agent/`), which is what `app.code-review.repository-root`'s default
(`.`) resolves against — the same reason `README.md`'s own example paths
(`03-code-review-agent/src/main/java/...`) work as-is when reviewed relative to the reactor root, since
the reactor root is one level above the module's own default repository root; point
`app.code-review.repository-root` explicitly at the reactor root if you want `userInput` paths relative
to the whole repository instead.

### Windows host note (Selector.open() / embedded-Tomcat limitation)

On some Windows hosts, `Selector.open()` (used by embedded Tomcat and by any `@SpringBootTest(webEnvironment
= RANDOM_PORT)` integration test) fails to establish a loopback connection. This module's own
`HermeticApplicationContextIT` is affected by exactly this limitation in the implementation sandbox this
module was built in — see `evaluation/RESULTS.md`'s "Known environment limitation" section for the full,
independently-reproduced isolation (a bare, Spring-free `Selector.open()` probe fails identically, and the
untouched `02-rag/RagChatIT` fails the same way). This is a host/JVM networking limitation, not a code
defect. If `spring-boot:run` or a `RANDOM_PORT` test fails immediately with a selector/pipe error on your
own machine, the same workaround already documented in `02-rag/RUNBOOK.md` applies: point the JVM's temp
directory at a short, non-default path before starting Maven or the jar.

```powershell
New-Item -ItemType Directory -Force -Path C:\Temp\javatmp | Out-Null
$env:TEMP = 'C:\Temp\javatmp'
$env:TMP = 'C:\Temp\javatmp'
```

## HTTP API

### `POST /code-review`

```http
POST /code-review
Content-Type: application/json

{
  "userInput": "03-code-review-agent/src/main/java/com/epam/codereviewagent/service/CodeReviewReactAgent.java"
}
```

`userInput` is required (`@NotBlank`) and must be a path relative to `app.code-review.repository-root` —
see "Security boundary" above.

**Response contract.** `README.md`'s own documented example (`{"review": "..."}`) remains a **valid
subset** of the real response shape (R16) — `findings` defaults to an empty array and `truncated`
defaults to `false` when absent, proven directly against the README's own literal example text (not a
paraphrase of it). The full shape, with a worked example showing `findings`, `truncated`, and all 5
`Severity` values:

```json
{
  "review": "### Code Review for `CodeReviewReactAgent.java`\n\nFound 3 issues across naming, structure, and documentation. See findings below for details.",
  "findings": [
    {
      "file": "src/main/java/com/epam/codereviewagent/service/CodeReviewReactAgent.java",
      "startLine": 42,
      "endLine": 78,
      "rule": "Long method",
      "severity": "high",
      "explanation": "The interact(String) method spans 36 lines combining loop control, tool execution, and structured-output parsing.",
      "recommendation": "Extract the phase-2 finalization logic into a dedicated private method."
    },
    {
      "file": "src/main/java/com/epam/codereviewagent/service/CodeReviewReactAgent.java",
      "startLine": 15,
      "endLine": 15,
      "rule": "Magic number",
      "severity": "low",
      "explanation": "The retry limit for phase-2 parsing is hardcoded as a bare literal.",
      "recommendation": "Extract it to a named constant."
    },
    {
      "file": "src/main/java/com/epam/codereviewagent/service/CodeReviewReactAgent.java",
      "startLine": null,
      "endLine": null,
      "rule": "Missing JavaDoc",
      "severity": "info",
      "explanation": "Public class lacks a class-level JavaDoc comment.",
      "recommendation": "Add JavaDoc summarizing the class's role in the ReAct loop."
    }
  ],
  "truncated": false
}
```

`severity` is always exactly one of the ticket's own five lowercase values: `blocker`, `high`, `medium`,
`low`, `info` (R10) — never uppercase, regardless of how the underlying model responds (deserialization
is lenient/case-insensitive on the way in; serialization is always the canonical lowercase form on the
way out). `startLine`/`endLine` are nullable — a finding need not reference a specific line range.
`truncated: true` means at least one piece of evidence gathered during the review was truncated to the
configured character limit; this field is set by the loop's own objective observation of tool output,
never solely trusted from the model's own claim (Experiment #6).

**Findings are evidence-based, structurally, not only by prompt instruction (R7).** If the agent never
successfully reads any file during a request (every `readFile` call failed, returned an empty-file
sentinel, or was never called), the response is forcibly replaced with an honest "no evidence gathered"
result — empty `findings`, an explanatory `review` string — regardless of what the model's own final
answer claimed (see `evaluation/RESULTS.md`'s Experiment #4 section for the exact, tested contract).

### Error responses

Every mapped failure returns a JSON `ApiError`:

```json
{
  "code": "PATH_SECURITY_VIOLATION",
  "message": "The requested path is not permitted: it must be a relative path within the repository root.",
  "violations": []
}
```

`violations` is always a non-null, possibly-empty array of `{ "field", "message" }` objects, populated
only for `VALIDATION_FAILED`. **No response body, for any row below, ever echoes a raw exception's own
message text** — every `message` is static and predefined; full exception detail (including any
caller-controlled path text, safely formatted to prevent log forging) is logged server-side only.

The complete exception → HTTP-status mapping (`CodeReviewExceptionHandler`, `@RestControllerAdvice`):

| # | Exception | HTTP status | `ApiError.code` | Log level | Notes |
|---|---|---|---|---|---|
| 1 | `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` | DEBUG | Blank/malformed `userInput` — a pure client mistake. |
| 2 | `PathSecurityViolationException` | 400 | `PATH_SECURITY_VIOLATION` | WARN | Traversal, absolute/drive-qualified path, embedded NUL, symlink escape. **Thrown from `CodeReviewController`'s own upfront pre-check, before `CodeReviewReactAgent#interact` is ever called — a traversal or absolute path returns 400 with zero model calls, not after a wasted review attempt.** |
| 3 | `FileNotFoundInRepositoryException` | 404 | `FILE_NOT_FOUND` | DEBUG | A syntactically valid, in-root path that does not exist (or is the wrong kind) — an ordinary, expected outcome, not a security concern. |
| 4 | `AgentIterationLimitExceededException` | 500 | `AGENT_ITERATION_LIMIT_EXCEEDED` | ERROR | The ReAct loop exhausted `app.code-review.max-iterations` without reaching a natural answer (Experiment #7). A server-side operational limit, not a request defect. |
| 5 | `AgentOutputParsingException` | 502 | `AGENT_OUTPUT_INVALID` | ERROR | The model's structured-output JSON could not be parsed into a valid response, even after one automatic retry. |
| 6 | `TransientAiException` / `NonTransientAiException` | 502 | `AI_PROVIDER_FAILURE` | ERROR | An upstream Azure OpenAI/DIAL failure (outage, rate limit, timeout, permanent provider error). |
| 7 | `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST` | DEBUG | The request body could not be parsed as JSON at all. |
| 8 | `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` | DEBUG | e.g. `GET /code-review`. |
| 9 | `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` | DEBUG | A `Content-Type` other than `application/json`. |
| 10 | any other `Exception` | 500 | `INTERNAL_ERROR` | ERROR | Catch-all safety net. |

**400 vs 404 leaks no information about anything outside the repository root.** Rows 1-2 are thrown from
*input shape alone*, before any existence check — `../../etc/passwd` and
`../../etc/does-not-exist-xyz` are indistinguishable, both `400`. Row 3 (`404`) can only ever be thrown
for input that *already* resolves strictly inside the permitted root, so it only ever reveals
non-existence within the portion of the filesystem `userInput`/the tools are already allowed to browse.

## Executive summary sub-agent (R14)

A second, genuinely separate, non-agentic sub-agent (`ExecutiveSummarySubAgent`) — no tools, no loop, one
`ChatModel` call — summarizes a completed review's `review` text and `findings[]`. It has **two,
independent** entry points, both printing to standard output (never returned in any HTTP response body):

### Entry point 1 — CLI, on demand: `--executive-summary-input=<path>`

A Spring `ApplicationRunner` (`ExecutiveSummaryRunner`) gated behind a program argument, so it never
fires during ordinary server operation unless explicitly invoked:

```powershell
.\mvnw.cmd -pl 03-code-review-agent spring-boot:run `
  "-Dspring-boot.run.arguments=--executive-summary-input=review.json"
```

or, against the executable JAR:

```powershell
java -jar .\03-code-review-agent\target\03-code-review-agent-<version>-exec.jar --executive-summary-input=review.json
```

`<path>` is resolved relative to the process working directory (a local operator-supplied CLI argument,
not caller-supplied network input — the repository-root security boundary intentionally does not apply
here) and must contain a single JSON document deserializable as the same `CodeReviewResponse` shape
`POST /code-review` returns — `README.md`'s own literal `{"review": "..."}` example is valid input. On
success, the sub-agent's summary is printed to `System.out` with a trailing newline, and nothing else. If
the argument is absent, this is a true no-op: zero `ChatModel` calls, zero output. Any failure (missing
file, malformed JSON, or the sub-agent itself failing) is caught, logged at `ERROR` server-side, and
prints one static `"ERROR: ..."` line to stdout — never a raw stack trace, never an uncaught exception.

### Entry point 2 — automatic, after every successful live review

Every `POST /code-review` request that completes successfully (i.e. `interact(...)` returns without
throwing) publishes a `CodeReviewCompletedEvent` internally; `ExecutiveSummaryEventListener` reacts to it
and prints an executive summary of that same review **to the running server's own standard output** —
**never as part of the HTTP response body returned to the caller**. The actual summarization call and
print happen asynchronously, on a separate thread (one virtual thread per event, production default),
strictly after the HTTP response has already been sent — there is no ordering guarantee relative to when
the client reads the response, and none is needed, since the two are fully decoupled.

**Toggle**: `app.code-review.executive-summary-auto-trigger-enabled`, **default `true`**. Set to `false`
to disable this automatic trigger entirely (zero extra `ChatModel` call, zero extra stdout write, per
review) while leaving entry point 1 (the CLI trigger) completely unaffected — the two are independently
gated.

```yaml
app:
  code-review:
    executive-summary-auto-trigger-enabled: false
```

**A summary failure can never fail, delay, or alter the primary HTTP response.** `CodeReviewController`
and `CodeReviewReactAgent` hold no reference to `ExecutiveSummarySubAgent`/`ExecutiveSummaryEventListener`
anywhere — the only coupling is the generic `ApplicationEventPublisher`/`CodeReviewCompletedEvent` pair.
Every failure mode inside the listener (scheduling failure, sub-agent failure) is caught, logged at
`ERROR`, and never rethrown.

## Token usage logging

Every LLM call logs, at `INFO`, the token counts the provider reported for that call, so a runaway
agent loop is visible in the log before it exhausts a token budget:

| Log line | Emitted by | Token fields |
|---|---|---|
| `ReAct iteration N/M: chatModel call completed ...` | `CodeReviewReactAgent`, each phase-1 call | `promptTokens`, `completionTokens`, `totalTokens` |
| `Phase-2 structured-output call completed ...` | `CodeReviewReactAgent`, each phase-2 attempt, including a retried one | same |
| `Code review completed: ...` | `CodeReviewReactAgent`, once per successful review | `agentModelCalls`, `agentPromptTokens`, `agentCompletionTokens`, `agentTotalTokens` |
| `ReAct loop exhausted ...` / `Phase-2 structured-output parsing failed again ...` | `CodeReviewReactAgent`, when a review fails | the same `agent*` totals, consumed before the failure |
| `retrieveCodeLanguage chatModel call completed ...` / `getCodebaseContext chatModel call completed ...` | `CodeReviewTools`, each tool-internal LLM call | `promptTokens`, `completionTokens`, `totalTokens` |
| `Executive-summary chatModel call completed ...` | `ExecutiveSummarySubAgent`, each summary | same |

The `agent*` totals sum only the calls `CodeReviewReactAgent` makes itself (phase-1 iterations and
phase-2 attempts); tool-internal and executive-summary calls are logged on their own lines and are not
included. Counts are exactly what Azure OpenAI reports for each call; a provider that reports no usage is
logged as `0`.

## Experiments and evaluation

`03-code-review-agent/evaluation/RESULTS.md` accounts for all 8 README/ticket Experiments & Edge Cases
(R13) and the R12 model-comparison subtask — every hermetically-provable mechanism is actually re-run and
cited to a specific test method; every live-model-dependent piece either cites a recorded live run under
`evaluation/runs/` or is explicitly `REQUIRES OPERATOR`, with the exact steps needed once EPAM VPN/DIAL
credentials are available. `evaluation/experiments.json` is the machine-readable companion (validated
structurally, not just parsed, by `EvaluationAssetsTest`, which also checks that every cited live run
records the cited case). `evaluation/model-comparison-schema.json` is the schema of R12's 3-model × 2-run
result set, filled in `evaluation/runs/20260829T193452Z-model-comparison.json`.

Run the hermetic (no live model, no network) portion of the experiments harness directly:

```powershell
.\03-code-review-agent\scripts\run-experiments.ps1 -HermeticOnly
```

This re-runs the module's own Surefire suite and reports, per experiment, whether every cited proving
test method actually passed in that run — never a canned or assumed result. Without `-HermeticOnly`, and
with real `AZURE_OPEN_AI_KEY`/`AZURE_OPEN_AI_ENDPOINT`/`AZURE_OPEN_AI_DEPLOYMENT_NAME` set, the script
additionally attempts to build and start the application for the live-only portions; without credentials
it prints an explicit `SKIPPED - no credentials` line per experiment rather than fabricating a result.

## Reference materials

- [Guide from the Spring team on creating AI agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [Spring AI tool calling reference](https://docs.spring.io/spring-ai/reference/api/tools.html)
