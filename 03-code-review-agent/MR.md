# Module 3 — LLM-Powered Code Review Agent (ReAct Agentic System)

**Branch:** `feature/practical-task-3`

## What was built

An LLM-powered, tool-using ReAct agent that reviews a single file or directory inside a configured
repository-root security boundary: it explores the repository, reads file content, retrieves pre-loaded
Java/Python coding conventions, gets a lightweight LLM summary of the code, and computes deterministic
structural metrics — through six `@Tool`-annotated methods driven by a manual, `ToolCallingManager`-based
ReAct loop bounded by a `max-iterations` guard — before producing a dual-output review: human-readable
summary plus a severity-tagged findings array. A runtime `EvidenceTracker` discards any findings the model
reports when no `readFile` call returned usable content, replacing them with an honest no-evidence result. A
second, independent executive-summary sub-agent condenses a completed review to standard output, on demand
or automatically after every live review, and every model call logs its token usage. The work shipped across
8 increments (`cf6cd00`…`23bf829`), a same-day addendum that ran the R12 model-comparison subtask live
against DIAL, and, on 2026-09-23, a live run of the experiments that found and fixed a production defect in
the evidence guard (Experiment #4 below).

## 🗒️ Key Takeaways (REQUIRED)

**Every finding of consequence came from reconstructing a claim against the real thing, not from reading the
diff or trusting a test double** — e.g. decompiling `ChatModel.call(String)`'s default method to reproduce a
real null-`AssistantMessage` response a "genuinely unreachable" claim depended on, rather than accepting the
claim as written. Highest-value defects this caught, by blast radius: an empty file counting as gathered
evidence (the assignment's own Experiment #4 trigger, below); a convenience `CodeReviewResponse(String)`
constructor Jackson auto-detected as an implicit delegating creator, so a bare JSON string model output
silently became a "no issues found" review; a system prompt claiming every tool result was
anti-injection-delimited when that was only true for two of three tool paths, never for `readFile`'s own
attacker-influenceable content; and a naive truncation that could split a UTF-16 surrogate pair. Two further
defects (an unregistered Spring bean; a Jackson 2-vs-3 wiring failure) would have been fatal but were each
caught the moment the application was booted, so realized risk was near zero.

**Found later, on 2026-09-23, by the first live run through the real endpoint: the evidence guard behind #1
— the original check and its fix alike — never fired in production, and neither did Experiment #6's
truncation guard.** Spring AI's real `DefaultToolCallingManager` JSON-encodes every `String` tool result, so
the agent received `"ERROR: ..."` in quotes and every check the guards relied on could never match. Every
agent test had used a fake manager that passed the tool's text through unchanged — it proved an assumption
about the framework, not its actual behaviour; three of four new tests against the real manager are
constructed to fail without the fix now applied. Same lesson, one level down: a fake is itself a claim.

**The same "confidently-wrong self-reporting" pattern showed up in this session's own conclusions**: a
`Selector.open()` failure was, for five increments, diagnosed as a fixed sandbox limitation, when it was
actually the Windows TEMP path — fixed on 2026-09-23 by pointing `TEMP`/`TMP` at a short directory
(`RUNBOOK.md`'s "Windows host note"). With that fix, `clean verify` now passes
`HermeticApplicationContextIT`, and the running application served the 10 live requests cited throughout
this MR.

**R12 — is `gpt-4o` justified as the default?** On the formal 2-run-per-model series, `gpt-4o` reversed
verdict between its two identical-input runs (PASS → FAIL, 2 verified-false claims in run 2, none in run 1),
while `gpt-4.1-nano-2025-04-14` and `gpt-5-mini-2025-08-07` were both clean; only `gpt-5-mini` named the
file's single most severe real defect (unvalidated path resolution). A 12-call, same-file extension weakened
but did not erase that signal (full detail in RESULTS.md). `gpt-4o` is not, on this evidence, the more
accurate reviewer of the three; keeping it as the default may still be a defensible latency/availability
decision, just not one this measurement supports on accuracy.

## 📸 Screenshots (REQUIRED)

Rendered as styled HTML panels from the recorded run artifacts cited throughout this MR and RESULTS.md, not
screen captures — no agent in this workflow has access to the GitLab UI or a running browser session.

![A live POST /code-review response](./docs/screenshots/01-successful-review.png)
_Live `/code-review` request/response (`gpt-4o`, case `exp3-missing-convention`), with the server log's
ReAct/tool-call/token lines and the executive summary — `evaluation/runs/20260923T114610Z-live-experiments.json`._

![Path traversal rejected before any model call](./docs/screenshots/02-path-traversal-400.png)
_Real HTTP `400`/`PATH_SECURITY_VIOLATION` in 0.06 s, zero model calls — case `exp5-path-traversal`,
same artifact as above._

![Unit tests passing](./docs/screenshots/03-tests-passing.png)
_Console output of `./mvnw -pl 03-code-review-agent test` (Surefire only — `clean verify`'s integration
test and coverage numbers are in the Quality check section below), 2026-09-23 — 331 tests, 0 failures._

![Model comparison results](./docs/screenshots/04-model-comparison.png)
_Summary of the R12 2-run headline series — `evaluation/runs/20260829T193452Z-model-comparison.json`._

## 🧪 Experiments & Edge Cases (REQUIRED)

Three of the assignment's eight named experiments; the full account of all 8, plus R12, is in
`03-code-review-agent/evaluation/RESULTS.md`.

### Experiment #4 — Evidence enforcement

**Trigger:** make `readFile` return empty, or point it at a non-existent file. **What to observe:** does the
agent still fabricate findings without real evidence?

Two tests prove the rule against a fake model that claims findings anyway (one non-existent-file, one a real
zero-byte fixture through the real `readFile`), both `[PASS]`: if no `readFile` call returned usable content,
any findings the model reports are discarded for an honest no-evidence result. Three further tests run this
through the real `DefaultToolCallingManager` — two fail without the JSON-decoding fix (see Key Takeaways),
and the third controls that genuine findings survive it.

**Live run (2026-09-23, `gpt-4o`):** the real empty file returned `200` with 0 findings and an honest review,
but the log showed `evidenceGathered=true` — the guard had never actually fired in production. After the
fix, the same request logs `evidenceGathered=false`, and a non-existent-file request logs
`evidenceGathered=false` with an honest "could not be located" answer. `gpt-4o` never claimed findings live,
so the override itself was exercised only by the real-manager tests above. Before/after:
`evaluation/runs/20260923T114610Z-live-experiments.json` and `.../20260923T160521Z-live-evidence-guard-fix.json`
— Experiment #6's truncation guard had the identical defect and fix.

### Experiment #5 — Path traversal

**Trigger:** submit `../../etc/passwd` or an absolute path outside the repository root. **What to observe:**
input validation and the repository-root security boundary.

`RepositoryPathResolver#validateSecurityBoundary` runs as the controller's first statement, before the agent
is ever invoked — proven end-to-end (`MockMvc`): a traversal or absolute-path attempt yields
`400`/`PATH_SECURITY_VIOLATION`, with `verify(reviewReactAgent, never()).interact(any())` proving zero
model-call cost. This wasn't the shipped behaviour from the start — an earlier version returned `200` with
an in-band, model-narrated rejection, caught on review and fixed by adding this pre-check. Reconfirmed over
real HTTP on 2026-09-23: `400` in 0.06 s, no model call.

### Experiment #7 — Loop non-termination

**Trigger:** craft a request that encourages endless tool calling. **What to observe:** the need for (and
behaviour of) a max-iteration guard.

A fake model requesting a tool call on every turn is proven to exhaust after exactly `maxIterations` calls —
never one more — and throw rather than return a partial answer; boundaries (0, 1) are asserted individually,
and a misconfigured `max-iterations <= 0` fails Spring Boot startup itself.

**Live run (2026-09-23, `gpt-4o`, `max-iterations=2`, `userInput=src`):** after exactly two model calls the
agent returned `500`/`AGENT_ITERATION_LIMIT_EXCEEDED` in 8.54 s — no hang, no partial answer; the error log
records the aborted attempt's cost (2 calls, 4661 tokens).

## 🕵️ Quality check (REQUIRED)

- [x] I have verified that the functionality works properly
- [x] I have performed self-review of my code

Re-confirmed directly on 2026-09-23: `test` — **331 run, 0 failures**; `clean verify` (integration tests
included) — **BUILD SUCCESS**, coverage **96.97% line, 97.25% instruction, 94.80% branch**; 10 real
`POST /code-review` requests against `gpt-4o` through the running application (`evaluation/runs/`);
`git diff --stat main...HEAD -- README.md 03-code-review-agent/README.md` — empty, unchanged from `main`.

**Known gaps**, detailed in `RESULTS.md`: the live-model halves of Experiments #1 (tool overload) and #2
(ambiguous descriptions) still need an operator with DIAL credentials — no hermetic mechanism could
meaningfully substitute for either. Pushing this branch (now rebased onto `main`, needing
`git push --force-with-lease`) and opening the Merge Request are this workflow's own remaining human steps.
