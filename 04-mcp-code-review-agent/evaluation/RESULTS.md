# Module 4 Code Review Agent — Evaluation Results

**Status: HERMETIC PORTION COMPLETE, LIVE PORTION NOT YET RUN.** Every hermetically-provable mechanism
below is cited to a specific, currently passing test class/method in this module's own Surefire suite. This
module has **not** been executed against a real GitHub PR: no `POST /code-review` request has been sent to
a running instance of this application, no MCP tool call has actually reached `https://api.githubcopilot.com/mcp/`,
and no comment has been posted to any real PR. Every experiment/sub-case that needs a live model and/or a
real PR is marked `REQUIRES_OPERATOR` below and in `evaluation/experiments.json` — never narrated as if it
had already happened. The required 3-model Subtask (Requirement 12) is likewise not yet run.

This file accounts for all 8 rows of `README.md`'s "Experiments & Edge Cases" table (`context/TICKET.md`
Requirement 13), plus the Requirement 12 Subtask. `04-mcp-code-review-agent/README.md` is the original,
unmodified assignment text; this file — together with `04-mcp-code-review-agent/RUNBOOK.md` — documents what
was actually built and actually measured. No score, verdict, or "ran successfully" claim below is made for
anything that did not actually run in this session.

## What was actually run in this session, and what was not

- **Actually run**: `.\mvnw.cmd -pl 04-mcp-code-review-agent test` and `.\mvnw.cmd -pl
  04-mcp-code-review-agent verify` (see this module's `RUNBOOK.md` for the exact numbers from the final
  regression check at the end of Increment 7) — every test citation below was confirmed passing in that run,
  not assumed from reading the source. `context/PROGRESS.md` was re-read in full (all Increment 1–6 entries,
  including every "Follow-up" note) before writing this file, specifically to confirm which planning-time
  risk items were or were not subsequently verified.
- **Not run, because it requires real `AZURE_OPEN_AI_*`/`GITHUB_TOKEN` credentials and a real, operator-owned
  GitHub repository this sandbox does not have**: any `POST /code-review` call through the real HTTP
  endpoint, any real MCP tool call against `https://api.githubcopilot.com/mcp/`, the live half of all 8
  experiments below, and the Requirement 12 Subtask. Each is labeled `REQUIRES_OPERATOR`, with the exact
  steps an operator needs, in `evaluation/experiments.json` and restated below — never narrated as if it had
  happened.

## Experiments & Edge Cases — all 8, individually accounted for

Machine-readable companion: `04-mcp-code-review-agent/evaluation/experiments.json`. Run the hermetic
(no live model, no network) portion directly:

```powershell
.\04-mcp-code-review-agent\scripts\run-experiments.ps1 -HermeticOnly
```

### Experiment #1 — Tool overload

**Trigger (README)**: compare the full auto-discovered GitHub toolset vs mentally restricting the agent to
a few tools in the system prompt. **What to observe**: tool-selection accuracy, latency, wrong/unnecessary
tool calls.

