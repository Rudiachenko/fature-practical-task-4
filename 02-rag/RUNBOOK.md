# Module 2 RAG Runbook

## Prerequisites

- Java 21
- Docker Desktop for Chroma and integration tests
- Existing DIAL credentials supplied through process environment variables
- Chroma 1.0.0 available at `http://localhost:8000`

Start Chroma from the repository root:

```powershell
docker compose up -d chroma
```

Set runtime configuration without committing secrets:

```powershell
$env:AZURE_OPEN_AI_KEY = '<existing DIAL key>'
$env:AZURE_OPEN_AI_ENDPOINT = 'https://ai-proxy.lab.epam.com'
$env:AZURE_OPEN_AI_DEPLOYMENT_NAME = 'gpt-5-mini-2025-08-07'   # optional: this is the configured default
$env:AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME = 'text-embedding-3-small-1'
$env:CHROMA_BASE_URL = 'http://localhost:8000'
$env:CHROMA_COLLECTION = 'doc-qa-collection'
```

The default embedding deployment is already `text-embedding-3-small-1`. Override it only
when intentionally rebuilding a collection: chat and ingestion must use the same embedding
model, and vectors created by different embedding deployments are not comparable.

## Build and run

From the repository root on Windows:

```powershell
.\mvnw.cmd -pl 02-rag test
.\mvnw.cmd -pl 02-rag integration-test
.\mvnw.cmd -pl 02-rag clean verify
```

`integration-test` and `clean verify` require Docker because
`ChromaVectorStoreWiringIT` starts an isolated Chroma container through Testcontainers.
Live DIAL credentials are not required for these tests; their AI models are test doubles.

Start the application with the Windows/JDK selector workaround scoped to the child process:

```powershell
.\02-rag\run-spring-boot.ps1
```

Additional Maven arguments are passed through, for example:

```powershell
.\02-rag\run-spring-boot.ps1 `
  '-Dspring-boot.run.arguments=--server.port=18080'
```

The script temporarily assigns `TEMP` and `TMP` to `C:\jtmp`, starts
`spring-boot:run`, and restores the caller's environment on exit. This avoids the Windows
JDK loopback-selector failure without changing the application's HTTP transport.

## Default RAG configuration

| Setting | Default |
|---|---:|
| generator deployment | `gpt-5-mini-2025-08-07` |
| embedding deployment | `text-embedding-3-small-1` |
| `topK` | 5 |
| similarity threshold | 0.3 |
| chunk size | 800 tokens |
| minimum chunk size characters | 100 |
| chat memory window | 20 messages |
| resources per request | 20 |
| resource size | 5 MiB |
| startup bootstrap | disabled |
| query compression/rewrite/expansion | disabled |
| MMR reranking | disabled |

**The configured default trades latency for the measured five-case result.** On one fixed index,
`gpt-5-mini-2025-08-07` scores 5/5, while `gpt-4o` and `gpt-4.1-nano-2025-04-14` each score 4/5
with a Detail failure. The default's comparison median is 10566 ms versus 3014 ms for `gpt-4o`.
The separate no-override capture records the configured default and five rubric-consistent answers.
Repeatability was measured separately rather than inferred from single answers: on the `Detail`
rubric, asked 20 times per generator on one fixed index, `gpt-5-mini-2025-08-07` was complete
20/20, `gpt-4o` 9/20 and `gpt-4.1-nano-2025-04-14` 0/20. The raw observations are retained in
`evaluation/runs/20260903T174200Z-model-selection-study.json`. Evidence and limitations:
`evaluation/RESULTS.md`.

**Heading-aware MMR is deliberately opt-in.** Its diversity penalty is applied per theme, so it
helps a question that spans the whole document but starves a question that legitimately needs
several subsections of one theme. Enable it per request for whole-document questions; the current
evaluation exercises it that way and it produces the thematically broadest summary in the run.

> *Historical, not current evidence.* Enabling MMR as the default over a broad candidate pool
> (`topK` 20 narrowed to 5) was measured on 2026-09-02 and rejected: the Synthesis case, which needs
> 2.3.1, 2.3.4 and 2.3.5 all under `2.3 INPUT AND OUTPUT`, lost that context and returned a false
> refusal, and the Analysis case lost `2.1.3 Restrict Privileges`. That measurement predates the
> current system prompt and the current default generator and has not been repeated, so it explains
> why the setting is opt-in but must not be cited as a current result. Re-measure before changing it.

**Retrieved passages are separated explicitly, and the separator carries no label.** Spring AI's
default `documentFormatter` joins retrieved document texts with a bare `System.lineSeparator()`, so
several policy sections arrive as one undifferentiated block - and the system prompt's rules speak
about "the relevant passage" and "a different passage", which a model cannot honour across a
boundary it cannot see. `AdvancedRagConfig.formatDelimitedContext` joins them with an unlabelled
rule instead: a caption gives the model a label to cite, and a cited "(Source 2)" references
something the caller never receives, since sources are returned in a separate structured field.
The current run contains no source marker in any of its 35 answers.

> *Historical, not current evidence.* The numbered `[Source N of M]` caption was implemented first
> and measured on 2026-09-03 under the previous default generator: 8 of 35 answers echoed a source
> marker. Suppressing the echo with an extra system-prompt rule was also tried and rejected in
> favour of removing the label at its source. Neither variant has been re-measured since.

Deployments whose name starts with `gpt-5` have default `temperature` and `topP` omitted. Other
deployments keep Spring AI defaults (`temperature` `0.7`, `topP` unset). The configured default is a
`gpt-5` deployment, so it runs without an explicit temperature.

> *Historical, not current evidence.* Lowering `temperature` was tried and rejected on 2026-09-02,
> when the default generator was `gpt-4o`: the native-method-wrapper question was asked six times at
> each of `0.7`, `0.2` and `0.0`, giving 4/6, 5/6 and 4/6 rubric-complete, and even at `0.0` the six
> responses differed from one another. At n=6 that is inside binomial noise, so the change was
> reverted rather than shipped on a difference indistinguishable from chance. It does not apply to
> the current default, which omits temperature entirely.

## HTTP API

### Health

```http
GET /actuator/health
```

### Ingest documents

```http
POST /doc-qa/documents
Content-Type: application/json

