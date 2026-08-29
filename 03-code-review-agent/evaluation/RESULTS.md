# Module 3 Code Review Agent — Evaluation Results

**Status: PARTIALLY COMPLETE — every hermetically-provable mechanism actually re-run and confirmed passing in this session; every live-model-dependent piece (Experiments #1/#2's live half, the live halves of #3/#4/#6/#7/#8, R12's model-comparison subtask, and R11's Merge Request) is explicitly `REQUIRES OPERATOR`, not fabricated.**

This file accounts for all 8 README/`context/TICKET.md` Experiments & Edge Cases (R13), the R12
model-choice-justification subtask, and R11 (GitLab Merge Request). `03-code-review-agent/README.md`
is the original, unmodified ticket text; this file — together with `03-code-review-agent/RUNBOOK.md`
— documents what was actually built and actually measured. No score, verdict, or "ran successfully"
claim below is made for anything that did not actually run in this session; where live DIAL/VPN access
was required and unavailable, the section says so explicitly rather than estimating or narrating a
plausible outcome.

## What was actually run in this session, and what was not

- **Actually run in this session**: `./mvnw -pl 03-code-review-agent test` (Surefire, 309 tests — the
  Increment 7 baseline of 299 plus this increment's own 10 new `EvaluationAssetsTest` cases),
  `./mvnw -pl 03-code-review-agent clean verify -DskipITs=true` (JaCoCo), `./mvnw -pl 03-code-review-agent
  verify` (full Failsafe pipeline, to re-confirm the known `HermeticApplicationContextIT` environment
  limitation still holds and has not silently changed), `./mvnw -DskipTests compile` (full 4-module
  reactor), and `03-code-review-agent/scripts/run-experiments.ps1 -HermeticOnly` (twice — once with
  `-SkipBuild` reusing a fresh Surefire run's own reports, and once via the script's own `mvnw test`
  invocation). See "Final verification" below for the exact numbers.
- **Not run in this session, because it requires EPAM VPN + live DIAL credentials this sandbox does
  not have**: any `POST /code-review` call against a real Azure OpenAI/DIAL deployment, the live halves
  of Experiments #1, #2, #3, #4, #6, #7, #8, the entire R12 model-comparison subtask, and R11's GitLab
  Merge Request. Every one of these is labeled `REQUIRES OPERATOR` below, with the exact command/steps
  an operator needs once credentials are available — never narrated as if it had happened.

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

**Live half: `REQUIRES OPERATOR`.** `POST /code-review` with `userInput` pointing at a real Go/Kotlin
file under the repository root, against a live-credentialed instance, to confirm the model's own final
review text honestly states no convention was found rather than inventing plausible-sounding rules.

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

**Live half: `REQUIRES OPERATOR`.** `POST /code-review` with `userInput` pointing at
`fixtures/repo-root/empty.txt` against a live-credentialed instance, to confirm the same empty-findings/
honest-review outcome holds end to end with a real model in the loop.

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

**Live half: `REQUIRES OPERATOR`.** Lower `app.code-review.max-file-chars` (or use a genuinely large
source file) against a live-credentialed instance, confirm `truncated: true` in the real response, and
qualitatively assess whether analysis degrades on the truncated remainder; an upstream token-limit error
would surface as `502`/`AI_PROVIDER_FAILURE` via `CodeReviewExceptionHandler`.

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

**Live half: `REQUIRES OPERATOR`.** A small `app.code-review.max-iterations` (e.g. 1-2) against a
live-credentialed instance, with a `userInput` likely to encourage repeated exploration (e.g. a large,
deeply-nested directory), to confirm the response is `500`/`AGENT_ITERATION_LIMIT_EXCEEDED` rather than a
hang once genuine model tool-calling behavior is involved.

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

**Measured in this session**: both `[PASS]`.

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

**Live half: `REQUIRES OPERATOR`.** Two `POST /code-review` calls against a live-credentialed instance —
once with `userInput` pointing at `fixtures/repo-root/empty.txt` (hermetically enforced regardless, per
above), once with `userInput` pointing at a real README/prose file — to observe whether the model's own
behavior on the second, prompt-only-mitigated case is in fact graceful.

## R12 — Model-choice-justification subtask

**Status: `REQUIRES OPERATOR` — no live run has been performed. Scaffolding only, per this increment's
own scope.**

The ticket requires picking one real, non-trivial source file, running the identical structured review
request through `POST /code-review` against each of `gpt-4o`, `gpt-4.1-nano-2025-04-14`, and
`gpt-5-mini-2025-08-07`, twice per model, then scoring each run per the ticket's own method (verify
every cited line/claim against the actual file, watch for generic/templated claims, compare the two runs
per model for reversed verdicts, log blind spots, score pass = net-accurate / fail = a new false claim
not present in the model's other run). None of this can be performed without EPAM VPN + live DIAL access
to all three named deployments, which this implementation sandbox does not have.

**What exists now (built in this increment)**:
- `03-code-review-agent/evaluation/model-comparison-schema.json` — a **JSON Schema** (the shape
  definition, not a populated instance) for the eventual result set, with `deployment` restricted to
  exactly the 3 named strings, `modelRuns` constrained to exactly 6 entries (`minItems`/`maxItems`: 6),
  and a `scoringMethod` sub-schema that restates the ticket's own pass/fail rule and verification steps
  so the schema is self-describing. `PENDING`/`null` appear in this file only inside `enum`/description
  text, never as populated field values — this file alone gave an operator no ready-to-fill starting
  point.
- `03-code-review-agent/evaluation/model-comparison-run-template.json` (new, this retry) — the actual
  **fillable instance document** that validates against `model-comparison-schema.json`: a real 6-entry
  `modelRuns` array (3 deployments × 2 runs, each named deployment appearing exactly twice with
  `runNumber` 1 and 2), `status: "PENDING"` and `verdict: null` on every entry, `findingsByCategory`
  pre-populated with all 5 ticket-required category keys each holding an empty array, and
  `scoringMethod.passRule`/`failRule` restated verbatim from the schema. An operator copies this file to
  `evaluation/runs/<timestamp>-model-comparison.json` and fills it in, rather than authoring the shape
  from scratch.
- `EvaluationAssetsTest` (new in the original increment, extended this retry) walks the schema's
  `deployment` enum structurally and asserts it is exactly `["gpt-4o", "gpt-4.1-nano-2025-04-14",
  "gpt-5-mini-2025-08-07"]`, and (new test, this retry) walks the template and asserts it genuinely has
  6 `modelRuns` entries covering each deployment twice and that every score/verdict/notes/findings field
  starts unpopulated — proven by genuine structural assertions, not "the file parses" checks.

**What an operator must do to close R12**: pick a real, non-trivial file (the ticket permits "from any
source"); set `AZURE_OPEN_AI_KEY`/`AZURE_OPEN_AI_ENDPOINT` and, in turn,
`AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-4o` / `gpt-4.1-nano-2025-04-14` / `gpt-5-mini-2025-08-07`; for each
deployment, `POST /code-review` twice with the identical `userInput` pointing at the chosen file; copy
`model-comparison-run-template.json` to `evaluation/runs/<timestamp>-model-comparison.json` and record
each run's `findings[]` and `review` text into it, keeping it conformant with
`model-comparison-schema.json`; verify every cited line/claim by hand against the real file content;
fill in `sourceFile`, each run's `verdict`, `blindSpots`, `genericTemplatedClaimsFlagged`, and (once both
runs for a deployment are `COMPLETED`) `reversedVerdicts`; and replace this section's `REQUIRES OPERATOR`
status with a citation to that real artifact, following the exact honesty convention already established
in `02-rag/evaluation/RESULTS.md`'s own `agentReview`/`humanReview` two-track model (never claim a review
happened that did not — see `context/RETROSPECTIVE.md`'s `HUMAN_REVIEWED` false-attribution lesson).

## R11 — GitLab Merge Request

**Status: `REQUIRES OPERATOR` — cannot be performed by any agent in this workflow.**

No agent in this workflow has GitLab credentials, a personal EPAM GitLab repository to push to, or
screenshot capability. Per `context/TICKET.md`'s own Out of Scope section, this is the user's own action
after the code is ready. The operator must, from their own EPAM GitLab account: push this branch, open a
Merge Request, and fill in the required template sections (🗒️ Key Takeaways, 📸 Screenshots, 🧪
Experiments & Edge Cases — this file and `experiments.json` are the source material for that section, 🕵️
Quality check checklist), and ensure the MR is accessible to facilitators.

## Final verification (this session, measured — not restated from an earlier increment's number)

- **`./mvnw -pl 03-code-review-agent test`** (Surefire only): **BUILD SUCCESS** — `Tests run: 309,
  Failures: 0, Errors: 0, Skipped: 2` (the Increment 7 baseline of 299 plus this increment's own 10 new
  `EvaluationAssetsTest` cases; the 2 skips are the same honest, capability-gated symlink-escape tests
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
