# Module 2 live evaluation

The current recorded evaluation uses `gpt-5-mini-2025-08-07`, `text-embedding-3-small-1`,
`topK=5`, threshold `0.3`, and MMR/query transformations off by default.
The policy corpus has 41 chunks. Semantic verdicts come from the separate objective review,
never the technical runner's status.

## Evidence index

| Artifact | Scope |
| --- | --- |
| [Raw capture](runs/20260903T193000Z.json) | 35 results; one fixed index and one ingestion |
| [Objective review](runs/20260903T193000Z-objective-review.json) | Per-result answer, traceability, coverage and requirement verdicts |
| [Manifest](runs/20260903T193000Z-chunk-manifest.json) | 41 policy chunks, deterministic IDs and content hashes |
| [Configured-default capture](runs/20260903T190000Z-configured-default.json) | Five questions with no generator environment/CLI override |
| [Separate smoke review](runs/20260903T193000Z-live-smokes-review.json) | Noisy corpus and document lifecycle |
| [Review schema](objective-review-schema.json) | Formal structure for the derived objective review |
| [Manifest tool](tools/ManifestDump.java) | Offline reconstruction from the ingestion pipeline |

Earlier `20260901*` artifacts are historical and are not used for current scores or latency.
Artifact filenames are identifiers: use the raw `generatedAtUtc` for recorded capture time.

## Results by obligation

| README obligation | Results | Requirement PASS | Requirement FAIL |
| --- | ---: | ---: | ---: |
| Core contract: configured default, five rubric questions | 5 | **5** | **0** |
| Model comparison: five questions on each alternative | 10 | 8 | 2 |
| Default experiment cases | 6 | 5 | 1 |
| Deliberately non-default experiment variants | 14 | Not scored | Not scored |

Answer correctness, source coverage and traceability are separate axes. Extra irrelevant sources
do not turn a correct answer into an answer FAIL; source precision is reported independently.

| Core case | Answer | Traceability | Coverage | Latency |
| --- | --- | --- | --- | ---: |
| Simple | PASS | PASS | PASS | 7712 ms |
| Detail | PASS | PASS | PASS | 10566 ms |
| Synthesis | PASS | PASS | PASS | 11787 ms |
| Analysis | PASS | PASS | PASS | 10048 ms |
| FiveBulletSummary | PASS | PASS | PASS | 11864 ms |

## Model choice

All three mandated generators use one fixed policy index, the same embedding deployment, prompt
and retrieval configuration. Each score below describes five captured comparison answers.

| Deployment | Simple | Detail | Synthesis | Analysis | FiveBulletSummary | Score | Median latency |
| --- | --- | --- | --- | --- | --- | ---: | ---: |
| `gpt-4o` | PASS | FAIL | PASS | PASS | PASS | 4/5 | 3014 ms |
| `gpt-4.1-nano-2025-04-14` | PASS | FAIL | PASS | PASS | PASS | 4/5 | 2517 ms |
| `gpt-5-mini-2025-08-07` (default) | PASS | PASS | PASS | PASS | PASS | 5/5 | 10566 ms |

The default covers every tested core question, at higher latency than both alternatives.
On Detail, `gpt-4o` supplies an addition-form bounds check instead of the required subtraction
form. Nano supplies the subtraction check and defensive copying but omits the private native
method/public Java wrapper requirement. These are different answer defects.

The comparison directly establishes identical returned-source lists for all five questions.
It does not capture retrieval-internal scores or prove identical internal execution.
The score measures answer quality only, independently of source precision.

**Configured-default proof:** the companion capture records `usedConfiguredDefault=true`,
an empty `deploymentEnvironmentVariable`, and
`resolvedDeployment=gpt-5-mini-2025-08-07`, read from the application's startup reporter.
Its five answers are also consistent with the rubric. The main comparison explicitly selects
each generator; the companion capture establishes which generator the application uses without
that override.

**Repeatability, and how far it is established.** The five-case comparison above is a single
observation per case, which cannot separate a generator that answers a rubric reliably from one
that happens to answer it. `Detail` is the case where that distinction matters, so it was measured
separately: each generator was asked the same question **20 times** on the same fixed index, under
the current prompt, and scored against all five rubric elements. The raw observations are retained
in [20260903T174200Z-model-selection-study.json](runs/20260903T174200Z-model-selection-study.json),
which also records every incomplete answer element by element.