{
  "resourceLocations": ["file:EPAM_JavaSecureCodingGD.md"],
  "metadata": {
    "sourceType": "policy",
    "reviewed": true
  }
}
```

Supported formats are `.md`, `.markdown`, `.txt`, and `.pdf`. Only `classpath:` and
bounded local `file:` resources are accepted. Success is `202 Accepted`; mixed success is
`207 Multi-Status`; typed client failures are `400`.

**Check the condition the caller needs.** The controller invokes ingestion synchronously before
returning `202`; it does not schedule an asynchronous application job. The lifecycle smoke checks
the stored chunk count and additionally polls the chat search path for a source before evaluating
the newly ingested content. Its recorded create step needed one search attempt (7041 ms).
The retained review supports that readiness procedure, not a general claim about Chroma's internal
atomicity or an indexing-delay distribution. See `readinessMethod` in
`evaluation/runs/20260903T193000Z-live-smokes-review.json`.

### Ask a grounded question

```http
POST /doc-qa/chat
Content-Type: application/json

{
  "input": "Why should secrets not be stored in code or data?",
  "conversationId": "policy-demo"
}
```

The response contract is:

```json
{
  "response": "Grounded answer",
  "sources": [
    {
      "documentName": "EPAM_JavaSecureCodingGD.md",
      "chunkId": "deterministic-chunk-id"
    }
  ]
}
```

When no usable context exists, the response is exactly:

```json
{
  "response": "I don't have enough information to answer this question.",
  "sources": []
}
```

### Delete documents

Exactly one selector is required:

```http
DELETE /doc-qa/documents?documentName=EPAM_JavaSecureCodingGD.md
```

```http
DELETE /doc-qa/documents?ids=chunk-id-1,chunk-id-2
```

Deletion is idempotent and returns `204 No Content`, including when nothing matches.
Validation errors are returned before the vector store is called:

- `EMPTY_DELETE_REQUEST`: no selector supplied.
- `AMBIGUOUS_DELETE_SELECTOR`: both selectors supplied.
- `INVALID_DELETE_SELECTOR`: blank/oversized input, blank id, oversized id list, or a
  `documentName` containing `'`, `"`, or `\`.

