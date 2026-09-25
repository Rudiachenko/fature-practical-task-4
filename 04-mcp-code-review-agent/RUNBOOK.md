# Module 4 Runbook: MCP-based GitHub PR Code Review Agent

This is the application runbook for Module 4 — build, run, configuration, and API reference for the
implementation. `README.md` is the original, unmodified assignment text (its lines 1–81 are the actual
graded requirements; lines 83–645 are a suggested-design illustration, not a literal implementation
mandate — see `context/TICKET.md`'s "Linked Documentation" note); this file documents what was actually
built to satisfy it. This mirrors the same `README.md`/`RUNBOOK.md` split already established for Modules 2
and 3 (`03-code-review-agent/RUNBOOK.md:1-6`) — `README.md` is never edited by any increment.

An LLM-powered, single-phase ReAct agent reviews a GitHub pull request identified by free text in
`userInput` (a PR URL, an `owner/repo#number` slug, a bare `#<number>`, or similar). It uses a deliberately
mixed tool set: every GitHub interaction — PR metadata, changed files/diffs, and posting inline review
comments plus one overall summary — goes through tools dynamically discovered from GitHub's remote MCP
server (`https://api.githubcopilot.com/mcp/`), while two local, non-MCP tools (`retrieveCodeLanguage`,
`retrieveCodeConvention`) identify a file's programming language and retrieve the matching pre-loaded
coding-convention document. Module 4 extends `03-code-review-agent`'s hand-rolled ReAct-loop architecture
rather than Spring AI's automatic tool-loop; module 3's classes are **ported** into this module's own
package (`com.epam.codereview`, not `com.epam.codereviewagent`), never imported as a cross-module
dependency.

## Prerequisites

- Java 21
- EPAM VPN access to DIAL, for any live run against a real Azure OpenAI/DIAL deployment
- Existing DIAL credentials in process environment variables (same three as Module 3)
- A **GitHub Personal Access Token** with **PR read+write scope** (`repo`, plus `read:org` for
  organization-owned repositories) — required to actually exercise this module against a real PR; without
  it, the remote MCP client cannot authenticate to `https://api.githubcopilot.com/mcp/` at all (see
  "MCP connectivity and the startup-failure mode" below)

## Configuration

Set the following before starting the application:

```powershell
$env:AZURE_OPEN_AI_KEY = '<dial-key>'
$env:AZURE_OPEN_AI_ENDPOINT = 'https://ai-proxy.lab.epam.com'
$env:AZURE_OPEN_AI_DEPLOYMENT_NAME = 'gpt-4o'
$env:GITHUB_TOKEN = '<github-personal-access-token-with-pr-read-write-scope>'
```

`AZURE_OPEN_AI_DEPLOYMENT_NAME` is switchable to either of the two other named deployments with no code
change (`gpt-4.1-nano-2025-04-14`, `gpt-5-mini-2025-08-07` — see the ticket's required 3-model Subtask,
Requirement 12, which this increment does not itself execute; see `evaluation/RESULTS.md`).

### Configuration properties

| Property | Default | Meaning |
|---|---|---|
| `app.code-review.system-prompt` (`CodeReviewProperties`) | `classpath:prompts/pr-review-system-prompt.md` | The ReAct agent's system prompt — see `src/main/resources/prompts/pr-review-system-prompt.md`. |
| `app.code-review.max-iterations` (`CodeReviewProperties`) | `15` | Maximum number of ReAct-loop tool-calling rounds before the agent aborts with `AgentIterationLimitExceededException` rather than return an incomplete answer. Higher than Module 3's `8`: a PR review plausibly needs more round trips (PR metadata, changed files/diffs, per-file language/convention lookups, then one or more comment-posting calls) before a final answer. **Validated with `@Min(1)`** (`@Validated` on `CodeReviewProperties`): a configured value `<= 0` fails Spring Boot **startup itself**, not silently at request time on every review. This is a starting estimate, not yet measured against a real PR review (`context/PLAN.md`'s own Risks section) — recalibrate once the live Subtask (Requirement 12) surfaces real iteration counts. |
| `app.conventions.resources` (`ConventionProperties`) | `[classpath:documents/java-convention.md, classpath:documents/python-convention.md]` | Pre-loaded coding-convention documents; only `java`/`python` are currently loaded — any other language honestly reports "no convention found" (`CodeReviewToolsTest#shouldReturnHonestNoConventionFoundMessageUnmodified_whenLanguageIsGo`). |
| `spring.ai.mcp.client.enabled` | `true` | Master switch for **both** Spring AI MCP auto-configurations (`McpClientAutoConfiguration` and `McpToolCallbackAutoConfiguration`, decompilation-confirmed — see "MCP connectivity" below). Set `false` (this module's `test` profile only) to disable MCP entirely: zero network activity at context refresh. |
| `spring.ai.mcp.client.name` | `github-mcp-client` | Client identifier reported to the MCP server during its handshake. |
| `spring.ai.mcp.client.version` | `1.0.0` | Client version reported to the MCP server during its handshake. |
| `spring.ai.mcp.client.request-timeout` | `60s` | Timeout for MCP requests — set generously for PR-review-sized payloads (diffs, file content). |
| `spring.ai.mcp.client.type` | `SYNC` | Synchronous MCP client, matching this module's own synchronous ReAct loop (`SyncMcpToolCallbackProvider`). |
| `spring.ai.mcp.client.streamable-http.connections.github.url` | `https://api.githubcopilot.com` | Base URL of the remote GitHub MCP server. |
| `spring.ai.mcp.client.streamable-http.connections.github.endpoint` | `/mcp/` | MCP endpoint path suffix (trailing slash required). |
| `GITHUB_TOKEN` (plain environment variable, **not** an `app.*`/`spring.*` property) | none — required | Consumed directly by `GitHubMcpRequestCustomizerConfig`'s `@Value("${GITHUB_TOKEN}")` bean, which injects `Authorization: Bearer <token>` into every outgoing MCP HTTP request. This bean is this module's own **unconditional** `@Configuration` bean (no `@ConditionalOnProperty`) — it is still constructed even when `spring.ai.mcp.client.enabled=false`, so the placeholder must resolve to *some* value in every profile, including hermetic tests (`application-test.yml`'s `hermetic-test-github-token`). |

No hardcoded configuration value exists anywhere in `src/main` for any of the above — every one is bound by
one of the two `@ConfigurationProperties` classes (`CodeReviewProperties`, `ConventionProperties`), Spring
AI's own MCP client properties, or a `@Value` placeholder, with an externalized default in `application.yml`.

### PR-reference validation boundary (`PrReferenceResolver`)

Every incoming `userInput` passes through one deterministic gate before any `ChatModel` call is made,
independently testable and independently proven — but this gate is deliberately **weaker** than Module 3's
`RepositoryPathResolver`: it validates *shape only, never completeness*, and rejects for exactly one reason.

1. Reject blank/`null` input.
2. Otherwise, accept if **any one** of three independent weak signals is present anywhere in the text:
   - a `github.com/{owner}/{repo}/pull/{number}` URL fragment (case-insensitive, scheme/host prefix
     optional, e.g. `https://github.com/octocat/Hello-World/pull/42`);
   - a bare or embedded `#<digits>` token (e.g. `#42`, `repo#123`);
   - an `{owner}/{repo}`-shaped slug (`[\w.-]+/[\w.-]+`, deliberately permissive).
3. Reject (400, `PrReferenceNotFoundException`) only when **none** of the three signals is present anywhere
   in the input.

This class never attempts to extract an owner, repository name, or PR number, and never reports which
signal (if any) matched — it is a pure accept/reject gate, not a parser. **A false-positive accept is
harmless; a false-negative reject is not**, so all three patterns are deliberately permissive (matches
Module 3's `RepositoryPathResolver`: validate shape only, never throw for a reason other than "no signal at
all").

**Why "accept-if-any-weak-signal" rather than "reject-if-incomplete"**: the assignment's own "ambiguous
request" experiment (omit the PR number or repo, e.g. *"review my PR"*) only makes sense if some
ambiguous-but-not-empty inputs are allowed to reach the agent — otherwise there would be nothing left for
the agent itself to reason about or ask a clarifying question over. A bare `#42` with no repository context,
for example, is **accepted** here and left for the agent's own system-prompt-driven reasoning to handle (ask
for the missing repository, or state plainly that it cannot proceed without one); this resolver's job ends
at "is there at least one PR-shaped signal at all," not "is this request complete enough to actually
execute."

**The controller itself enforces this boundary before any model call.** `CodeReviewController` calls
`PrReferenceResolver#validatePrReferencePresent(userInput)` as its first statement, before
`CodeReviewReactAgent#interact` is ever invoked (see the controller's own Javadoc) — a `userInput` with zero
PR-shaped signal returns `400`/`PR_REFERENCE_NOT_FOUND` deterministically, with zero `ChatModel` calls spent
on it. Proven, not merely status-checked:

- `PrReferenceResolverTest` — the accept/reject boundary at the unit level, including a literal standalone
  `"#42"` case, an embedded `#42`/`#123` case, a full URL, a bare `owner/repo` slug, a case-insensitive URL
  match, blank/`null` input, and plain prose with no signal at all.
- `CodeReviewControllerTest#shouldReturnBadRequestWithPrReferenceNotFoundCode_whenUserInputHasNoPrSignal` —
  the same rejection proven end-to-end through the real controller + real advice (standalone `MockMvc`),
  asserting the exact `400`/`PR_REFERENCE_NOT_FOUND` response.

## Build and run

From the repository root:

```powershell
.\mvnw.cmd -pl 04-mcp-code-review-agent test
.\mvnw.cmd -pl 04-mcp-code-review-agent integration-test
.\mvnw.cmd -pl 04-mcp-code-review-agent verify
```

### Canonical start command

```powershell
.\mvnw.cmd -pl 04-mcp-code-review-agent spring-boot:run
```

### Windows host note (`Selector.open()` / `RANDOM_PORT` limitation)

On some Windows hosts, `Selector.open()` (used by embedded Tomcat and by any `@SpringBootTest(webEnvironment
= RANDOM_PORT)` integration test) fails to establish a loopback connection. **This was actually hit and
fixed in this exact implementation sandbox, during Increment 6**: `HermeticApplicationContextIT` failed all
3 cases on the first `verify` attempt with `ApplicationContextException: Failed to start bean
'webServerStartStop'` → `WebServerException: Unable to start embedded Tomcat` → `IOException: Unable to
establish loopback connection` → `SocketException: Invalid argument: connect`, at
`WEPollSelectorImpl`/`Selector.open()` — byte-for-byte the same failure signature `03-code-review-agent`'s
own `RUNBOOK.md` already documents for that module. The documented fix was applied (pointing `TEMP`/`TMP` at
a short, non-default path, which already existed with historical Mockito/Netty temp artifacts from prior
Module 3 sessions, confirming this is a persistent, previously-known environment characteristic, not a new
regression) before re-running `verify`, which then passed cleanly:

```powershell
New-Item -ItemType Directory -Force -Path C:\Temp\javatmp | Out-Null
$env:TEMP = 'C:\Temp\javatmp'
$env:TMP = 'C:\Temp\javatmp'
```

If `spring-boot:run` or a `RANDOM_PORT` test fails immediately with a selector/pipe error on your own
machine, apply this workaround before any of the "Build and run" commands above.

## MCP connectivity and the startup-failure mode

**An unreachable or misconfigured MCP endpoint is, for this module, mostly a *startup* failure, not a
per-request HTTP error.** Two genuinely different failure surfaces exist, and they behave very differently:

- **Per-request MCP tool failure** (e.g. the model calls a GitHub MCP tool against a PR the token cannot
  read, or a tool name that does not resolve): the underlying `McpError`/`McpTransportException` (both
  extend `RuntimeException` directly) is caught inside Spring AI's own `SyncMcpToolCallback`, rethrown as
  `ToolExecutionException`, and — since this application never sets
  `spring.ai.tools.throw-exception-on-error=true` — absorbed by Spring AI's own
  `DefaultToolExecutionExceptionProcessor` into a **text tool-result fed back to the model**, rather than
  ever propagating out of `CodeReviewReactAgent#interact(String)` to reach `CodeReviewExceptionHandler` at
  all. This is why `CodeReviewExceptionHandler`'s 8-row mapping table below has **no dedicated
  "MCP unavailable" row** — there is no exception type such a row could ever catch (see that class's own
  Javadoc for the full, decompilation-traced call chain). The agent is expected to see the tool's own error
  text as a normal tool result and reason/report accordingly (per the system prompt's "Evidence
  Requirements" section), not to crash.
- **Application startup**: a bad MCP endpoint, an invalid/expired `GITHUB_TOKEN`, or a blocked network path
  can fail the `McpSyncClient`'s connection handshake during context refresh, before the application ever
  accepts a single HTTP request — an ops/startup concern, not an HTTP status this application could ever
  return.

**Honesty note on how well-proven this is**: the "per-request failures are absorbed, not thrown" claim is
grounded in Spring AI's own documented/decompiled framework behavior, researched during this module's
planning session and restated in `CodeReviewExceptionHandler`'s own Javadoc — it is **not** exercised by any
test in this module's own suite (no test constructs a genuinely failing MCP tool call or a hallucinated tool
name and observes the absorption happen). `context/PLAN.md`'s own Risks section flagged exactly this
("whether `DefaultToolCallingManager` throws or absorbs a genuinely unresolvable tool-name request") as
"not fully confirmed" during planning and asked for a targeted verification in a later increment; that
targeted verification was not subsequently performed in Increments 4–6 (confirmed by re-reading
`context/PROGRESS.md` in full before writing this section). Treat this section's claim as planning-time
framework reasoning, not a hermetically proven guarantee of this module's own code — see
`evaluation/RESULTS.md`'s Experiments #3, #5, and #7 for the full, honest accounting.

What **is** hermetically proven, directly, is the opposite direction — that disabling MCP cleanly produces
zero network activity and a healthy application, not a hang or a crash:
`HermeticApplicationContextIT` (all 4 cases), `AgentConfigTest#shouldAttachExactlyTheTwoLocalToolCallbacks_whenNoMcpClientsAreConfigured`,
and `McpToolDiscoveryLoggerTest#shouldLogZeroToolsWithAnEmptyNameList_whenNoMcpClientsAreConfigured`.

## Accepted limitation: no runtime evidence-fabrication override

**Unlike Module 3, this module has no runtime, code-level guard against a fabricated finding.** Module 3's
two-phase design lets `EvidenceTracker`/`applyEvidenceOverride` inspect the model's own structured
`findings[]` array after the loop ends and force it to an honest "no evidence gathered" result if no
`readFile` call ever succeeded. Module 4's `interact()` is **single-phase**: the final answer is simply the
last assistant message's own raw text (`AssistantMessage#getText()`), with no structured-output contract to
inspect or override, because the module's real deliverable is the *side effect* of MCP tool calls posting to
GitHub, not a parseable response body (`context/PLAN.md`'s Architecture Notes; `CodeReviewReactAgent`'s own
class-level Javadoc states this explicitly).

**Stated plainly, not implied as parity with Module 3**: the no-fabrication guarantee in this module is
**system-prompt-only**. The system prompt's "Evidence Requirements — No Fabrication" section instructs the
model never to report a finding it cannot support with tool-retrieved evidence from the same conversation,
and `SystemPromptContentTest#shouldForbidFabricatingFindingsWithoutEvidence` proves that instruction is
genuinely present in the rendered prompt text — but nothing in this module's own Java code can detect or
correct a model that ignores it. This is an accepted, disclosed design trade-off from planning, not an
oversight discovered late.

## HTTP API

### `POST /code-review`

```http
POST /code-review
Content-Type: application/json

{
  "userInput": "https://github.com/octocat/Hello-World/pull/42"
}
```

`userInput` (`UserRequest{userInput}`) is required (`@NotBlank`) and must carry at least one PR-shaped
signal — see "PR-reference validation boundary" above. On success, the response is `200` with a **plain-text
`String` body** (`Content-Type: application/json` is declared on `CodeReviewApi.processUserQuery`, but a
declared `String` return type is written by Spring's `StringHttpMessageConverter`, not JSON-quoted — verified
against the resolved Spring Framework 7.0.3 jars during Increment 5, and directly proven end-to-end by
`HermeticApplicationContextIT#shouldReturn200OkWithTheRecordingChatModelsCannedText_whenPostingAValidPrReferenceAgainstTheFullRealContext`).
That returned string is **not** the review itself — per the system prompt's "Final Output Expectations,"
it is a short completion summary; the actual review (inline comments anchored to file+line, plus one
overall summary) is delivered as the side effect of MCP tool calls posted directly to the GitHub PR.

### Error responses

Every mapped failure returns a JSON `ApiError`:

```json
{
  "code": "PR_REFERENCE_NOT_FOUND",
  "message": "The request does not identify a GitHub pull request to review: include a PR URL, an owner/repo reference, or a #<number> token.",
  "violations": []
}
```

`violations` is always a non-null, possibly-empty array of `{ "field", "message" }` objects, populated only
for `VALIDATION_FAILED`. **No response body, for any row below, ever echoes a raw exception's own message
text** — every `message` is static and predefined; full exception detail is logged server-side only, routed
through `SafeLogFormatter` wherever the logged text could itself carry caller-controlled content.

The complete exception → HTTP-status mapping (`CodeReviewExceptionHandler`, `@RestControllerAdvice`):

| # | Exception | HTTP status | `ApiError.code` | Log level | Notes |
|---|---|---|---|---|---|
| 1 | `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` | DEBUG | Blank `userInput` (`@NotBlank`) — a pure client mistake. |
| 2 | `PrReferenceNotFoundException` | 400 | `PR_REFERENCE_NOT_FOUND` | DEBUG | Zero PR-shaped signal in `userInput` — thrown from `CodeReviewController`'s own upfront pre-check, before `CodeReviewReactAgent#interact` is ever called, with zero model calls spent. |
| 3 | `AgentIterationLimitExceededException` | 500 | `AGENT_ITERATION_LIMIT_EXCEEDED` | ERROR | The ReAct loop exhausted `app.code-review.max-iterations` without reaching a natural answer. A server-side operational limit, not a request defect. |
| 4 | `TransientAiException` / `NonTransientAiException` | 502 | `AI_PROVIDER_FAILURE` | ERROR | An upstream Azure OpenAI/DIAL failure (outage, rate limit, timeout, permanent provider error). |
| 5 | `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST` | DEBUG | The request body could not be parsed as JSON at all. |
| 6 | `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` | DEBUG | e.g. `GET /code-review`. |
| 7 | `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` | DEBUG | A `Content-Type` other than `application/json`. |
| 8 | any other `Exception` | 500 | `INTERNAL_ERROR` | ERROR | Catch-all safety net. |

Rows 5–7 exist because `ExceptionHandlerExceptionResolver` (the resolver backing `@ExceptionHandler`
methods) runs at precedence order `0`, strictly before Spring's own `DefaultHandlerExceptionResolver` —
once any `@ExceptionHandler(Exception.class)` method exists on this class, it wins for these three
framework-dispatch exception types too, unless each is given its own, more specific handler. Without them, a
malformed JSON body or a wrong HTTP method/`Content-Type` would silently fall through to row 8 and be
misreported as a 500, logged at `ERROR` with a full stack trace, as though a pure client mistake were a
server fault (same decompilation-confirmed finding `03-code-review-agent`'s own
`CodeReviewExceptionHandler` documents). **No dedicated "MCP unavailable" row exists** — see "MCP
connectivity and the startup-failure mode" above for why. Every row is proven by
`CodeReviewExceptionHandlerTest` (one test per row, plus a violation-sorting test, an `ApiError`
null-violations test, and `shouldNotLogAtErrorLevel` proofs for the two rows easiest to
miscategorize as server faults) and re-proven end-to-end by `CodeReviewControllerTest` (real controller +
real advice, standalone `MockMvc`).

## Token usage logging

Every `ChatModel` call logs, at `INFO`, the token counts the provider reported for that call, so a runaway
agent loop is visible in the log before it exhausts a token budget:

| Log line | Emitted by | Token fields |
|---|---|---|
| `Received PR review request: userInput=...` | `CodeReviewReactAgent`, once per request (`SafeLogFormatter`-redacted) | — |
| `ReAct iteration N/M: chatModel call completed ...` | `CodeReviewReactAgent`, each ReAct-loop iteration | `promptTokens`, `completionTokens`, `totalTokens` |
| `Tool call executed: name=..., arguments=..., batchDurationMs=..., resultLength=...` | `CodeReviewReactAgent`, once per executed tool call (`SafeLogFormatter`-redacted arguments) | — (result length only, not token counts) |
| `ReAct loop completed: iterationsUsed=..., ...` | `CodeReviewReactAgent`, once per successful request | `agentModelCalls`, `agentPromptTokens`, `agentCompletionTokens`, `agentTotalTokens` |
| `ReAct loop exhausted ...` | `CodeReviewReactAgent`, when a request fails on the iteration limit | the same `agent*` totals, consumed before the failure |
| `retrieveCodeLanguage chatModel call completed ...` | `CodeReviewTools`, each local-tool sub-call | `promptTokens`, `completionTokens`, `totalTokens` |
| `Application ready - Discovered N MCP tool(s): [...]` | `McpToolDiscoveryLogger`, once at startup | — (tool count/names, not tokens — satisfies the "log MCP connectivity" requirement) |

The `agent*` totals sum only the calls `CodeReviewReactAgent` makes itself; `CodeReviewTools`' own
sub-call is logged on its own line and is not included in that sum. Counts are exactly what Azure
OpenAI reports for each call.

## Experiments & Edge Cases

`evaluation/RESULTS.md` accounts for all 8 rows of `README.md`'s "Experiments & Edge Cases" table, plus a
short architectural comparison against Module 3 (row 8) that needs no live run. `evaluation/experiments.json`
is the machine-readable companion. Every hermetically-provable mechanism is cited to a specific, currently
passing test class/method; every live-model-dependent piece is marked `REQUIRES_OPERATOR` and has **not**
been executed — this module has not yet been run against a real PR. Summary:

| # | Experiment | Hermetic status | Live status |
|---|---|---|---|
| 1 | Tool overload | Partially proven — the local+MCP tool-callback merge mechanism itself is proven | `REQUIRES_OPERATOR` |
| 2 | Ambiguous request | Proven for the zero-signal sub-case (rejected before any model call) | `REQUIRES_OPERATOR` for the weak-signal sub-case |
| 3 | Invalid/inaccessible PR | Not exercised by this module's own tests (framework-level reasoning only) | `REQUIRES_OPERATOR` |
| 4 | Large PR | Proven — iteration-limit guard and AI-provider-failure mapping | `REQUIRES_OPERATOR` |
| 5 | MCP unavailability | Proven for clean MCP-disabled startup only | `REQUIRES_OPERATOR` for a genuinely bad endpoint |
| 6 | Line anchoring | Not applicable — no local anchoring logic exists | `REQUIRES_OPERATOR` |
| 7 | Hallucinated tool | Not exercised by this module's own tests | `REQUIRES_OPERATOR` |
| 8 | Local vs MCP comparison | Architectural comparison — no live run needed | Not applicable |

Run the hermetic portion of the experiments harness directly:

```powershell
.\04-mcp-code-review-agent\scripts\run-experiments.ps1 -HermeticOnly
```

This re-runs the module's own Surefire suite and reports, per experiment, whether every cited proving test
method actually passed in that run — never a canned or assumed result. Without `-HermeticOnly`, and with
real `AZURE_OPEN_AI_*` **and** `GITHUB_TOKEN` set, the script prints the exact manual steps an operator needs
to exercise the live portion (build+run the app, `POST` a real PR reference) — it does **not** itself `POST`
anything to a real GitHub PR (an irreversible, externally-visible side effect that is a user-executed step,
per `context/TICKET.md`'s explicit scope boundary).

The required 3-model Subtask (Requirement 12: run `gpt-4o`, `gpt-4.1-nano-2025-04-14`, and
`gpt-5-mini-2025-08-07` once each on one real, live, end-to-end PR-review task) is likewise not executed by
any increment — see `evaluation/RESULTS.md`'s own Subtask section.

## Reference materials

Must-read (per `context/TICKET.md`'s Original Description):

- [MCP Specification](https://modelcontextprotocol.io/specification/2025-03-26)
- [MCP Intro / Core Docs](https://modelcontextprotocol.io/)
- [Spring AI MCP Overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [LangChain4j MCP API Docs](https://docs.langchain4j.dev/tutorials/mcp/)