| Generator | `Detail` rubric-complete | Wilson 95% CI | Exact refusal, no sources | Study latency (min / median / max) |
| --- | ---: | --- | ---: | --- |
| `gpt-5-mini-2025-08-07` (default) | **20 / 20** | [84%, 100%] | 3 / 3 | 4843 / 9121 / 13790 ms |
| `gpt-4o` | 9 / 20 | [26%, 66%] | 3 / 3 | 1099 / 2420 / 3400 ms |
| `gpt-4.1-nano-2025-04-14` | 0 / 20 | [0%, 16%] | 3 / 3 | 1633 / 2044 / 3742 ms |

Against `gpt-4o` the difference is significant at Fisher's exact p ≈ 0.003. The study's latencies
are lower than the main run's because its questions were asked without the comparison suite's
surrounding load; the main run's figures are the ones quoted elsewhere on this page.

Two limits on this. The interval for the default, [84%, 100%], is what 20 consecutive successes
support — it is not a claim of perfection. And an earlier, separate 20-repetition sample of `gpt-4o`
under the same configuration gave 17/20 where this one gives 9/20: two samples of the same
generator differ at p ≈ 0.04, which is a reminder that a single n=20 sample locates a rate loosely.
Pooled over both samples `gpt-4o` is 26/40. Nothing in the selection depends on the exact figure:
every sample places `gpt-4o` far below 20/20, and the selection rule turns on whether a generator
fails a core-contract case at all.

## Changes represented by this evidence

- The system prompt emphasizes supported multi-part completeness and distinct, grounded summary
  themes, with preparatory enumeration kept internal.
- Heading-only Markdown sections are skipped, reducing this corpus from 48 to 41 chunks.
- Retrieved passages use an unlabelled separator, with no numbered caption to leak into answers.
- The startup reporter and runner record the resolved generator, and the runner supports a
  no-generator-override mode.
- MMR remains opt-in; `mmrFinalTopK` above `topK` clamps with a warning.

The current capture contains no preparatory working-list preamble or numbered source marker.
Unlinked intermediate-run counts are not used as current evidence for those improvements.

## Per-axis totals

| Scope | Answer PASS / FAIL | Coverage PASS / FAIL | Traceability PASS / FAIL |
| --- | ---: | ---: | ---: |
| Core contract | 5 / 0 | 5 / 0 | 5 / 0 |
| Model comparison | 8 / 2 | 9 / 1 | 10 / 0 |
| Default experiments | 5 / 1 | 5 / 1 | 6 / 0 |
| Experiment variants | 13 / 1 | 13 / 1 | 14 / 0 |
| All recorded results | 31 / 4 | 32 / 3 | 35 / 0 |

These all-result axis totals are not a combined README compliance score.

The four answer FAILs are result 2 (gpt-4o Detail), result 7 (Nano Detail), result 21
(threshold 0.8 false refusal) and result 29 (default compression-off follow-up false refusal).
Coverage fails on 2, 21 and 29. Nano's omission is an answer defect, while the claims it does
make remain supported. Result 23 is a genuinely unsupported question, so its empty-source
exact refusal is PASS.

## Source traceability and precision

All 183 returned references resolve to the 41-chunk manifest with matching document names.
The audit recomputed the classifications and checked all 35 raw/review result identities.

| Classification | Count | Share |
| --- | ---: | ---: |
| Directly supporting | 88 | 48.1% |
| Contextual, not used to support a claim | 44 | 24.0% |
| Irrelevant | 51 | 27.9% |
| Total | 183 | 100% |

Mean `precisionAtK` is 0.731 across non-empty result sets, where the per-result metric is
`(direct + contextual) / returned`. This is different from the directly-supporting share.

Result 18 (`topK=20`) returns 1 direct, 8 contextual and 11 irrelevant chunks.
Result 34 (`topK20Unnarrowed`) returns 5 direct and 15 contextual chunks.
Directly supporting chunk counts are not counts of claims.

## Experiment observations

All measurements in this table come from the current 35-result capture.