Document names are sanitized during ingestion by replacing these three unsafe characters
with `_`. This is deterministic but not reversible, so two original names can collide on
the same stored name. A rejected name can still be deleted by exact chunk ids.

Name-based resolution is bounded by `app.documents.processing.max-delete-resolution-passes`.
It uses similarity threshold `0.0`; a genuine match with negative similarity remains a
known structural risk.

### Replace one document

```http
PUT /doc-qa/documents
Content-Type: application/json

{
  "resourceLocations": ["file:EPAM_JavaSecureCodingGD.md"]
}
```

Success returns `200 OK`. Replacement resolves the old ids, ingests the new content, then
deletes `staleIds - newIds`. The set difference protects byte-identical and partially
unchanged replacement from deleting the content just written.

Replacement is not transactional. If cleanup fails after ingestion, both versions can
remain indexed. This failure mode prefers duplication over data loss; operators must clean
up by stale chunk ids, not by document name.

## Reproducible evaluation

The runner requires a built executable JAR, existing environment variables, a running
Chroma instance, and a fresh dedicated collection:

```powershell
$env:CHROMA_COLLECTION = 'task2-evaluation-<unique-run-id>'
.\mvnw.cmd -pl 02-rag clean package '-DskipTests'
.\02-rag\scripts\run-live-evaluation.ps1 `
  -Suite All `
  -ExperimentCase All `
  -OutputPath .\02-rag\evaluation\runs\<unique-run-id>.json
```

The runner executes:

1. Five policy-grounded questions against `gpt-5-mini-2025-08-07` (the configured default),
   `gpt-4o`, and `gpt-4.1-nano-2025-04-14`.
2. `topK` sweep: 1, 4, 20.
3. Similarity-threshold sweep: 0.1, 0.5, 0.8.
4. Supported grounding and unsupported refusal/source behavior.
5. Query rewrite/expansion toggles.
6. Two-turn query compression off/on.
7. Baseline, MMR, un-narrowed top-20, and ordinary-question MMR cases.

Noisy-corpus, PUT, and DELETE checks are separate live smokes because they require multiple
documents or lifecycle calls that the runner intentionally does not perform.

### Proving the configured default generator

The run above sets `AZURE_OPEN_AI_DEPLOYMENT_NAME` and passes an explicit deployment argument for
every model in the comparison sweep, so its results are evidence about those models - not about the
generator the application ships with. To produce evidence for the **configured** default, add
`-UseConfiguredDefaultGenerator`:

```powershell
$env:CHROMA_COLLECTION = 'task2-default-<unique-run-id>'
.\mvnw.cmd -pl 02-rag clean package '-DskipTests'
.\02-rag\scripts\run-live-evaluation.ps1 `
  -Suite Comparison `
  -UseConfiguredDefaultGenerator `
  -OutputPath .\02-rag\evaluation\runs\<unique-run-id>.json
```

In that mode the runner removes `AZURE_OPEN_AI_DEPLOYMENT_NAME` from the process environment and
omits `--spring.ai.azure.openai.chat.options.deployment-name` entirely, so the application resolves
its own `application.yml` fallback. It then reads the deployment back from the application's startup
line `Resolved generator deployment=..., embedding deployment=...` (logged by
`GeneratorDeploymentReporter`), fails the run if that is outside the allow-list, and records it on
every result and in the artifact's `generatorSelection` block:

```json
"generatorSelection": {
  "usedConfiguredDefault": true,
  "configuredDefaultDeployment": "gpt-5-mini-2025-08-07",
  "resolvedDeployment": "gpt-5-mini-2025-08-07",
  "deploymentEnvironmentVariable": null
}
```

`usedConfiguredDefault: true` together with a null `deploymentEnvironmentVariable` is what makes a
capture evidence for the shipped default rather than for whatever the harness chose. Every run,
including an ordinary one, cross-checks the reported deployment against the requested one and fails
on a mismatch, so a silent override cannot go unnoticed.

The runner records technical evidence only. A supported answer with the expected document name
and nonblank chunk IDs is not final evidence of source attribution: the review must match every
id to an ingestion manifest and check every referenced chunk for content relevance.
Existence alone is insufficient.

After the run, an agent records a justified `PASS` or `FAIL` for every captured result and a
1–5 score for each comparison model in a separate review artifact. A completed review has no
unresolved verdicts. Keep the technical capture and its review separate; never edit the
technical capture to insert later judgments.

