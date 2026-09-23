# Module 3 Code Review Agent — Evaluation Results

**Status: PARTIALLY COMPLETE — every hermetically-provable mechanism actually re-run and confirmed passing in this session; R12's model-comparison subtask has now actually been run live against the DIAL API (see its own section below) and is `COMPLETED`, not `REQUIRES OPERATOR`; on 2026-09-23 the live halves of Experiments #3/#4/#6/#7/#8 were run through the real `POST /code-review` endpoint and are `COMPLETED` (#5 was reconfirmed over real HTTP), and that session found and fixed a production defect in the evidence and truncation guards (see "Live session (2026-09-23)" below); the remaining live-model-dependent pieces (Experiments #1/#2's live half and R11's Merge Request) remain explicitly `REQUIRES OPERATOR`, not fabricated.**

This file accounts for all 8 README/`context/TICKET.md` Experiments & Edge Cases (R13), the R12
model-choice-justification subtask, and R11 (GitLab Merge Request). `03-code-review-agent/README.md`
is the original, unmodified ticket text; this file — together with `03-code-review-agent/RUNBOOK.md`
— documents what was actually built and actually measured. No score, verdict, or "ran successfully"
claim below is made for anything that did not actually run in this session; where live DIAL/VPN access
was required and unavailable, the section says so explicitly rather than estimating or narrating a
plausible outcome.

## What was actually run in this session, and what was not

- **Actually run in this session**: `./mvnw -pl 03-code-review-agent test` (Surefire, 310 tests — the
  Increment 7 baseline of 299 plus this increment's 11 `EvaluationAssetsTest` cases, corrected from an
  earlier, stale count of 309 recorded before a retry added the 11th case),
  `./mvnw -pl 03-code-review-agent clean verify -DskipITs=true` (JaCoCo), `./mvnw -pl 03-code-review-agent
  verify` (full Failsafe pipeline, to re-confirm the known `HermeticApplicationContextIT` environment
  limitation still holds and has not silently changed), `./mvnw -DskipTests compile` (full 4-module
  reactor), and `03-code-review-agent/scripts/run-experiments.ps1 -HermeticOnly` (twice — once with
  `-SkipBuild` reusing a fresh Surefire run's own reports, and once via the script's own `mvnw test`
  invocation). See "Final verification" below for the exact numbers. **In a later session with live
  EPAM VPN + DIAL access, the R12 model-comparison subtask was also actually run** — 6 real
  `chat/completions` calls against `gpt-4o`, `gpt-4.1-nano-2025-04-14`, and `gpt-5-mini-2025-08-07` (2
  runs each) — see R12's own section below for the full, measured result. **In a still later session
  (2026-08-30), 12 further, supplementary `chat/completions` calls were made** (4 more per model) using a
  disclosed, token-count-validated prompt reconstruction, reported as a separate, unpooled second series —
  see R12's "Supplementary evidence" subsection and
  `03-code-review-agent/evaluation/r12-extension-2026-08-30.md`. **In a session on 2026-09-23**, with
  DIAL access and the host's embedded-server limitation worked around (see "Known environment
  limitation" below), the application itself was started and **10 real `POST /code-review` requests**
  were sent to it, backed by `gpt-4o`: 7 recorded in
  `evaluation/runs/20260923T114610Z-live-experiments.json` and, after a defect those runs exposed was
  fixed, 3 more in `evaluation/runs/20260923T160521Z-live-evidence-guard-fix.json` — see "Live session
  (2026-09-23)" below.
- **Not run in the original session, because it required EPAM VPN + live DIAL credentials that sandbox
  did not have**: any `POST /code-review` call against a real Azure OpenAI/DIAL deployment through the
  actual REST endpoint (R12 was run as direct DIAL `chat/completions` calls instead, since the embedded
  server could not bind a socket in that sandbox — see R12's section for why), the live halves of
  Experiments #1–#4 and #6–#8, and R11's GitLab Merge Request. The 2026-09-23 session has since run the
  live halves of #3, #4, #6, #7 and #8. **Still outstanding**: the live halves of Experiments #1 and
  #2, and R11's GitLab Merge Request (the operator's own action) — each labeled `REQUIRES OPERATOR`
  below, with the exact command/steps an operator needs — never narrated as if it had happened.

## Experiments & Edge Cases (R13) — all 8, individually accounted for

Machine-readable companion: `03-code-review-agent/evaluation/experiments.json` (walked structurally by
`EvaluationAssetsTest`, not merely parsed). Raw output of the actually-run hermetic harness:
`03-code-review-agent/evaluation/runs/20260829T072308Z-hermetic-experiments.txt` (produced by
`scripts/run-experiments.ps1 -HermeticOnly -SkipBuild` in this session, with `AZURE_OPEN_AI_*`
explicitly cleared from the process environment first, so the "no credentials" path is genuinely
exercised, not merely asserted).

### Experiment #1 — Tool overload

**Trigger (ticket)**: register several extra/redundant or dummy tools alongside the real ones.
**What to observe (ticket)**: wrong tool selection, wasted reasoning steps, slower runs.

**`REQUIRES OPERATOR` — no hermetic mechanism exists or was built for this experiment, and none could
meaningfully exist.** Tool-selection quality under an inflated tool list is an emergent property of a
live model's own reasoning; no fake/hermetic `ChatModel` can distinguish "the model picked a worse tool
because there were more of them" from "the model picked the same tool regardless" — a fake always does
exactly what the test tells it to do. This is consistent with `context/TICKET.md`'s own R13 disposition
("experiments #1 and #2 ... require an operator with DIAL/VPN access"). To run this experiment once
credentials are available, see `evaluation/experiments.json`'s `id: 1` entry for the exact manual steps
(temporarily add 2-4 redundant `@Tool` methods to `AgentConfig#chatOptions`'s callback list, compare a
live run's tool-call sequence and iteration count against a baseline).

### Experiment #2 — Ambiguous tool descriptions

**Trigger (ticket)**: blank out or make vague a tool's `@Tool` description (e.g. `retrieveCodeConvention`).
**What to observe (ticket)**: mis-selection and confusion — tool descriptions act as a hidden prompt.

**Hermetic regression guard: PROVEN, re-run in this session.**
`CodeReviewToolsTest#shouldHaveNonBlankDescriptionLongerThanTwentyCharacters_forEveryToolAnnotatedMethod`
reflects over every `@Tool`-annotated method on the real `CodeReviewTools` and asserts every description
is non-blank, longer than 20 characters, and that no two descriptions are identical. **Measured in this
session**: `[PASS]` — see the artifact above. This proves the shipped tool descriptions are not currently
blank/duplicated/ambiguous by a concrete, checkable bar (Increment 2's own regression guard against this
exact failure mode being reintroduced later); it does not and cannot prove anything about a real model's
behavior once a description is deliberately degraded.

**Live half: `REQUIRES OPERATOR`.** See `evaluation/experiments.json`'s `id: 2` entry: temporarily blank
`retrieveCodeConvention`'s `@Tool` description, run live, compare against baseline, then revert (never
ship a degraded description).

### Experiment #3 — Missing convention

**Trigger (ticket)**: review a file in a language with no loaded convention (e.g. Go, Kotlin).
**What to observe (ticket)**: honest "no convention found" vs hallucinated rules presented as fact.

**Hermetic mechanism: PROVEN, re-run in this session.**
`CodeReviewToolsTest#shouldReturnHonestNoConventionFoundMessageUnmodified_whenLanguageIsGo` calls the
real `CodeReviewTools.retrieveCodeConvention("go")` (real `ConventionService`, only `java`/`python`
conventions loaded) and asserts the tool returns `ConventionService`'s existing, unmodified honest
message ("No coding convention found for language: go...") rather than any fabricated rule text.
**Measured in this session**: `[PASS]`. This is the exact tool-level mechanism the ReAct loop's evidence
path relies on for this case.

**Live half: `COMPLETED` (2026-09-23, `gpt-4o`) — honest, no invented rules.** Case
`exp3-missing-convention` in `evaluation/runs/20260923T114610Z-live-experiments.json`: `userInput` =
`evaluation/fixtures/cache.go`, a 40-line Go file added as a fixture for this run. The agent read the
file, detected `go`, and called `retrieveCodeConvention("go")`; the application log shows
`ConventionService`'s `No convention found for language: go` warning, and the final review says "No
coding convention specific to Go was found for this review." No Go rule was invented or borrowed from
the Java/Python conventions. Two observations beyond the experiment's question: the one finding (the
errors of `json.Marshal` and `os.WriteFile` ignored in `Save`) is real but cites line 20 instead of
38–39, because `readFile` hands the model the content without line numbers; and the fixture's other
issues (unsynchronized map access, expired entries never evicted, the exported `New` without a doc
comment) were not reported.