| Experiment | Observation |
| --- | --- |
| topK 1 / 4 / 20 | All PASS; 1 / 4 / 20 sources; 8261 / 9922 / 9626 ms. The extra sources at topK 20 add no directly supporting chunk to the narrow Detail answer. |
| Threshold 0.1 / 0.5 / 0.8 | PASS / PASS / FAIL; 5 / 3 / 0 sources; 10983 / 16083 / 5269 ms. Threshold 0.5 has 3/3 direct sources; 0.8 falsely refuses supported content. |
| Supported / Unsupported | Both PASS; 5 / 0 sources; 7151 / 3395 ms. The supported set has 1 direct and 4 irrelevant chunks; the unsupported answer is the exact refusal. |
| Baseline / expansion / rewrite / both | All four summaries PASS; 10596 / 21055 / 15896 / 22575 ms. The added transformations do not improve correctness on this question. |
| Compression off / on, turn 1 | Both PASS; 6145 / 10768 ms. |
| Compression off / on, turn 2 | FAIL / PASS; 0 / 5 sources; 3590 / 10691 ms. The default elliptical follow-up defect remains; opt-in compression resolves this captured case. |
| Reranking baseline / MMR / topK20 unnarrowed | All three summaries PASS; 10904 / 13582 / 16624 ms; 5 / 5 / 20 sources. |
| Ordinary-question MMR | PASS; 5 sources; 7480 ms. |

## Noisy corpus and lifecycle

The separate smoke review records the configured default without a generator override on
a collection containing 41 policy chunks and 33 PDF chunks. Its counts are not added to the
main run's per-axis totals.

- Noisy corpus: **5/5 answer PASS** — Simple, Synthesis, FiveBulletSummary, DistractorTopicProbe
  and Unsupported. The review records source-document separation between the policy and PDF.
  It does not provide a per-chunk precision classification for this collection.
- Lifecycle: **6 PASS** — create v1, replace v2, retrieve v2, delete by document name,
  post-delete refusal and absence of the deleted document. The remaining collection has 74 chunks.

The controller calls ingestion synchronously before returning. The smoke checks stored chunks
and also waits on the chat search path before evaluating a newly ingested document; its recorded
create step needed one search attempt, completed in 7041 ms. This supports the recorded readiness
method, not a general claim about Chroma's internal atomicity or an indexing-delay distribution.

## Automated verification and provenance

At source commit `d7d572fbd3133b4c408547e4fc5b1205f307cdf0`,
`./mvnw.cmd -pl 02-rag clean verify` passed using Corretto 21.0.6 and the RUNBOOK's process-local
short-TEMP/TMP workaround:

- Surefire: 208 tests, 0 failures, 0 errors, 1 skipped.
- Failsafe: 12 tests, 0 failures, 0 errors, 0 skipped.
- JaCoCo: 742/783 lines (94.76%); 80% gate PASS; BUILD SUCCESS.

[Full sanitized Maven log](verification/20260903T090609Z-d7d572f-clean-verify.txt) ·
[verification record](verification/20260903T090609Z-d7d572f-verification.json).
The initial attempt without the workaround failed with Windows/JDK loopback-selector errors.
Automated tests use AI test doubles and a Testcontainers Chroma instance; they are separate from
live DIAL evidence.

Offline manifest reproduction at this commit gives identical chunk IDs, content hashes and
metadata. The committed manifest uses LF and the Windows reproduction CRLF: parsed JSON and bytes
after line-ending normalization match; literal file hashes differ.

**Exact live executable identity at the current commit is NOT_PROVEN.** The raw captures were
produced on an earlier uncommitted tree and carry no source Git SHA or executable digest. Recorded
model selection and manifest compatibility do not independently establish that executable identity.

The derived objective review was audited for schema conformance, source-reference integrity and
aggregate arithmetic without changing per-result semantic verdicts. The audit corrects its
previous future-dated review timestamp and preserves that value in audit metadata. The separate
smoke review likewise distinguishes its metadata audit time from its historical execution.

The raw captures remain unmodified. Windows PowerShell's `ConvertTo-Json` escapes in their strings
are valid JSON; current runner formatting differs, without changing the decoded evidence.

## Whether the functionality checkbox can be checked

**Yes for the documented default core contract and verified behavior**, with the scope stated:
the five default core cases pass with traceability and coverage, automated verification passes,
and the separate smoke review records successful noisy-corpus answers and lifecycle operations.
The checkbox does not assert that every model or experiment passes.

The default compression-off follow-up failure, irrelevant returned sources, high-threshold false
refusal, and the historical executable-identity gap remain disclosed. Screenshot completion and
GitLab link/rendering verification are separate submission steps.