### Review contract

The review contract is `evaluation/objective-review-schema.json`. Each axis is scored on its own,
because a failure on one says nothing about the others:

| Axis | Scope | Values |
|---|---|---|
| `answerStatus` | every captured result | `PASS` / `FAIL` |
| `sourceTraceabilityStatus` | every captured result | `PASS` / `FAIL` |
| `sourceCoverageStatus` | every captured result | `PASS` / `FAIL` |
| `requirementStatus` | every result except an experiment variant | `PASS` / `FAIL` |
| `sourcePrecision` | every captured result | measured counts, never a verdict |

`requirementStatus` is tallied per `readmeScope`, never as one combined figure, because the README
states three separate obligations:

| `readmeScope` | Obligation |
|---|---|
| `core-contract` | the shipped default generator answering a rubric question at the shipped default retrieval configuration |
| `model-comparison` | the same questions answered by another generator — the README's separate model-choice subtask |
| `experiment-default-config` | an Experiments & Edge Cases case whose variant is the default configuration |
| `experiment-variant` | a deliberately non-default configuration — carries no `requirementStatus`, so a configuration trade-off is never reported as a module failure; the outcome goes in `experimentObservation` |

Source verdicts resolve against a committed manifest, not an ad-hoc lookup. Rebuild it with
`evaluation/tools/ManifestDump.java`, which drives the committed ingestion pipeline offline —
`chunkId` is `sha256(documentId | chunkIndex | chunkText | embeddingModel)`, so no vector store
or embedding call is involved. Chunk content and metadata are reproducible; serialized bytes also
depend on LF/CRLF line endings, which must be normalized for cross-platform byte comparisons.

The tool targets Java 21, and a `java` on `PATH` may be older, so pin the interpreter through
`JAVA_HOME`. Extraction goes to a scratch directory outside the repository so the checkout stays
clean. Package first: a full `clean verify` leaves `02-rag/target/02-rag-0.0.1-SNAPSHOT-exec.jar`
overwritten by the test fixture that exercises the runner's failure path.

```powershell
.\mvnw.cmd -pl 02-rag clean package '-DskipTests'
$repo = (Get-Location).Path
$work = Join-Path $env:TEMP 'rag-manifest'
New-Item -ItemType Directory -Force $work | Out-Null
& "$env:JAVA_HOME\bin\java.exe" -version    # must report 21
Push-Location $work
& "$env:JAVA_HOME\bin\jar.exe" xf "$repo\02-rag\target\02-rag-0.0.1-SNAPSHOT-exec.jar" `
  BOOT-INF/lib BOOT-INF/classes
Pop-Location
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 `
  -cp "$work\BOOT-INF\classes;$work\BOOT-INF\lib\*" -d "$work\out" `
  02-rag\evaluation\tools\ManifestDump.java
& "$env:JAVA_HOME\bin\java.exe" `
  -cp "$work\out;$work\BOOT-INF\classes;$work\BOOT-INF\lib\*" `
  com.epam.docqachatbot.ingestion.ManifestDump `
  02-rag/EPAM_JavaSecureCodingGD.md file:02-rag/EPAM_JavaSecureCodingGD.md `
  EPAM_JavaSecureCodingGD.md text-embedding-3-small-1 `
  02-rag/evaluation/runs/<run-id>-chunk-manifest.json
```

The packaged jar is an untracked build output whose digest changes on every package, so it is
historical provenance only and never a precondition: the manifest is determined by the committed
ingestion classes, the corpus bytes, the ingest location and the embedding-model name.

Every returned `chunkId` is then recorded individually in the review's `classifiedSources`, with
its resolved chunk index, heading path and role (`direct`, `contextual`, `irrelevant`), so no
precision count is asserted without a per-chunk justification.

## Reference materials

- [LangChain4j RAG concepts](https://docs.langchain4j.dev/tutorials/rag)
- [Spring AI RAG concepts](https://docs.spring.io/spring-ai/reference/concepts.html#concept-rag)
- [Rerankers and two-stage retrieval](https://www.pinecone.io/learn/series/rag/rerankers/#Power-of-Rerankers)
- [Advanced RAG techniques with graphs](https://neo4j.com/blog/genai/advanced-rag-techniques/)