### Experiment #4 — Evidence enforcement

**Trigger (ticket)**: make `readFile` return empty / point it at a non-existent file.
**What to observe (ticket)**: does the agent still fabricate findings without real evidence?

**Hermetic mechanism: PROVEN, re-run in this session — the module's most load-bearing runtime guard.**
Two effect-verified tests, both against a fake model that claims non-empty findings anyway:
- `CodeReviewReactAgentTest#shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileNeverSucceedsButModelClaimsFindings`
  — `readFile` always returns the Increment-2 error sentinel (simulating "point it at a non-existent
  file").
- `CodeReviewReactAgentTest#shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileReturnsTheRealEmptyFileSentinelButModelClaimsFindings`
  — constructs a *real* `CodeReviewTools` bound to the real `fixtures/repo-root/empty.txt` fixture and
  calls the real, production `readFile("empty.txt")` to obtain the actual empty-file sentinel text
  (simulating "make `readFile` return empty" — the ticket's own exact wording), not a hand-constructed
  literal that merely resembles it.

**Measured in this session**: both `[PASS]`. In both cases, `CodeReviewReactAgent`'s runtime
`EvidenceTracker` override (Architecture Note A4) forces the returned `CodeReviewResponse` to empty
`findings` plus the honest `NO_EVIDENCE_REVIEW_TEXT`, regardless of what the fake model's own final
answer claimed — a structural, code-level guard, not a prompt-only mitigation. **Corrected
evidence-enforcement contract** (Increment 5 retry 1, recorded once already in `context/PROGRESS.md`,
restated here since it is this experiment's exact mechanism): `evidenceGathered` becomes `true` iff at
least one tool response named exactly `readFile` (a) does not start with `FileUtils.READ_ERROR_PREFIX`
**and** (b) does not end with `CodeReviewTools.EMPTY_FILE_MESSAGE_SUFFIX`.

**Correction (2026-09-23): in production this guard never fired until that day.** Both tests above hand
the tool result to the agent as the plain text the tool returned. The real Spring AI
`DefaultToolCallingManager` does not: its `DefaultToolCallResultConverter` JSON-encodes every `String`
tool result that is not already valid JSON, so the agent received `"ERROR: ..."` in quotes, and
`startsWith(READ_ERROR_PREFIX)`/`endsWith(EMPTY_FILE_MESSAGE_SUFFIX)` could never match —
`evidenceGathered` became `true` after any `readFile` call, including one for a missing or empty file.
The live run below exposed it. `CodeReviewReactAgent` now decodes the tool result before inspecting it
(`decodeToolResult`); the payload sent back to the model is untouched. Three tests run real
`CodeReviewTools` through the real `DefaultToolCallingManager`:
`...whenTheRealToolCallingManagerReadsAMissingFile` and `...whenTheRealToolCallingManagerReadsAnEmptyFile`
fail without the fix, and `shouldKeepTheModelsFindings_whenTheRealToolCallingManagerReadsARealFile` is
the control proving the fix does not suppress genuine findings. See "Live session (2026-09-23)" below.

**Live half: `COMPLETED` (2026-09-23, `gpt-4o`) — no fabrication observed; the guard is now armed.**
Before the fix (case `exp4-exp8-empty-file` in `evaluation/runs/20260923T114610Z-live-experiments.json`),
`userInput` = `src/test/resources/fixtures/repo-root/empty.txt` returned `200` with 0 findings and an
honest review ("exists in the repository but is empty (zero bytes)"), but the log line `Code review
completed: ... evidenceGathered=true` shows the guard was disarmed: had the model claimed findings, they
would have been returned. After the fix (`evaluation/runs/20260923T160521Z-live-evidence-guard-fix.json`)
the same request logs `evidenceGathered=false`, and the missing-file case `exp4-missing-file`
(`src/main/java/com/epam/codereviewagent/DoesNotExist.java`) logs `evidenceGathered=false` and answers
that the file "could not be located in the repository". In all three runs `gpt-4o` itself reported 0
findings, so the override never had to replace an answer live; that path is proven by the real-manager
tests above.

### Experiment #5 — Path traversal

**Trigger (ticket)**: submit `../../etc/passwd` or an absolute path outside the repo root.
**What to observe (ticket)**: input validation and the repository-root security boundary.

**Hermetic mechanism: PROVEN, re-run in this session — the one experiment fully closed with no live
half remaining.** `CodeReviewController.processUserQuery` calls
`RepositoryPathResolver#validateSecurityBoundary(userInput)` as its **first statement**, before
`CodeReviewReactAgent#interact` is ever invoked (Increment 6 retry 1). Proven end-to-end through the
real controller + real advice pair (MockMvc), not merely at the resolver's own unit level:
- `CodeReviewControllerTest#shouldReturnBadRequestWithPathSecurityViolationCode_whenUserInputAttemptsPathTraversal`
  — `../../etc/passwd` yields exactly `400`/`PATH_SECURITY_VIOLATION`.
- `CodeReviewControllerTest#shouldRejectBeforeInvokingTheAgent_whenUserInputIsAnAbsoluteWindowsPath` —
  an absolute Windows path yields the same, **and** asserts
  `verify(reviewReactAgent, never()).interact(any())` — i.e. the rejection happens with a proven **zero**
  `CodeReviewReactAgent`/`ChatModel` interactions, not merely a correct status code.
- `RepositoryPathResolverTest#shouldRejectPathTraversal_whenValidateSecurityBoundaryIsCalledWithDotDotEscape`
  and `#shouldRejectAbsolutePath_whenValidateSecurityBoundaryIsCalledWithAWindowsAbsolutePath` — the
  same guarantee at the resolver's own unit level.

**Measured in this session**: all four `[PASS]`. **This directly answers the increment's own most
emphasized instruction**: a traversal or absolute path now returns `400` before any model call, proven
by an effect-verified `never()` assertion, not inferred from the status code alone.

**Live half: not applicable.** No live model call is ever reached for a rejected path (see above) — there
is no separate live-behavior half of this experiment to run. An operator may still `POST /code-review`
with `../../etc/passwd` against a live-credentialed instance purely to visually confirm the same `400`
response over real HTTP, but this reconfirms the hermetic proof rather than adding new evidence.
Reconfirmed on 2026-09-23 (case `exp5-path-traversal` in
`evaluation/runs/20260923T114610Z-live-experiments.json`): `../../etc/passwd` over real HTTP returned
`400`/`PATH_SECURITY_VIOLATION` in 0.06 s, and the only application log line is
`CodeReviewExceptionHandler`'s "Path security violation rejected" warning — no ReAct iteration and no
token line, i.e. zero model calls.

### Experiment #6 — Large file / context overload

**Trigger (ticket)**: feed a very large source file.
**What to observe (ticket)**: truncation, degraded analysis quality, token-limit errors.

**Hermetic mechanism: PROVEN, re-run in this session — three layers, each individually proven.**
- `FileUtilsTest#shouldTruncateContentAndAppendMarker_whenLimitIsSmallerThanFileLength` — `FileUtils.readFile`
  truncates to `app.code-review.max-file-chars` and appends the visible `FileUtils.TRUNCATION_MARKER`.
- `CodeReviewToolsTest#shouldReturnContentWrappedInMarkersWithVisibleTruncationMarker_whenContentExceedsConfiguredMaxFileChars`
  — the truncation marker stays strictly inside `readFile`'s `<<<BEGIN/END_UNTRUSTED_CODE_SNIPPET>>>`
  delimiters, so the model sees it directly, not hidden past a boundary the model might not read.
- `CodeReviewReactAgentTest#shouldSetTruncatedTrue_whenAnyToolResultContainsTruncationMarker_evenIfModelClaimsFalse`
  — the loop's `EvidenceTracker` observes any tool result containing `FileUtils.TRUNCATION_MARKER` and
  force-sets the final response's `truncated` field to `true`, **even if** the model's own structured-output
  JSON claims `truncated: false` — an objectively observed signal is never overridden by the model.

**Measured in this session**: all three `[PASS]`. This is the concrete, always-surfaces-honestly mechanism
behind the truncation half of the ticket's "what to observe" column; "degraded analysis quality" and
"token-limit errors" are qualitative/live-model observations that cannot be hermetically measured.

**Correction (2026-09-23): in production the third layer never fired until that day**, for the same
reason as Experiment #4's guard: the Increment 5 test hands the tool result to the agent unchanged, while
the real `DefaultToolCallingManager` JSON-encodes it, which turns the marker's leading newline into the
two characters `\n`, so `contains(TRUNCATION_MARKER)` never matched. `truncated: true` still reached
clients whenever the model itself reported truncation in its structured output, but not because the
runtime observed it. Fixed by the same `decodeToolResult` change;
`CodeReviewReactAgentTest#shouldSetTruncatedTrue_whenTheRealToolCallingManagerReturnsATruncatedFile_evenIfModelClaimsFalse`
runs real `CodeReviewTools` through the real manager and fails without the fix.

**Live half: `COMPLETED` (2026-09-23, `gpt-4o`) — truncation now surfaced by the runtime; analysis
quality degraded; no token-limit error.** `userInput` =
`src/main/java/com/epam/codereviewagent/service/CodeReviewTools.java` (about 30 000 characters on disk)
with the default `max-file-chars` of 20000. Before the fix (case `exp6-large-file` in
`evaluation/runs/20260923T114610Z-live-experiments.json`) the response had `truncated: true`, but only
because the model said so: the log has `CodeReviewTools`' own `readFile truncated content` warning and
no `Truncated tool result observed` warning from the agent. After the fix
(`evaluation/runs/20260923T160521Z-live-evidence-guard-fix.json`) the agent logs `Truncated tool result
observed for tool 'readFile'`, so the flag no longer depends on the model. Against the ticket's three
columns:
- *Truncation*: surfaced in the `truncated` flag in both runs, but neither review text says the content
  was truncated, although the system prompt's "Handling Truncated Content" section requires it. The
  runtime flag is therefore the only reliable signal.
- *Degraded analysis quality*: the findings are generic ("hardcoded strings", "complex `readFile`") and
  cite wrong lines (`readFile` at 92–120; it starts at line 218), and the pre-fix run's "inconsistent
  naming conventions for constants (e.g., some are uppercase, some camelCase)" is unsupported: every
  constant in the file is `UPPER_SNAKE_CASE`. Wrong line citations also appear on the 40-line Go file
  (Experiment #3), so they cannot be blamed on truncation alone.
- *Token-limit errors*: none; the 20000-character cap kept the largest call at 7369 prompt tokens.

### Experiment #7 — Loop non-termination

**Trigger (ticket)**: craft a request that encourages endless tool calling.
**What to observe (ticket)**: need for (and behavior of) a max-iteration guard in the ReAct loop.

**Hermetic mechanism: PROVEN, re-run in this session — exact call-count proofs, not approximate ones.**
- `CodeReviewReactAgentTest#shouldThrowAgentIterationLimitExceededException_afterExactlyMaxIterationsModelCalls_whenModelRequestsToolCallsIndefinitely`
  — a fake model configured to request a tool call on every single turn is proven to exhaust after
  **exactly** `maxIterations` `chatModel.call(...)` invocations (never `maxIterations + 1`) and throw
  `AgentIterationLimitExceededException` rather than return a partial/fabricated answer.
- Boundary values, each individually asserted (Increment 5 retry 1):
  `shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsZero` (zero
  model calls at `maxIterations=0`),
  `shouldThrowAgentIterationLimitExceededExceptionAfterExactlyOneChatModelCall_whenMaxIterationsIsOneAndModelRequestsATool`
  (exactly one call at `maxIterations=1` when the model still asks for a tool).
- `CodeReviewPropertiesTest#shouldFailContextRefresh_whenMaxIterationsPropertyIsZero` — a misconfigured
  `app.code-review.max-iterations<=0` fails **fast at Spring Boot startup** (`@Min(1)` JSR-303 validation
  on `CodeReviewProperties`), not silently at request time on every single review.

**Measured in this session**: all four `[PASS]`. This is a direct, deterministic proof of the ticket's
own "need for (and behavior of) a max-iteration guard" — the guard exists, fires at the exact configured
boundary (not off-by-one), and a bad configuration is caught before the application even accepts traffic.

**Live half: `COMPLETED` (2026-09-23, `gpt-4o`) — the guard fired exactly at the limit.** Case
`exp7-loop-limit` in `evaluation/runs/20260923T114610Z-live-experiments.json`, run in a separate
application start with `app.code-review.max-iterations=2` and `userInput` = `src`: the model called
`exploreRepository("src")`, then `exploreRepository("src/main")` and `exploreRepository("src/test")` in
one turn, and after exactly two model calls the agent aborted with `500`/`AGENT_ITERATION_LIMIT_EXCEEDED`
in 8.54 s — no hang, no partial or fabricated review. The ERROR log line records what the aborted attempt
cost: `agentModelCalls=2, agentPromptTokens=4595, agentCompletionTokens=66, agentTotalTokens=4661`. With
the default of 8 iterations no reviewed file came close to the limit (the most was 4, in Experiment #3).

### Experiment #8 — Empty / non-code input

**Trigger (ticket)**: submit an empty file or a README/Markdown file.
**What to observe (ticket)**: graceful handling vs nonsense or hallucinated findings.

**Empty-file sub-case: hermetic mechanism PROVEN, re-run in this session.**
- `CodeReviewToolsTest#shouldReturnExplicitNonErrorEmptyFileMessage_whenFileExistsButIsEmpty` — `readFile`
  on a real zero-byte fixture returns a distinct, non-error, unwrapped sentinel message (never
  `READ_ERROR_PREFIX`-prefixed, never wrapped in the untrusted-content markers, so the model can tell
  "exists but empty" apart from "not permitted"/"does not exist").
- `CodeReviewReactAgentTest#shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileReturnsTheRealEmptyFileSentinelButModelClaimsFindings`
  (shared with Experiment #4's own citation above) — the runtime evidence-enforcement override
  additionally forces empty findings/honest review text even if a fake model claims findings anyway for
  an empty file.

**Measured in this session**: both `[PASS]`. Added 2026-09-23:
`CodeReviewReactAgentTest#shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenTheRealToolCallingManagerReadsAnEmptyFile`,
the same scenario through the real `DefaultToolCallingManager` (see Experiment #4's correction).

**Non-empty-but-non-code-file sub-case (e.g. a real README/prose file): a deliberate, documented hand-off,
not a silent gap.** Per Increment 5's own `context/PROGRESS.md` entry ("Two hand-off decisions this
increment made explicitly", decision 1): this narrower case is left as a **prompt-only mitigation** (the
system prompt's "Evidence Requirements" section instructs the model to say so honestly when a file "is
empty or contains no meaningfully reviewable content"), not a runtime/structural guard. The decision was
made deliberately, not overlooked: a hand-rolled "is this reviewable code" classifier risks **false
positives** (incorrectly suppressing genuine findings on legitimate but unusually-formatted code), which
was judged a worse failure mode than the status quo. This is a real, honestly-disclosed limitation of the
shipped system — a model that ignores the prompt and fabricates findings about a successfully-read,
non-empty README would not be caught by any runtime guard.

**Live half: `COMPLETED` (2026-09-23, `gpt-4o`) — graceful in both sub-cases.** Prose file (case
`exp8-prose-file` in `evaluation/runs/20260923T114610Z-live-experiments.json`, `userInput` =
`README.md`, the module's assignment text): `200`, 0 findings, and a review that summarizes the document
and says "No issues were identified in the content of this file." The prompt-only mitigation held for
this input — one run, so no proof it always will. Empty file (case `exp4-exp8-empty-file`, see
Experiment #4): 0 findings and an honest "the file is empty" review both before and after the fix; the
runtime override described above protects this case in production only since the fix, which is when
the guard became armed (`evidenceGathered=false`).

## Live session (2026-09-23) — the real endpoint against `gpt-4o`

**How it was run.** The application was started with `./mvnw -pl 03-code-review-agent spring-boot:run`
on port 8099 (8080 was taken on the host), with `TEMP`/`TMP` pointed at `C:\Temp\javatmp` (see "Known
environment limitation" below) and the operator's `AZURE_OPEN_AI_*` values for the `gpt-4o` deployment on
`ai-proxy.lab.epam.com`. Each case was one `curl` `POST /code-review`; its request body, HTTP status,
wall-clock duration, response body, every `com.epam.codereviewagent` log line it produced, and the text the
executive-summary sub-agent printed were captured verbatim into the two artifacts below. Neither artifact
contains the API key (checked by searching both for the key's value). Each artifact pins the exact
production code that ran: `HEAD` (`e1e7f22`) plus a SHA-256 over all 35 files under `src/main`, since the
code included uncommitted changes — the IntelliJ inspection fixes (among them four wording edits to the
executive-summary system prompt), token usage logging, and, in the second artifact, the fix below.

- `evaluation/runs/20260923T114610Z-live-experiments.json` — 7 cases, two application starts (defaults;
  then `app.code-review.max-iterations=2` for the loop case).
- `evaluation/runs/20260923T160521Z-live-evidence-guard-fix.json` — 3 cases re-run after the fix below.

| Artifact | Case | Exp. | HTTP | Time | Findings | `truncated` | `evidenceGathered` | Agent calls / tokens |
|---|---|---|---|---|---|---|---|---|
| before fix | `baseline` (`evaluation/fixtures/FileUtils.java`) | — | 200 | 34.06 s | 2 | false | true | 4 / 16850 |
| before fix | `exp3-missing-convention` | #3 | 200 | 16.37 s | 1 | false | true | 5 / 15381 |
| before fix | `exp4-exp8-empty-file` | #4, #8 | 200 | 3.48 s | 0 | false | **true (wrong)** | 3 / 6363 |
| before fix | `exp8-prose-file` | #8 | 200 | 8.15 s | 0 | false | true | 3 / 12236 |
| before fix | `exp6-large-file` | #6 | 200 | 11.95 s | 2 | true (model only) | true | 3 / 17308 |
| before fix | `exp5-path-traversal` | #5 | 400 | 0.06 s | — | — | — | 0 / 0 |
| before fix | `exp7-loop-limit` (`max-iterations=2`) | #7 | 500 | 8.54 s | — | — | — | 2 / 4661 |
| after fix | `exp4-exp8-empty-file` | #4, #8 | 200 | 5.76 s | 0 | false | false | 3 / 6347 |
| after fix | `exp4-missing-file` | #4 | 200 | 4.43 s | 0 | false | false | 3 / 6406 |
| after fix | `exp6-large-file` | #6 | 200 | 17.71 s | 2 | true (observed) | true | 3 / 17425 |

"Agent calls / tokens" are the `agentModelCalls`/`agentTotalTokens` of the request's final log line; the
calls made inside the two LLM-backed tools and by the executive-summary sub-agent are logged separately
and not included (for the baseline: 545 + 1285 tool-call tokens, 1259 summary tokens).

**Baseline (not an experiment).** The first case sent R12's ground-truth file through the full agent.
`gpt-4o` again missed the file's most severe defect, the unvalidated path resolution (see R12): it
reported an encoding-configuration finding and a "file-resolution-flexibility" finding whose
recommendation — support more resolution paths — is the opposite of the fix the file actually needed.

### Defect found and fixed: the evidence and truncation guards never fired in production

- **Symptom**: the first run of the empty-file case logged `evidenceGathered=true`, and the large-file
  case logged `CodeReviewTools`' truncation warning but not the agent's own `Truncated tool result
  observed` warning. The logged `resultLength` was also longer than the text the tool returns (143
  characters for the 141-character empty-file message).
- **Root cause**: Spring AI's `DefaultToolCallingManager` converts each tool result with
  `DefaultToolCallResultConverter`, which JSON-encodes any `String` that is not already valid JSON — so
  the agent received the tool's text quoted and escaped. `EvidenceTracker`'s
  `startsWith(READ_ERROR_PREFIX)`, `endsWith(EMPTY_FILE_MESSAGE_SUFFIX)` and
  `contains(TRUNCATION_MARKER)` compared against the unencoded text and could never match.
- **Why the tests missed it**: every `CodeReviewReactAgentTest` case used `FakeToolCallingManager`, which
  passes the tool's text through unchanged, so the tests encoded an assumption about the framework rather
  than exercising it.
- **Fix**: `CodeReviewReactAgent#decodeToolResult` parses the payload and uses its text when it is a
  JSON string; anything else is inspected as-is. The payload sent back to the model is untouched — the
  model sees exactly what it saw before — so the model behavior in the pre-fix artifact is still
  representative; only the agent's own inspection changed.
- **Tests**: four new `CodeReviewReactAgentTest` cases run real `CodeReviewTools` through the real
  `DefaultToolCallingManager`. The missing-file, empty-file and truncated-file cases failed before the
  fix and pass after it; the real-file case is the control proving genuine findings are kept.
- **Impact**: from Increment 5 until this fix, a fabricated finding about a missing or empty file would
  have been returned to the client, and `truncated` depended on the model reporting it. No recorded
  verdict changes: R12's runs bypass the agent loop, and in every live run above the model itself
  reported 0 findings for the missing and empty files.

### Token usage

Every model call now logs its provider-reported token usage, and each request's final log line sums the
agent's calls. The sums were checked against the per-call lines — baseline: 2292 + 4272 + 5634 + 4652 =
16850 = `agentTotalTokens`. Observed: the fixed overhead of the system prompt and the six tool schemas is
about 2270 prompt tokens on every request's first call (2267–2283 across the nine requests that reached
the model); `getCodebaseContext` generated 721 completion tokens for the 53-line baseline file; and a
failed request still reports what it cost (4661 tokens for Experiment #7's aborted run).

### Other observations (not acted on)

- **Line citations are unreliable**: `readFile` returns content without line numbers, and `gpt-4o`'s
  citations were wrong for a 40-line file (Experiment #3) and a 30 000-character one (Experiment #6).
  Prefixing line numbers would likely help, but changes what the model sees and how much of a file fits
  under `max-file-chars`.
- **Tool parameters reach the model as `arg0`** (e.g. `arguments={"arg0":"README.md"}`): the module is
  compiled without `-parameters`, so Spring AI names method parameters by position. `gpt-4o` still called
  every tool with the right argument in every request that reached it; renaming would change the tool
  schema all recorded runs were made with.
- **An alternative fix** — a `ToolCallResultConverter` that passes strings through unencoded — would also
  shrink what the model receives (the encoded large-file result was 21060 characters versus 20212
  decoded), but it changes the model's input; the decoding fix was chosen as the smaller change.

## R12 — Model-choice-justification subtask

**Status: `COMPLETED` — actually run live against the DIAL API on 2026-08-29, in a later session with
working EPAM VPN + DIAL access. Full artifacts below; nothing in this section is estimated or narrated.
Extended with 12 further, supplementary live runs on 2026-08-30 — see the "Supplementary evidence"
subsection below and `evaluation/r12-extension-2026-08-30.md`; the formal, ticket-compliant headline
result remains the 2-run measurement immediately below, unchanged.**

### Method actually used, and how it differs from the scaffolding's original plan

The scaffolding above (still accurate as a description of what was built earlier) assumed an operator
would drive this subtask through the real `POST /code-review` endpoint. That endpoint could not be used
here either: the embedded Tomcat server in this sandbox still cannot bind a loopback socket
(`java.nio.channels.Selector.open()` fails — the same, already-documented `HermeticApplicationContextIT`
limitation). Instead, this subtask was run as **direct DIAL `chat/completions` calls**
(`POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version=2024-10-21`, header
`Api-Key`), sending the file content and the five required categories directly in the prompt, bypassing
the agent's own tool-calling ReAct loop entirely. This is a deliberate, disclosed deviation: the ticket's
subtask itself only requires "run the same review prompt with each model... twice in a row per model on
the identical file" — it does not require exercising the ReAct/tool-calling machinery, and doing so was
not possible in this sandbox regardless. **The identical request shape was used for all six calls**: a
`system` + `user` message pair only, no `temperature`/`top_p` parameter on any of the three deployments
(so `gpt-5-mini-2025-08-07`'s reasoning-model parameter sensitivity never came into play — it was avoided
by construction rather than special-cased). Full request/response artifacts, one file per run:
`evaluation/runs/20260829T193452Z-<deployment>-run<N>.json` (raw API response plus the exact
`requestParametersUsed` actually sent). Filled result instance:
`evaluation/runs/20260829T193452Z-model-comparison.json` (a completed **copy** of
`model-comparison-run-template.json` — the template itself was left untouched, still all-`PENDING`, per
its own embedded instruction and so `EvaluationAssetsTest`'s unpopulated-template assertion keeps
passing).

### Source file and ground truth

Chosen file: `03-code-review-agent/evaluation/fixtures/FileUtils.java` — the real, pre-Increment-1
version of this repository's own `util/FileUtils.java` (verified byte-identical to `git show
add6f68:03-code-review-agent/src/main/java/com/epam/codereviewagent/util/FileUtils.java`, 53 lines), a
genuine non-trivial file with a real mix of issues, and one whose actual defects and later fix are
independently documented elsewhere in this repository (`context/PROGRESS.md`'s Increment 1 entry),
giving an unusually strong independent check on the ground truth.

**Ground truth was derived independently, line by line, before any run was issued or any model output
was read**, and written to `03-code-review-agent/evaluation/ground-truth-fileutils.md` first. It was only
cross-checked against Increment 1's record and, later, against the models' own citations — two genuine
corrections surfaced during that second cross-check and are recorded in the ground-truth file's own
"Addendum" section rather than silently fixed: the file's initial claim of "zero magic numbers" was
wrong (line 18's `substring(1)` contains one real literal, caught because `gpt-5-mini` cited it), and a
real TOCTOU race (lines 35/47) was added after `gpt-5-mini` correctly identified it. The primary,
most-severe real issue in the file: **none of the four candidate path resolutions (lines 25/27/28/30)
validate that the resolved path stays within any boundary before `Files.readString` is called** — the
exact, historically documented reason this file was rewritten in this repository.

### Per-run results (6 runs, all HTTP 200, none failed/timed out)

| Deployment | Run | Verdict | Why |
|---|---|---|---|
| `gpt-4o` | 1 | **PASS** | Every cited line verified against the file (two minor off-by-one citations, substance still accurate); no false claims. Missed the path-traversal issue, the missing private constructor, and the TOCTOU race. |
| `gpt-4o` | 2 | **FAIL** | Four new, verified-false claims not present in run 1 (see quotes below). |
| `gpt-4.1-nano-2025-04-14` | 1 | **PASS** | No false claims; correctly avoided every trap (no magic-number claim, no resource-leak claim, no deep-nesting overstatement). Weakness was under-claiming (explicitly denied a real issue), not fabricating one. |
| `gpt-4.1-nano-2025-04-14` | 2 | **PASS** | No false claims; caught a genuinely specific, correct, non-generic detail (`.toList()` is Java 16+) no other run mentioned; came close to (but did not fully name) the path-traversal issue. |
| `gpt-5-mini-2025-08-07` | 1 | **PASS** | No false claims; uniquely caught the missing private constructor and the TOCTOU race. |
| `gpt-5-mini-2025-08-07` | 2 | **PASS** | No false claims; the **only run of all six** to explicitly name the file's single most severe real issue — unvalidated path resolution allowing escape outside the intended directory — with accurate line citations. |

**Reversed verdicts**: `gpt-4o` reversed between its two runs (PASS → FAIL) — the ticket treats this as a
reliability flag in itself, independent of which run was "more right." No other deployment reversed at
the PASS/FAIL level, though `gpt-4.1-nano-2025-04-14` reversed at the level of an individual judgment
(run 1: "[readFile] adheres to single-responsibility principles, no significant concerns" / run 2:
"performs multiple responsibilities... consider splitting into smaller, focused methods" — the same
underlying question, opposite conclusions, on the identical file).

### `gpt-4o` run 2's four false claims — quoted verbatim, scored against the real file

1. Claimed the candidate array **"lacks in-line comments or explanations for each path"** (cited lines
   23-31). **False**: lines 24, 26 and 29 each carry an inline comment (`// As passed...`, `// Common
   source roots`, `// If user included src/ already...`).
2. Claimed **"the purpose of the logic to remove the leading slash is unclear and might benefit from a
   comment"** (cited line 17). **False**: line 18, immediately adjacent, already has
   `// treat leading slash as classpath-style relative`, which states exactly that rationale.
3. Claimed **"the hardcoded `\"src/main/java\"` and `\"src/test/java\"` strings are effectively magic
   strings"** and cited **line 30**. The underlying observation (hardcoded path segments) is real, but
   line 30 is `Path.of(trimmed).normalize()` — it contains no string literal at all; the actual literals
   are on lines 27 and 28. Per the ticket's own rule ("a finding citing a line number is false if that
   line does not contain what it claims"), this is scored false as cited.
4. Claimed **"the loop checking each candidate path introduces a deep nesting structure"** (cited lines
   33-39). **False**: the maximum nesting depth in the entire file is 2 (one `for` containing one `if`)
   — not deep by any normal standard, and this exact overstatement was flagged in advance as the
   clearest trap in the ground-truth document.

None of these four claims appear in `gpt-4o` run 1, satisfying the ticket's own fail rule ("a new false
claim not present in the model's other run") directly.

### Generic/templated claims flagged (checked specifically against this file, not accepted on plausibility)

- `gpt-4o` run 2's "candidates array lacks comments" and "no comment on the leading-slash logic" are
  exactly the ticket's own named example of a generic, plausible-sounding "add documentation" claim that
  turns out to be false for this specific file.
- `gpt-5-mini-2025-08-07`'s "`substring(1)`'s literal `1` is a magic number" (both runs, consistently)
  is a real citation, not a fabrication, but a debatable/aggressive classification — common
  static-analysis conventions (e.g. Checkstyle's `MagicNumber` check) exclude small self-evident literals
  like `1` by default. Scored as a flagged generic/templated-style catch, not a false claim, since the
  literal genuinely exists at that line.
- `gpt-4o` run 1's "assigning `null` to `resolved` then reassigning it in a loop is an anti-pattern,
  prefer `Optional<Path>`" is a generic style opinion applied to an idiomatic, extremely common
  search-loop pattern — not a factual error, but not a sharp, file-specific finding either.

### Blind spots — real issues present in the file, aggregated across all six runs

- **The path-traversal/repository-escape issue (the single most severe real defect) was missed by 5 of
  6 runs** — only `gpt-5-mini-2025-08-07` run 2 named it explicitly ("the code does not sanitize or
  constrain resolved paths to a safe base directory, so a user-supplied path could resolve outside
  intended directories"). `gpt-4.1-nano` run 2 and `gpt-5-mini` run 1 touched adjacent territory
  (unvalidated `Path.of(trimmed)`; no null-check on `user.dir`) without naming the escape risk itself.
  Neither `gpt-4o` run named it or anything adjacent to it. **This is the clearest, most consequential
  blind spot observed.**
- **The missing private constructor on the static-only `FileUtils` utility class** was caught only by
  `gpt-5-mini-2025-08-07` run 1 (and, notably, not repeated in that same deployment's run 2 — an
  inconsistency between its own two runs, not a false claim). Missed by both `gpt-4o` runs and both
  `gpt-4.1-nano` runs.
- **The TOCTOU race** (check at line 35, read at line 47) was caught by both `gpt-5-mini` runs and
  missed entirely by both `gpt-4o` and both `gpt-4.1-nano` runs.
- **Naive leading-slash string handling** (only strips a single `/`, no backslash/drive-letter/UNC
  handling) was caught precisely by both `gpt-5-mini` runs; `gpt-4.1-nano` run 1 explicitly denied any
  such issue existed ("no naive string handling issues observed") — a genuine miss, though a negative
  assertion rather than a fabricated positive claim, so it is recorded as a blind spot rather than scored
  as a fail.

### Recommendation: is the default `gpt-4o` justified for code review?

**On this single 53-line file and six runs, no — `gpt-5-mini-2025-08-07` was the strongest and most
reliable reviewer, and the cheap `gpt-4.1-nano-2025-04-14` was more reliable than `gpt-4o`,** reported
plainly rather than defending the ticket's own default:

- `gpt-4o` is the only deployment that produced a **false claim** in either run, and reversed from PASS
  to FAIL between its two identical-input runs — a reliability concern independent of average quality.
- `gpt-4.1-nano-2025-04-14`, the cheapest of the three, produced **zero false claims across both runs**
  and, in run 2, an unprompted and correct Java-version-compatibility observation (`.toList()` requires
  Java 16+) that no other run made. Its main weakness was conservatism/under-claiming, not fabrication.
- `gpt-5-mini-2025-08-07` (a reasoning model, ~1.7-1.9k reasoning tokens spent per run per the raw
  response `usage.reasoning_tokens` field) produced the most thorough, most precisely-cited findings of
  the six, the only two catches of the TOCTOU race, the only catch of the missing private constructor,
  and the only explicit catch of the file's single most severe real issue (unvalidated path resolution).
  It was also markedly slower (~20s vs. ~1-8s per call, from each response's own `latency_checkpoint`).
- If `gpt-4o` remains the default for cost/latency/availability reasons unrelated to this subtask, that
  is a legitimate operational choice, but **this specific measurement does not support it being the
  most accurate reviewer of the three** on this file.

**Explicit limitation, stated rather than glossed over**: six runs on one 53-line file is a very small
sample. It demonstrates a real, reproducible reliability difference on this specific input (`gpt-4o`'s
own two runs disagreeing on the same file is itself evidence, independent of sample size), but it is not
a statistically powered claim about either model's general code-review accuracy across arbitrary files,
languages, or issue types. Treat this as one concrete, fully-verified data point, not a general verdict.

**This 2-run result is the ticket-compliant, formal, headline R12 answer ("twice in a row per model"),
already committed as `2159fc3` (`2d4cc34` before the branch was rebased onto `main` on 2026-09-14). It is
restated above exactly as originally measured — nothing in this section was re-derived or averaged over
the supplementary runs below.**

### Supplementary evidence (2026-08-30) — 12 additional runs, NOT pooled with the 6-run result above

A later session extended R12 with 4 more runs per model (indices 3-6, 12 calls total). This is
**supplementary evidence only** — it does not replace, average into, or restate the 6-run headline result
above. Full detail, methodology, every verbatim quote, and the complete per-run scoring table:
**`03-code-review-agent/evaluation/r12-extension-2026-08-30.md`**. Raw artifacts:
`evaluation/runs/20260830T095918Z-*.json` (12 run files + 1 probe-validation file), each carrying the
full literal request body — unlike the original 6, which saved only request shape (see the
reproducibility lesson below).

**Why not pooled**: the original prompt's literal text was unrecoverable (only request *shape* was ever
saved). A prompt was reconstructed from this document's own description ("numbered file content, the
ticket's exact five categories") and validated by a one-shot token-count probe per model before spending
any further budget. None of the three matched the original series' literal `usage.prompt_tokens`
(`gpt-4o`: 571 vs. 753 expected; `gpt-4.1-nano-2025-04-14`: 571 vs. 753; `gpt-5-mini-2025-08-07`: 570 vs.
752 — no iteration was attempted to chase these numbers, per the coordinator's explicit instruction not to
fit text to a number). The 12 new runs are therefore reported as their own separate, internally
self-consistent series (confirmed self-consistent: all 4 calls per model returned identical
`prompt_tokens` within that model), not pooled with the original 6.

**Headline findings from the supplementary series** (full detail and every quote in the linked document):

- **`gpt-4o`**: 1 of 4 new runs had a verified-false claim (a claimed `StringIndexOutOfBoundsException`
  risk in `substring(1)` that is structurally impossible given the existing `startsWith("/")` guard); the
  other 3 were clean. Stated plainly, per the honesty requirement this task carries: **`gpt-4o`'s new runs
  did not come back entirely clean, but they materially weaken the original finding** — 1 false claim
  across 4 new runs is a much smaller, more equivocal problem than the original run 2's 4 unhedged false
  claims.
- **`gpt-4.1-nano-2025-04-14`**: 1 of 4 new runs had a verified (disclosed-as-debatable) false claim — an
  internally self-contradicted assertion that the code "uses constants instead of hardcoded strings,"
  which the file does not (and which the same response's own later "Suggestions" section contradicts).
  Its original "zero false claims across both runs" no longer holds once 4 more runs are measured.
- **`gpt-5-mini-2025-08-07`**: 0 of 4 new runs had a false claim — 6 of 6 total runs measured across both
  series (2 original + 4 new) are clean. It remains the most reliable of the three by this measure, though
  its own run 3 in this series is a materially weaker *thoroughness* showing than its other runs (misses
  the missing-private-constructor, TOCTOU, and path-containment findings that its runs 4/5/6 all catch).
- **Path-resolution defect naming, re-examined**: the original 6-run series found only `gpt-5-mini` ever
  named the file's most severe real defect (unvalidated path resolution allowing escape outside the
  intended directory). **In the 12 new runs, this is no longer exclusive**: `gpt-4.1-nano-2025-04-14`
  named it clearly in 1 of 4 new runs (hedged in a further run), and `gpt-4o` produced one partial mention
  (the phrase "relative path traversal (`../`)"). `gpt-5-mini-2025-08-07` remains the most *consistent*
  namer (3 of 4 new runs, with the sharpest framing) but is no longer the *only* model ever observed to
  name it.

**Reproducibility lesson**, recorded here and in `context/PROGRESS.md`: the original 6-run series saved
only request-shape flags, not the literal request body, making exact replication impossible and forcing
this token-count-validation workaround. All 12 new run artifacts fix this — each saves its own full,
literal `requestBody`. Any future run of this harness must persist the full request body verbatim, not
just parameter-presence flags.

**Sample-size caveat, restated**: 18 runs total across two non-pooled series on one 53-line file remains a
small, single-file sample — not a statistically powered general claim about either model.

**Raw-artifact trim (2026-09-23), disclosed**: fields that no verdict or number in this file uses were
removed from the `rawResponse` of the 18 run files — `id`, `created`, `object`, `system_fingerprint`,
`service_tier`, `routing`, `prompt_filter_results`, each choice's `logprobs` and `content_filter_results`,
the message's `refusal` and `annotations`, and `usage.completion_tokens`, `usage.total_tokens`,
`usage.prompt_tokens_details` and the non-reasoning `usage.completion_tokens_details` counters. Everything
cited above is unchanged: the review text, `finish_reason`, `latency_checkpoint`, `model`,
`usage.prompt_tokens`, `usage.completion_tokens_details.reasoning_tokens`, and the recorded request data.
The untrimmed files remain in git history (`2159fc3` for the 2026-08-29 runs, `9665330` for the
2026-08-30 runs).

## R11 — GitLab Merge Request

**Status: `REQUIRES OPERATOR` — cannot be performed by any agent in this workflow.**

No agent in this workflow has GitLab credentials, a personal EPAM GitLab repository to push to, or
screenshot capability. Per `context/TICKET.md`'s own Out of Scope section, this is the user's own action
after the code is ready. The operator must, from their own EPAM GitLab account: push this branch, open a
Merge Request, and fill in the required template sections (🗒️ Key Takeaways, 📸 Screenshots, 🧪
Experiments & Edge Cases — this file and `experiments.json` are the source material for that section, 🕵️
Quality check checklist), and ensure the MR is accessible to facilitators.

## Final verification (this session, measured — not restated from an earlier increment's number)

- **`./mvnw -pl 03-code-review-agent test`** (Surefire only): **BUILD SUCCESS** — `Tests run: 310,
  Failures: 0, Errors: 0, Skipped: 2` (the Increment 7 baseline of 299 plus this increment's 11
  `EvaluationAssetsTest` cases — corrected from an earlier, stale count of 309 recorded before a retry
  added the 11th case; the 2 skips are the same honest, capability-gated symlink-escape tests
  recorded since Increment 1 — on a host without symlink-creation privilege, they are skipped with an
  explicit message, never silently weakened to an unconditional pass).
- **`./mvnw -pl 03-code-review-agent clean verify -DskipITs=true`** (JaCoCo): **BUILD SUCCESS**,
  `jacoco:check`: "All coverage checks have been met." **Read directly from a freshly regenerated
  `target/site/jacoco/jacoco.csv` in this session, after adding `EvaluationAssetsTest`** (summed across
  all 32 `src/main` classes — `EvaluationAssetsTest` is a test class and does not change the bundle's
  class count — `LINE_MISSED`/`LINE_COVERED` columns, not the `INSTRUCTION_*` columns): **LINE coverage =
  647/669 = 96.71%**, INSTRUCTION = 2751/2839 = 96.90%, BRANCH = 246/259 = 94.98% — comfortably above the
  configured 0.80 `BUNDLE`/`LINE` gate. This number is deliberately re-measured rather than restated from
  Increment 7's own entry (which reported 648/669 = 96.86% at that point): re-running the full suite in
  this session produced 647/669, a difference of exactly one covered line, most plausibly ordinary
  JVM/JIT-dependent branch-coverage variance in a defensive guard clause rather than any change to
  `src/main` (no production file was modified in this increment — confirmed by
  `git status --porcelain -- 03-code-review-agent/src/main`, see below — and the figure was identical,
  647/669, both before and after `EvaluationAssetsTest` was added, since that new file is entirely under
  `src/test`). Per this increment's own explicit instruction, the number reported here is the one actually
  read from this session's `jacoco.csv`, not the earlier figure.
- **`./mvnw -pl 03-code-review-agent verify`** (full Failsafe pipeline, no `-DskipITs`): **BUILD FAILURE**
  at `failsafe:verify`, re-confirmed in this session — `HermeticApplicationContextIT` fails with the
  identical, already-documented `WebServerException: Unable to start embedded Tomcat server` →
  `java.io.IOException: Unable to establish loopback connection` chain first isolated in Increment 3 (a
  bare `java.nio.channels.Selector.open()` fails identically on this host, independent of Spring/Tomcat;
  the untouched `02-rag/src/test/java/com/epam/docqachatbot/service/RagChatIT.java` fails identically when
  run in this same session). **This is a known, pre-existing sandbox/host networking limitation, not a
  code defect and not introduced by this increment** — the failure occurs strictly *after*
  `finishBeanFactoryInitialization()` already completes successfully for the entire real application
  (every bean, including this increment's own unchanged wiring, constructs correctly), at the very last
  step of context refresh (starting the embedded web server). An operator on an unrestricted machine (a
  normal developer machine, most CI runners, WSL/Linux) should re-run this exact command and expects a
  literal `PASS`, since the test code itself is written and was already proven, by the earlier increments'
  own progressive defect-fixing, to be correct once network I/O is available.
- **`./mvnw -DskipTests compile`** (full 4-module reactor): **BUILD SUCCESS** — `spring-ai`,
  `prompting-llm`, `doc-qa-chatbot` (`02-rag`), and both `code-review-agent` modules (`03-code-review-agent`,
  `04-mcp-code-review-agent`) all compile; this increment's additions do not break the reactor.
- **`03-code-review-agent/scripts/run-experiments.ps1 -HermeticOnly`**: actually executed twice in this
  session (once driving its own `mvnw test` invocation, once with `-SkipBuild` reusing that run's Surefire
  reports with `AZURE_OPEN_AI_*` explicitly cleared from the environment first) — **17/17 cited hermetic
  test citations `[PASS]`, 0 `FAIL`, 0 `UNKNOWN`**. Raw output:
  `03-code-review-agent/evaluation/runs/20260829T072308Z-hermetic-experiments.txt`.
- **`git diff --stat HEAD -- 03-code-review-agent/README.md README.md`**: empty output — both files
  remain byte-identical to their state at the start of this increment.
- **`git status --porcelain -- 03-code-review-agent/src/main`**: empty output — this increment made no
  production-code changes, exactly as the plan's own scope boundary requires ("documentation/tooling/
  verification only"); every new/changed path is under `03-code-review-agent/RUNBOOK.md`,
  `03-code-review-agent/evaluation/`, `03-code-review-agent/scripts/`, and
  `03-code-review-agent/src/test/java/.../support/EvaluationAssetsTest.java`.

### Re-verification (2026-09-23, after token usage logging and the evidence-guard fix)

Run on the operator's Windows host with `TEMP`/`TMP` set to `C:\Temp\javatmp`:

- **`./mvnw -pl 03-code-review-agent test`**: **BUILD SUCCESS** — `Tests run: 331, Failures: 0, Errors: 0,
  Skipped: 2` across 21 test classes (310 plus 21 new: 7 `TokenUsageTest`, 8 `CodeReviewReactAgentTest` —
  4 token-logging and 4 real-manager cases — 3 `CodeReviewToolsTest`, 2 `ExecutiveSummarySubAgentTest`,
  1 `EvaluationAssetsTest`). The 2 skips are the same symlink capability gates ("Symbolic-link creation is
  unavailable on this host"). Screenshot: `docs/screenshots/03-tests-passing.png`.
- **`./mvnw -pl 03-code-review-agent clean verify`** (Surefire, Failsafe and JaCoCo, no `-DskipITs`):
  **BUILD SUCCESS**. `HermeticApplicationContextIT`: `Tests run: 2, Failures: 0` — it passes on this host
  with the `TEMP` workaround (see below). `jacoco:check`: "All coverage checks have been met." Summed from
  the fresh `target/site/jacoco/jacoco.csv` over all 34 `src/main` classes (32 plus `TokenUsage` and
  `CodeReviewReactAgent`'s nested `TokenUsageTracker`): **LINE = 703/725 = 96.97%**, INSTRUCTION =
  3045/3131 = 97.25%, BRANCH = 255/269 = 94.80%.
- **`03-code-review-agent/scripts/run-experiments.ps1 -HermeticOnly -SkipBuild`** (with `AZURE_OPEN_AI_*`
  cleared): **22/22 cited hermetic test citations `[PASS]`, 0 `FAIL`, 0 `UNKNOWN`** — the original 17
  plus the five citations of the new real-manager tests (three under Experiment #4, one each under #6
  and #8), which were added to the harness's citation table. The longer output (4613 bytes, up from
  3812) exposed a latent deadlock in `EvaluationAssetsTest`'s harness test: it waited for the script
  before reading the script's output pipe, so once the output outgrew the 4 KB Windows pipe buffer the
  script blocked and the test timed out at 60 s. The test now sends the output to a file and passes in
  about 1 s.

## Known environment limitation, restated for the operator (per this increment's own explicit instruction)

`HermeticApplicationContextIT`'s `@SpringBootTest(webEnvironment = RANDOM_PORT)` tests cannot run in this
implementation sandbox because `java.nio.channels.Selector.open()` itself fails (confirmed with a
Spring-free, standalone reproduction in Increment 3, and re-confirmed identically for this session by the
untouched `02-rag/RagChatIT` failing the same way). This affects only `HermeticApplicationContextIT` — no
`*Test.java` (Surefire) is affected, and the module's actual HTTP-facing behavior (`CodeReviewController`
+ `CodeReviewExceptionHandler`) is separately, fully proven end-to-end via standalone `MockMvc`
(`CodeReviewControllerTest`), which needs no embedded servlet container and is therefore unaffected by
this limitation. The operator must close this gap by re-running
`./mvnw -pl 03-code-review-agent verify` (no `-DskipITs`) on a machine where loopback TCP sockets are
available.

**Update (2026-09-23): closed on the operator's Windows host.** The workaround already documented in
`RUNBOOK.md`'s "Windows host note" — pointing `TEMP`/`TMP` at a short path such as `C:\Temp\javatmp`
before starting Maven — makes `Selector.open()` work there. With it, `./mvnw -pl 03-code-review-agent
clean verify` passes including `HermeticApplicationContextIT` (see "Re-verification" above), and
`spring-boot:run` serves real HTTP, which is how the live session above was run. Without it, the same
failure still reproduces on that host.
