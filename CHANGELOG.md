# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com).

## [Unreleased] — 2026-08-29

Module 3 (`03-code-review-agent`): an LLM-powered code review agent that reasons
over repository code through a bounded tool-assisted loop and returns
evidence-based results.

### Added

- **`POST /code-review` returns a dual output.** Alongside the existing
  human-readable `review` text, responses now carry machine-readable
  `findings[]` — each with file reference, line range, rule, `severity`
  (`blocker`/`high`/`medium`/`low`/`info`), explanation and recommendation — plus
  a `truncated` flag. The previously documented `{"review": "..."}` shape remains
  a valid subset, so existing callers are unaffected.
- **Six agent tools**: reading a file, exploring repository contents, detecting
  the language, retrieving the matching coding convention, summarising codebase
  context, and computing deterministic structural metrics. The agent reaches for
  these itself across multiple reasoning steps rather than answering in one shot.
- **A repository-root security boundary.** Requests are restricted to paths
  inside the configured root; traversal, absolute, UNC and drive-relative paths,
  embedded NUL and alternate-data-stream forms are all rejected, and containment
  is re-verified against the canonical root so a symlink cannot escape it.
- **Runtime evidence enforcement.** Findings that are not grounded in a
  successful file read are dropped and the response says so, so a model ignoring
  its instructions still cannot emit fabricated review comments.
- **An executive-summary sub-agent** that condenses a completed review to the
  server's standard output. It runs automatically after each review (toggle
  `app.code-review.executive-summary-auto-trigger-enabled`, default on) and can
  also be invoked for a saved response via `--executive-summary-input=<path>`.
- **A structured error contract.** Validation, path-security, missing-file,
  iteration-limit, unusable-model-output, provider-failure, malformed-body,
  unsupported-method and unsupported-media-type conditions each map to a
  deliberate HTTP status with a stable body that never exposes filesystem paths,
  stack traces or internals.
- **Token usage logging.** Every model call — each agent loop step, the final
  structured-output call, the two model-backed tools and the executive-summary
  sub-agent — logs its provider-reported prompt, completion and total tokens, and
  a request's closing log line, including on the iteration-limit and
  unusable-output failures, sums the agent's own calls.
- **Operational documentation** in `03-code-review-agent/RUNBOOK.md` and an
  experiments harness under `03-code-review-agent/evaluation/`, recording
  measured outcomes for the six assignment experiments that are provable without
  a live model, live results against a real deployment for five of them, and
  explicitly marking what is still missing — the live halves of the tool-overload
  and ambiguous-description experiments — as requiring an operator.

### Changed

- **A path outside the repository root is now rejected with `400` before any
  model call.** It previously returned `200` with a review whose prose might or
  might not mention the rejection, because the security rejection reached the
  model as an in-band tool message. Access was never actually granted; only the
  observable contract changed. Paths that are merely non-existent still reach the
  agent, since handling a missing file is the agent's job.
- **The agent loop is bounded by `app.code-review.max-iterations`.** Exhausting
  it raises an explicit error rather than returning a partial review as though it
  were complete. The value is validated at startup, so a misconfiguration fails
  fast instead of failing every request.
- **Severity values are accepted case-insensitively** and always serialised in
  lowercase, so a model emitting `HIGH` no longer costs the caller every finding
  and the review text.
- **Untrusted file content is delimited and framed as data** in every prompt that
  carries it, and forged delimiters inside that content are neutralised. This is
  mitigation, not elimination.

### Fixed

- File content truncated at the character limit no longer splits a UTF-16
  surrogate pair, which previously corrupted any non-BMP character sitting at the
  boundary into a replacement byte.
- Structural metrics no longer mis-report line spans and nesting depth for every
  method following an unbalanced brace inside a string literal or comment — a
  regex fragment or JSON sample in a log message was enough to trigger it.
- A successful read of an *empty* file no longer counts as evidence, closing the
  path by which findings could be reported about a file with no content.
- Evidence enforcement and the `truncated` flag now work with Spring AI's real
  tool-calling manager, which JSON-encodes tool results. Before, a failed or
  empty file read still counted as evidence, and `truncated` was set only when
  the model itself reported the truncation.
- Malformed model output is rejected rather than accepted: a bare JSON string
  previously deserialised into a "successful" review with an empty findings list,
  which reads as "no issues found".
- Trailing content after the model's JSON, and non-integral line numbers, are
  rejected rather than silently discarded or truncated.
- A malformed JSON request body returns `400` instead of `500` logged as a server
  fault.
- Duplicate `ChatModel` bean definitions no longer leave the injection point
  ambiguous, and the path resolver enforcing the security boundary is now
  registered as a bean at all.
- Log output cannot be forged from user input or reviewed file content: control
  characters and terminal escape sequences are stripped.