**Hermetic mechanism: `HERMETICALLY_PROVEN` (the precondition only).**
`AgentConfigTest#shouldMergeLocalAndMcpToolCallbacks_whenMcpToolsAreDiscovered` proves
`AgentConfig#chatOptions` genuinely merges `CodeReviewTools`'s 2 local tool callbacks with every callback
`SyncMcpToolCallbackProvider` discovers (`Stream.concat`, not a hardcoded list) — a mocked provider
returning one MCP tool callback yields exactly 3 tool names on the resulting `ChatOptions`.
`HermeticApplicationContextIT#shouldAttachExactlyTheTwoLocalToolCallbacksAndZeroMcpToolCallbacks_whenTheRealContextIsBooted`
proves the same merge through the real, fully-booted application context. Together these prove the tool
list really does grow as more MCP tools are exposed — the structural precondition this experiment
manipulates. **What is not, and cannot be, hermetically proven**: whether a live model actually selects the
wrong tool, wastes reasoning steps, or slows down as the list grows. That is an emergent property of real
model reasoning; no fake `ChatModel` can distinguish "the model picked a worse tool because there were more
of them" from "the model picked the same tool regardless" — a fake always does exactly what the test tells
it to do (same limitation Module 3's own Experiment #1 documented, `03-code-review-agent/evaluation/RESULTS.md`).

**Live half: `REQUIRES_OPERATOR`.** See `evaluation/experiments.json` id 1 for the exact steps: run once
with the full discovered toolset, once with the system prompt temporarily edited to mention only a handful
of preferred tool names, and compare tool-call sequences/iteration counts from the per-tool-call INFO logs.

### Experiment #2 — Ambiguous request

**Trigger (README)**: omit the PR number or repo (e.g., *"review my PR"*). **What to observe**: does the
agent ask for clarification or hallucinate owner/repo/PR identifiers?

**Hermetic mechanism: `HERMETICALLY_PROVEN` for the zero-signal sub-case only.** This experiment splits
cleanly into two sub-cases by design (`RUNBOOK.md`'s "PR-reference validation boundary" section):

- **Total omission** (no PR-shaped signal anywhere in the text, e.g. literally *"review my PR"* or the
  parameterized case *"Please review my latest changes"*):
  `PrReferenceResolverTest#shouldThrowPrReferenceNotFoundException_whenUserInputHasNoPrShapedSignal` and
  `CodeReviewControllerTest#shouldReturnBadRequestWithPrReferenceNotFoundCode_whenUserInputHasNoPrSignal`
  prove this is rejected with `400`/`PR_REFERENCE_NOT_FOUND` **before any `ChatModel` call is made**. By
  construction, there is zero hallucination risk for this sub-case — the model is never invoked.
- **Weak/partial signal** (e.g. a bare `"#42"` with no repository context — now covered as its own literal
  standalone case, not just embedded in a sentence):
  `PrReferenceResolverTest#shouldNotThrow_whenUserInputCarriesAtLeastOnePrShapedSignal` proves this reaches
  the agent unchanged, exactly as the ticket's own Ambiguities resolution intends (`context/TICKET.md`:
  "accept weak/incomplete signals and let the agent itself reason about and report what's missing"). Whether
  the agent then asks a clarifying question or hallucinates a plausible owner/repo is a live-model judgment
  no hermetic fake can answer.

**Live half: `REQUIRES_OPERATOR`** for the weak-signal sub-case. See `evaluation/experiments.json` id 2.

### Experiment #3 — Invalid / inaccessible PR

**Trigger (README)**: target a non-existent PR, or a private repo with a read-only token (403). **What to
observe**: error handling and graceful degradation vs confident-but-wrong output.

**Hermetic mechanism: `NOT_APPLICABLE` — disclosed honestly, not assumed safe.** No test in this module's
own suite constructs a genuinely failing/erroring MCP tool call. The claimed production behavior — that a
per-request MCP failure (`McpError`/`McpTransportException`) is caught by Spring AI's `SyncMcpToolCallback`,
rethrown as `ToolExecutionException`, and absorbed by `DefaultToolExecutionExceptionProcessor` into a text
tool-result fed back to the model rather than ever reaching `CodeReviewExceptionHandler` — is Spring AI's
own framework behavior, reasoned from documented/decompiled behavior during this module's planning session
and restated in `CodeReviewExceptionHandler`'s own Javadoc. It is **not exercised by any test in this
module**. `context/PLAN.md`'s own Risks section flagged the closely related "whether
`DefaultToolCallingManager` throws or absorbs a genuinely unresolvable tool-name request" as needing a
targeted verification in Increments 4–6; re-reading `context/PROGRESS.md`'s Increment 4–6 entries in full
(including every "Follow-up" note) confirms that verification was never subsequently performed. Treat this
as planning-time framework reasoning, not a hermetically proven guarantee of this module's own code.
Separately and independently proven: a genuine AI-provider-level failure (not a GitHub-access failure) maps
deterministically to `502`/`AI_PROVIDER_FAILURE`
(`CodeReviewExceptionHandlerTest#shouldReturnBadGatewayWithStaticMessage_whenTransientAiExceptionIsThrown`,
`#...whenNonTransientAiExceptionIsThrown`) — a different failure class than this experiment's trigger.

**Live half: `REQUIRES_OPERATOR`.** See `evaluation/experiments.json` id 3.

### Experiment #4 — Large PR

**Trigger (README)**: review a PR with many changed files / huge diffs. **What to observe**: context
overload, partial reviews, request timeouts.

**Hermetic mechanism: `HERMETICALLY_PROVEN` — two structural guards against unbounded work, each with exact
proofs.**

- The ReAct loop exhausts after **exactly** `app.code-review.max-iterations` `chatModel` calls (never one
  more) and throws `AgentIterationLimitExceededException` (`500`) rather than returning an incomplete
  answer: `CodeReviewReactAgentTest#shouldThrowAgentIterationLimitExceededException_afterExactlyMaxIterationsChatModelCalls_whenModelRequestsToolCallsIndefinitely`,
  with `0`/negative/`1` boundary values each individually asserted
  (`#shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsZero`,
  `#...WhenMaxIterationsIsNegative`,
  `#shouldThrowAgentIterationLimitExceededExceptionAfterExactlyOneChatModelCall_whenMaxIterationsIsOneAndModelRequestsATool`),
  proven end-to-end through the real controller + advice
  (`CodeReviewControllerTest#shouldReturnInternalServerErrorWithIterationLimitCode_whenAgentExhaustsMaxIterations`),
  and a misconfigured `max-iterations<=0` fails **fast at Spring Boot startup**
  (`CodeReviewPropertiesTest#shouldFailContextRefresh_whenMaxIterationsPropertyIsZero`), not silently at
  request time.
- If the provider itself fails (e.g. a genuine context-window overflow surfacing as a provider error), that
  maps deterministically to `502`/`AI_PROVIDER_FAILURE`, never a raw `500` with leaked detail
  (`CodeReviewExceptionHandlerTest`, same two tests cited under Experiment #3).

**What is not hermetically provable**: whether `max-iterations=15` is actually sufficient for a real large
PR — `RUNBOOK.md` and `context/PLAN.md`'s own Risks section already document this as an unmeasured starting
estimate, not a measured value — and whether analysis quality visibly degrades on a large diff.

**Live half: `REQUIRES_OPERATOR`.** See `evaluation/experiments.json` id 4.

### Experiment #5 — MCP unavailability

**Trigger (README)**: use a wrong endpoint, an expired token, or block network access. **What to observe**:
startup and runtime resilience; quality of error messages.

**Hermetic mechanism: `HERMETICALLY_PROVEN` for the "cleanly disabled" direction only — the literal trigger
was not attempted.** `HermeticApplicationContextIT` (all 4 test methods) proves that with
`spring.ai.mcp.client.enabled=false`, the full real application context boots cleanly on a random port and
serves a real HTTP request successfully, attaching exactly the 2 local tool callbacks and zero MCP tool
callbacks (`AgentConfigTest#shouldAttachExactlyTheTwoLocalToolCallbacks_whenNoMcpClientsAreConfigured`,
`McpToolDiscoveryLoggerTest#shouldLogZeroToolsWithAnEmptyNameList_whenNoMcpClientsAreConfigured` reconfirm
the same zero-tool shape independently). This is a controlled, intentional **absence** of MCP,
decompilation-confirmed (`context/PROGRESS.md`'s Increment 6 entry) to disable both Spring AI MCP
auto-configurations and the streamable-HTTP transport auto-configuration entirely — **it is not the same as
the experiment's literal trigger**, which is a wrong endpoint/expired token/blocked network while MCP is
still enabled and genuinely attempting a handshake. The "a bad endpoint fails context refresh before any
request is accepted" claim (`RUNBOOK.md`) is reasoned from decompiled Spring AI auto-configuration
conditionals during planning (`context/PLAN.md`'s Architecture Notes), not from actually booting this
application against a broken endpoint.

**Live half: `REQUIRES_OPERATOR`.** See `evaluation/experiments.json` id 5 for the three concrete variants
(bad endpoint, expired token, blocked network).

### Experiment #6 — Line anchoring

**Trigger (README)**: inspect where posted comments land in the "Files changed" tab. **What to observe**:
comments on the correct line vs out-of-diff / wrong-line anchoring.

**Hermetic mechanism: `NOT_APPLICABLE`.** This module implements no local line-anchoring logic of its own.
Anchoring is entirely delegated to whatever file/line arguments the model itself passes to the GitHub MCP
server's own comment-posting tool(s), and to GitHub's own rendering of those arguments — both outside this
module's code. Nothing here computes, validates, or corrects a line number, so no hermetic test could
meaningfully prove or disprove anchoring correctness.

**Live half: `REQUIRES_OPERATOR`.** See `evaluation/experiments.json` id 6.

### Experiment #7 — Hallucinated tool

**Trigger (README)**: prompt the agent to use a GitHub tool that doesn't exist. **What to observe**: how it
recovers from invalid/failed tool calls.

**Hermetic mechanism: `NOT_APPLICABLE` — genuinely unverified, disclosed honestly.** No test in this module
constructs a model response requesting a tool name absent from the merged local+MCP tool-callback list.
`context/PLAN.md`'s own Risks section explicitly flagged this exact question ("whether
`DefaultToolCallingManager` throws or absorbs a genuinely unresolvable tool-name request") as needing
targeted verification in Increments 4–6; re-reading `context/PROGRESS.md`'s Increment 4–6 entries in full
confirms that verification was never subsequently performed. This experiment's actual recovery behavior is
therefore genuinely unverified in this module, not merely unexercised by a convenience test.

**Live half: `REQUIRES_OPERATOR`.** See `evaluation/experiments.json` id 7.

### Experiment #8 — Local vs MCP comparison

**Trigger (README)**: compare this module's MCP tools with Module 3's local tools on a similar review.
**What to observe**: trade-offs — control, tool discovery, reliability, latency, debuggability.

**This experiment does not require a live run** — it is a design-level comparison of two already-built
systems. See "Local vs MCP tool comparison against Module 3" below for the full written comparison.

## Local vs MCP tool comparison against Module 3

Grounded directly in both modules' actual shipped code, not a general essay about MCP:

| Dimension | `03-code-review-agent` (local tools) | `04-mcp-code-review-agent` (MCP tools) |
|---|---|---|
| **Tool inventory** | 6 fixed, hardcoded `@Tool` methods on one `CodeReviewTools` class (`readFile`, `exploreRepository`, `retrieveCodeLanguage`, `retrieveCodeConvention`, `getCodebaseContext`, `analyzeCodeMetrics`) — the full set is known at compile time and never changes between runs. | 2 fixed local `@Tool` methods (`retrieveCodeLanguage`, `retrieveCodeConvention`) plus every tool `SyncMcpToolCallbackProvider` **dynamically discovers** from GitHub's remote MCP server at startup — the exact set is not known until runtime and can change if GitHub's own MCP server adds/removes tools, with no code change on this module's side (`AgentConfig#chatOptions`'s `Stream.concat` merge; `McpToolDiscoveryLogger` logs whatever was actually discovered). |
| **Data source / freshness** | The local filesystem, bounded by a configured `repository-root` — whatever is checked out on disk at request time; never "live" GitHub state, and can go stale relative to the actual PR branch. | Live GitHub state at the moment each tool is called (PR metadata, diffs, file content as they exist on GitHub right now) — always current, but only for what the GitHub MCP server chooses to expose and only for what the configured `GITHUB_TOKEN` can read. |
| **Network / cost** | Zero network calls for the tools themselves (only the two LLM-backed sub-calls, `retrieveCodeLanguage`/`getCodebaseContext`, touch the network, and only DIAL). Fast, free of external rate limits, fully available offline. | Every GitHub-facing tool call is a real HTTP round trip to `https://api.githubcopilot.com/mcp/`, subject to GitHub's own latency, availability, and rate limits — an entire class of failure mode (`request-timeout: 60s`, MCP unavailability, Experiment #5) that Module 3 structurally cannot experience for its own tools. |
| **Content scope** | Whatever the local filesystem actually contains — can review any file in the configured root, including ones with no relationship to a "pull request" at all (there is no PR concept in Module 3). | Bound to a specific PR's changed files/diffs only, by design (Requirement 6: "analyze only files actually modified in the PR") — narrower in scope but exactly matched to the actual review task. |
| **Reliability / determinism** | A local file either exists and is readable, or it does not — a small, well-understood failure surface (`FileNotFoundInRepositoryException`, a handful of sentinel messages), all hermetically tested against real fixture files. | A much larger failure surface owned by a third party: auth failures, rate limits, transient network errors, pagination/truncation on GitHub's own side — most of it reasoned about at the framework level in this module (see Experiments #3, #5, #7 above) rather than hermetically exercised, because it cannot be faithfully simulated without either a live GitHub API or a hand-rolled fake MCP server (out of this ticket's scope, see `context/TICKET.md`'s "Out of Scope"). |
| **Discoverability / debuggability** | Every tool's behavior is this module's own code — a failure can always be root-caused by reading `CodeReviewTools.java` directly. `McpToolDiscoveryLoggerTest`/`AgentConfigTest` this module's own tools are still fully debuggable the same way. | The GitHub-facing tools' actual behavior (argument shapes, error text, pagination behavior) lives in GitHub's own MCP server implementation, not in this repository — debugging a GitHub-side surprise means reading GitHub's MCP server docs/source or its own error responses, not this module's code. `McpToolDiscoveryLogger`'s startup INFO line is this module's only structural visibility into *what* was discovered; it has no equivalent for module 3, which needs none (its tool list is fixed at compile time). |
| **Extensibility** | Adding a tool means writing and testing new Java code in this repository. | Adding a tool (from GitHub's side) requires zero code change here — the dynamic-discovery mechanism picks it up automatically on the next startup, proven by `AgentConfigTest#shouldMergeLocalAndMcpToolCallbacks_whenMcpToolsAreDiscovered`'s use of an arbitrary mocked tool name. |

**Net takeaway**: Module 3's local tools trade flexibility for speed, cost, and a small, fully
self-contained failure surface — every failure mode is this module's own code and is hermetically testable.
Module 4's MCP tools trade that same speed/cost/self-containment for live GitHub state and zero-code-change
extensibility — at the price of a network- and rate-limit-bound dependency on a third party whose own
internal behavior (argument shapes, error semantics, tool-name resolution) this module can only reason
about, not hermetically prove, from outside.

## Requirement 12 — 3-model Subtask

**Status: `REQUIRES_OPERATOR` — not yet run.** The ticket requires running `gpt-4o`,
`gpt-4.1-nano-2025-04-14`, and `gpt-5-mini-2025-08-07` once each on one real, live, end-to-end PR-review
task (read the diff, post inline review comments), scoring pass/fail on genuine task completion, and
checking posted comments against the real diff. This posts real, externally-visible comments to a real
GitHub PR — an irreversible side effect that is a user-executed step, per `context/TICKET.md`'s explicit
scope boundary ("Increments produce the code and a runnable evaluation script only; the user triggers and
confirms the live run themselves"). No increment has run this Subtask. To run it:

1. Set real `AZURE_OPEN_AI_*` and `GITHUB_TOKEN` credentials (see `RUNBOOK.md`'s Prerequisites/Configuration).
2. For each of the three deployment names in turn (`AZURE_OPEN_AI_DEPLOYMENT_NAME`), start the application
   (`.\mvnw.cmd -pl 04-mcp-code-review-agent spring-boot:run`) and `POST /code-review` once against the same
   real PR in a repository the operator controls.
3. Record wall-clock time, whether the task completed or looped/timed out/exhausted `max-iterations`, the
   concrete comments actually posted, and whether each comment is real and on-target against the actual diff
   or generic filler.
4. Score pass/fail per model per the ticket's own rule, and record the result here.
