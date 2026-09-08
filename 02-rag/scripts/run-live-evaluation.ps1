param(
  [ValidateSet('All', 'Comparison', 'Experiments')]
  [string]$Suite = 'All',

  [ValidateSet('All', 'Simple', 'Detail', 'Synthesis', 'Analysis', 'FiveBulletSummary')]
  [string]$ComparisonCase = 'All',

  [ValidateSet('All', 'TopK', 'SimilarityThreshold', 'GroundingRefusalSources', 'QueryTransformationExpansion', 'QueryCompression', 'Reranking')]
  [string]$ExperimentCase = 'All',

  [Parameter(Mandatory = $true)]
  [string]$OutputPath,

  [ValidateRange(1024, 65535)]
  [int]$Port = 18082,

  [ValidateRange(10, 300)]
  [int]$ReadinessTimeoutSeconds = 60,

  [ValidateRange(5, 300)]
  [int]$HttpTimeoutSeconds = 120,

  [switch]$ContractTest,

  # Start the application with no generator override at all - no AZURE_OPEN_AI_DEPLOYMENT_NAME in
  # the environment and no --spring.ai.azure.openai.chat.options.deployment-name argument - so the
  # run exercises whatever application.yml resolves to. The resolved deployment is read back from
  # the application's own startup log and recorded on every result, which is what makes a run
  # evidence for the configured default rather than for whatever the harness happened to set.
  # Only meaningful for the Comparison suite, which is the module's core contract.
  [switch]$UseConfiguredDefaultGenerator
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function ConvertTo-SanitizedJson {
  param(
    [Parameter(Mandatory = $true)] [object]$Value,
    [string[]]$SecretValues = @()
  )

  $json = $Value | ConvertTo-Json -Depth 30

  # Windows PowerShell's ConvertTo-Json escapes ' < > & as \uXXXX - a blunt HTML-injection guard
  # inherited from .NET, for a context these artifacts never enter. Undoing it is not cosmetic:
  # redaction below searches the serialized text for the raw secret, so a secret containing any of
  # those four characters would be serialized escaped, missed by the search, and written to the
  # artifact in full. Unescape first, then redact. It also keeps output identical across PowerShell
  # editions, which otherwise disagree on this, and keeps the artifact readable in a diff.
  $json = [regex]::Replace($json, '(\\+)u00(27|3[cCeE]|26)', {
    param($match)
    $backslashes = $match.Groups[1].Value
    # An even run means the \u is itself escaped - literal text, not an escape sequence.
    if ($backslashes.Length % 2 -eq 0) { return $match.Value }
    $character = [char][Convert]::ToInt32($match.Groups[2].Value, 16)
    return $backslashes.Substring(0, $backslashes.Length - 1) + $character
  })

  foreach ($secret in $SecretValues | Where-Object { $_ }) {
    $json = $json.Replace($secret, '[REDACTED]')
  }
  return [regex]::Replace(
    $json,
    '(?i)(authorization|api[-_]?key)\s*[:=]\s*[^\s,"}]+',
    '$1=[REDACTED]')
}

function New-SafeFailure {
  param([Parameter(Mandatory = $true)] [Exception]$Exception)

  return [ordered]@{
    errorType = $Exception.GetType().FullName
    details = 'See the local process exit status; secret-bearing server logs were deleted.'
  }
}

function New-EvaluationArtifact {
  param(
    [Parameter(Mandatory = $true)] [string]$Status,
    [Parameter(Mandatory = $true)] [string]$Collection,
    [Parameter(Mandatory = $true)] [string]$EmbeddingDeployment,
    [Parameter(Mandatory = $true)] [int]$IngestionCount,
    [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]]$Results,
    [Parameter(Mandatory = $true)] [object[]]$ModelScores,
    [Parameter(Mandatory = $true)] [string]$ConfiguredDefaultDeployment,
    [AllowNull()] [string]$ResolvedDeployment,
    [AllowNull()] [string]$DeploymentEnvironmentVariable,
    [AllowNull()] [object]$Failure
  )

  return [ordered]@{
    schemaVersion = 1
    status = $Status
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    fixedIndex = [ordered]@{
      collection = $Collection
      embeddingDeployment = $EmbeddingDeployment
      corpus = 'EPAM_JavaSecureCodingGD.md'
      ingestionCount = $IngestionCount
    }
    generatorSelection = [ordered]@{
      # True only when the application was started with no generator override of any kind, so the
      # results are evidence for the configured default rather than for the harness's own choice.
      usedConfiguredDefault = [bool]$UseConfiguredDefaultGenerator
      configuredDefaultDeployment = $ConfiguredDefaultDeployment
      resolvedDeployment = $ResolvedDeployment
      deploymentEnvironmentVariable = $DeploymentEnvironmentVariable
    }
    results = @($Results)
    modelScores = @($ModelScores)
    failure = $Failure
  }
}

function Get-SourceAttributionAssessment {
  param(
    [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]]$Sources,
    [AllowNull()] [string]$ExpectedDocumentName
  )

  if (-not $ExpectedDocumentName) {
    return [ordered]@{
      sourceShapeValid = $null
      manifestMatchVerified = $null
      contentRelevanceVerified = $null
      agentReviewRequired = $false
      sourceAttributionCorrect = $null
      status = 'NOT_PROVEN'
    }
  }

  $shapeValid = $Sources.Count -gt 0 -and @($Sources | Where-Object {
    $_.documentName -ne $ExpectedDocumentName -or
    [string]::IsNullOrWhiteSpace([string]$_.chunkId)
  }).Count -eq 0
  return [ordered]@{
    sourceShapeValid = $shapeValid
    manifestMatchVerified = $null
    contentRelevanceVerified = $null
    agentReviewRequired = $shapeValid
    sourceAttributionCorrect = $null
    status = if ($shapeValid) { 'NOT_PROVEN' } else { 'FAIL' }
  }
}

if ($ContractTest) {
  # The secret deliberately contains ' < > & : those are exactly the characters ConvertTo-Json
  # escapes, and a redaction that runs against escaped text would miss them.
  $contractSecret = "contract-test-secret'value<and>more&here"
  $contractException = [InvalidOperationException]::new(
    "raw provider error containing $contractSecret")
  $validSources = @([ordered]@{ documentName = 'policy.md'; chunkId = 'chunk-1' })
  $validAssessment = Get-SourceAttributionAssessment -Sources $validSources `
    -ExpectedDocumentName 'policy.md'
  $contractResult = [ordered]@{
    deployment = 'gpt-4o'
    suite = 'GroundingRefusalSources'
    case = 'Supported'
    question = 'contract question'
    config = [ordered]@{
      topK = 5
      similarityThreshold = 0.3
      embeddingDeployment = 'fixed-embedding'
      collection = 'fixed-collection'
    }
    latencyMs = 1
    httpStatus = 200
    status = $validAssessment.status
    response = "sanitized response $contractSecret"
    sources = $validSources
    retrievalScoresAvailable = $false
    rubric = [ordered]@{
      pass = $null
      notes = 'Agent review must verify chunk existence and relevance before a final verdict.'
      sourceFaithful = $null
      refusalCorrect = $null
      sourceShapeValid = $validAssessment.sourceShapeValid
      manifestMatchVerified = $validAssessment.manifestMatchVerified
      contentRelevanceVerified = $validAssessment.contentRelevanceVerified
      agentReviewRequired = $validAssessment.agentReviewRequired
      sourceAttributionCorrect = $validAssessment.sourceAttributionCorrect
    }
  }
  $invalidSources = @([ordered]@{ documentName = 'policy.md'; chunkId = '   ' })
  $invalidAssessment = Get-SourceAttributionAssessment -Sources $invalidSources `
    -ExpectedDocumentName 'policy.md'
  $invalidContractResult = [ordered]@{
    deployment = 'gpt-4o'
    suite = 'GroundingRefusalSources'
    case = 'SupportedBlankChunkId'
    question = 'contract question'
    config = $contractResult.config
    latencyMs = 1
    httpStatus = 200
    status = $invalidAssessment.status
    response = 'invalid attribution shape'
    sources = $invalidSources
    retrievalScoresAvailable = $false
    rubric = [ordered]@{
      pass = $false
      notes = 'Invalid source shape cannot pass attribution.'
      sourceFaithful = $null
      refusalCorrect = $null
      sourceShapeValid = $invalidAssessment.sourceShapeValid
      manifestMatchVerified = $invalidAssessment.manifestMatchVerified
      contentRelevanceVerified = $invalidAssessment.contentRelevanceVerified
      agentReviewRequired = $invalidAssessment.agentReviewRequired
      sourceAttributionCorrect = $invalidAssessment.sourceAttributionCorrect
    }
  }
  $contractArtifact = New-EvaluationArtifact -Status 'FAILED' `
    -Collection 'fixed-collection' -EmbeddingDeployment 'fixed-embedding' `
    -IngestionCount 1 -Results @($contractResult, $invalidContractResult) -ModelScores @(
      [ordered]@{ deployment = 'gpt-4o'; score = $null; status = 'NOT_PROVEN'; notes = 'contract' }
    ) -ConfiguredDefaultDeployment 'gpt-4o' -ResolvedDeployment 'gpt-4o' `
    -DeploymentEnvironmentVariable 'gpt-4o' `
    -Failure (New-SafeFailure -Exception $contractException)
  $contractJson = ConvertTo-SanitizedJson -Value $contractArtifact `
    -SecretValues @($contractSecret)
  $resolvedContractOutput = [IO.Path]::GetFullPath($OutputPath)
  $contractOutputDirectory = [IO.Path]::GetDirectoryName($resolvedContractOutput)
  if ($contractOutputDirectory) {
    [IO.Directory]::CreateDirectory($contractOutputDirectory) | Out-Null
  }
  [IO.File]::WriteAllText(
    $resolvedContractOutput,
    $contractJson + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false))
  return
}

$requiredEnvironment = @(
  'AZURE_OPEN_AI_KEY',
  'AZURE_OPEN_AI_ENDPOINT',
  'AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME',
  'CHROMA_BASE_URL',
  'CHROMA_COLLECTION'
)
foreach ($name in $requiredEnvironment) {
  if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
    throw "$name must already be present in the process environment."
  }
}

$generatorDeployments = @(
  'gpt-4o',
  'gpt-4.1-nano-2025-04-14',
  'gpt-5-mini-2025-08-07'
)
$fixedEmbeddingDeployment = $env:AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME
$fixedCollection = $env:CHROMA_COLLECTION

# Shipped retrieval defaults, mirrored from 02-rag/src/main/resources/application.yml.
# The Comparison suite is the module's core contract, so it must exercise the configuration the
# application actually ships with. Pinning it to a fixed topK here would silently measure a
# configuration nobody runs, and would hide any regression in the shipped default.
$shippedTopK = 5
$shippedSimilarityThreshold = 0.3
$shippedMmrEnabled = $false
$shippedMmrFinalTopK = 5
$shippedMmrLambda = 0.5

# Shipped generator deployment, mirrored from the same file's
# ${AZURE_OPEN_AI_DEPLOYMENT_NAME:...} fallback. The warm-up ingest and the Experiments suite use
# it instead of a hard-coded name, so a change to the shipped default is exercised rather than
# silently bypassed. Pass -UseConfiguredDefaultGenerator to prove the value below really is what
# the application resolves, rather than trusting this mirror.
$shippedGeneratorDeployment = 'gpt-5-mini-2025-08-07'
$chromaBaseUrl = $env:CHROMA_BASE_URL.TrimEnd('/')
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$moduleRoot = Join-Path $repositoryRoot '02-rag'
$jarPath = Join-Path $moduleRoot 'target\02-rag-0.0.1-SNAPSHOT-exec.jar'
$policyPath = Join-Path $moduleRoot 'EPAM_JavaSecureCodingGD.md'
$questionsPath = Join-Path $moduleRoot 'evaluation\questions.json'
$experimentsPath = Join-Path $moduleRoot 'evaluation\experiments.json'
$javaPath = Join-Path $env:JAVA_HOME 'bin\java.exe'

if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) {
  throw 'Java was not found under JAVA_HOME. Java 21 is required.'
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
  throw 'Executable JAR not found. Run .\mvnw.cmd -pl 02-rag clean package first.'
}
if (-not (Test-Path -LiteralPath $policyPath -PathType Leaf)) {
  throw 'The repository EPAM Java secure coding policy is missing.'
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
  throw "Port $Port is already in use."
}

$questions = Get-Content -LiteralPath $questionsPath -Raw | ConvertFrom-Json
$experiments = Get-Content -LiteralPath $experimentsPath -Raw | ConvertFrom-Json
$deploymentWasDefined = Test-Path 'Env:AZURE_OPEN_AI_DEPLOYMENT_NAME'
$previousDeployment = $env:AZURE_OPEN_AI_DEPLOYMENT_NAME
$application = $null
$httpClient = [System.Net.Http.HttpClient]::new()
$httpClient.Timeout = [TimeSpan]::FromSeconds($HttpTimeoutSeconds)
$stdoutPath = $null
$stderrPath = $null
$indexed = $false
$results = [System.Collections.Generic.List[object]]::new()
$runStatus = 'COMPLETED'
$failure = $null
$runException = $null

function Restore-ProcessEnvironmentVariable {
  param(
    [Parameter(Mandatory = $true)] [string]$Name,
    [Parameter(Mandatory = $true)] [bool]$WasDefined,
    [AllowNull()] [string]$PreviousValue
  )

  if ($WasDefined) {
    [Environment]::SetEnvironmentVariable($Name, $PreviousValue, 'Process')
  } else {
    [Environment]::SetEnvironmentVariable($Name, $null, 'Process')
  }
}

function Stop-EvaluationApplication {
  if ($script:application -and -not $script:application.HasExited) {
    Stop-Process -Id $script:application.Id -Force -ErrorAction SilentlyContinue
    [void]$script:application.WaitForExit(5000)
  }
  $script:application = $null
  if ($script:stdoutPath) {
    Remove-Item -LiteralPath $script:stdoutPath -Force -ErrorAction SilentlyContinue
  }
  if ($script:stderrPath) {
    Remove-Item -LiteralPath $script:stderrPath -Force -ErrorAction SilentlyContinue
  }
  $script:stdoutPath = $null
  $script:stderrPath = $null
}

function ConvertTo-JavaBooleanLiteral {
  param([Parameter(Mandatory = $true)] [bool]$Value)

  return $Value.ToString([Globalization.CultureInfo]::InvariantCulture).ToLowerInvariant()
}

function Start-EvaluationApplication {
  param(
    [Parameter(Mandatory = $true)] [string]$Deployment,
    [Parameter(Mandatory = $true)] [int]$TopK,
    [Parameter(Mandatory = $true)] [double]$SimilarityThreshold,
    [AllowNull()] [Nullable[bool]]$CompressionEnabled = $null,
    [AllowNull()] [Nullable[bool]]$RewriteEnabled = $null,
    [AllowNull()] [Nullable[bool]]$QueryExpansionEnabled = $null,
    [AllowNull()] [Nullable[int]]$NumberOfQueries = $null,
    [AllowNull()] [Nullable[bool]]$IncludeOriginal = $null,
    [AllowNull()] [Nullable[bool]]$MmrEnabled = $null,
    [AllowNull()] [Nullable[int]]$MmrFinalTopK = $null,
    [AllowNull()] [Nullable[double]]$MmrLambda = $null
  )

  if (-not $UseConfiguredDefaultGenerator -and $generatorDeployments -notcontains $Deployment) {
    throw 'Generator deployment is outside the evaluation allow-list.'
  }
  if ($env:AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME -ne $fixedEmbeddingDeployment -or
      $env:CHROMA_COLLECTION -ne $fixedCollection) {
    throw 'The fixed embedding deployment or Chroma collection changed during the run.'
  }
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port became unavailable during evaluation."
  }

  if ($UseConfiguredDefaultGenerator) {
    # A leftover environment variable would silently become the override this mode exists to avoid.
    Remove-Item Env:\AZURE_OPEN_AI_DEPLOYMENT_NAME -ErrorAction SilentlyContinue
  } else {
    $env:AZURE_OPEN_AI_DEPLOYMENT_NAME = $Deployment
  }
  # Recorded now, while it is in effect. The run's finally block restores the caller's original
  # value, so reading the variable when the artifact is written would report the wrong thing.
  $script:deploymentEnvironmentVariableDuringRun = $env:AZURE_OPEN_AI_DEPLOYMENT_NAME
  $logLabel = if ($UseConfiguredDefaultGenerator) { 'configured-default' } else { $Deployment }
  $script:stdoutPath = Join-Path $env:TEMP ("rag-evaluation-$logLabel-" + [guid]::NewGuid().ToString('N') + '.out.log')
  $script:stderrPath = Join-Path $env:TEMP ("rag-evaluation-$logLabel-" + [guid]::NewGuid().ToString('N') + '.err.log')
  $thresholdText = $SimilarityThreshold.ToString([Globalization.CultureInfo]::InvariantCulture)
  $arguments = @(
    '-jar', $jarPath,
    "--server.port=$Port",
    "--spring.ai.azure.openai.embedding.options.deployment-name=$fixedEmbeddingDeployment",
    "--app.documents.processing.embedding-model=$fixedEmbeddingDeployment",
    "--app.vectorstore.chroma.collection-name=$fixedCollection",
    "--app.rag.retrieval.top-k=$TopK",
    "--app.rag.retrieval.similarity-threshold=$thresholdText",
    '--app.documents.default.bootstrap-enabled=false'
  )
  if (-not $UseConfiguredDefaultGenerator) {
    $arguments += "--spring.ai.azure.openai.chat.options.deployment-name=$Deployment"
  }
  # Query transformation/expansion overrides are optional and off by default (matching the
  # shipped application.yml defaults): a variant that does not specify one leaves the
  # corresponding --app.rag.* flag unset, so the application falls back to its own configured
  # default, exactly preserving existing behaviour for every pre-existing experiment/comparison call.
  if ($null -ne $CompressionEnabled) {
    $arguments += "--app.rag.query-transformation.compression-enabled=$(ConvertTo-JavaBooleanLiteral $CompressionEnabled)"
  }
  if ($null -ne $RewriteEnabled) {
    $arguments += "--app.rag.query-transformation.rewrite-enabled=$(ConvertTo-JavaBooleanLiteral $RewriteEnabled)"
  }
  if ($null -ne $QueryExpansionEnabled) {
    $arguments += "--app.rag.query-expansion.enabled=$(ConvertTo-JavaBooleanLiteral $QueryExpansionEnabled)"
  }
  if ($null -ne $NumberOfQueries) {
    $arguments += "--app.rag.query-expansion.number-of-queries=$NumberOfQueries"
  }
  if ($null -ne $IncludeOriginal) {
    $arguments += "--app.rag.query-expansion.include-original=$(ConvertTo-JavaBooleanLiteral $IncludeOriginal)"
  }
  if ($null -ne $MmrEnabled) {
    $arguments += "--app.rag.retrieval.mmr-enabled=$(ConvertTo-JavaBooleanLiteral $MmrEnabled)"
  }
  if ($null -ne $MmrFinalTopK) {
    $arguments += "--app.rag.retrieval.mmr-final-top-k=$MmrFinalTopK"
  }
  if ($null -ne $MmrLambda) {
    $mmrLambdaText = $MmrLambda.ToString([Globalization.CultureInfo]::InvariantCulture)
    $arguments += "--app.rag.retrieval.mmr-lambda=$mmrLambdaText"
  }

  $script:application = Start-Process -FilePath $javaPath `
    -ArgumentList $arguments `
    -WorkingDirectory $repositoryRoot `
    -PassThru `
    -WindowStyle Hidden `
    -RedirectStandardOutput $script:stdoutPath `
    -RedirectStandardError $script:stderrPath

  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ReadinessTimeoutSeconds)
  $ready = $false
  while ([DateTimeOffset]::UtcNow -lt $deadline) {
    if ($script:application.HasExited) {
      break
    }
    $readinessCancellation = [Threading.CancellationTokenSource]::new(
      [TimeSpan]::FromSeconds(2))
    try {
      $health = $httpClient.GetAsync(
        "http://127.0.0.1:$Port/actuator/health",
        $readinessCancellation.Token).GetAwaiter().GetResult()
      try {
        if ($health.IsSuccessStatusCode) {
          $ready = $true
          break
        }
      } finally {
        $health.Dispose()
      }
    } catch [System.Net.Http.HttpRequestException] {
      Start-Sleep -Milliseconds 250
    } catch [System.Threading.Tasks.TaskCanceledException] {
      Start-Sleep -Milliseconds 250
    } finally {
      $readinessCancellation.Dispose()
    }
  }
  if (-not $ready) {
    throw 'The evaluation application did not become ready within the configured timeout.'
  }

  # The application logs the deployment it actually resolved. Read it back rather than assuming the
  # requested one took effect: that is the whole point of -UseConfiguredDefaultGenerator, and it is
  # a cheap consistency check on every other run.
  $resolved = $null
  $logDeadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
  while ([DateTimeOffset]::UtcNow -lt $logDeadline -and -not $resolved) {
    $match = Select-String -LiteralPath $script:stdoutPath `
      -Pattern 'Resolved generator deployment=([^,]+), embedding deployment=(\S+)' `
      -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($match) {
      $resolved = $match.Matches[0].Groups[1].Value
      $script:resolvedEmbeddingDeployment = $match.Matches[0].Groups[2].Value
    } else {
      Start-Sleep -Milliseconds 250
    }
  }
  if (-not $resolved) {
    throw 'The application did not report its resolved generator deployment.'
  }
  if ($generatorDeployments -notcontains $resolved) {
    throw "The application resolved generator '$resolved', which is outside the evaluation allow-list."
  }
  if (-not $UseConfiguredDefaultGenerator -and $resolved -ne $Deployment) {
    throw "The application resolved generator '$resolved' but '$Deployment' was requested."
  }
  if ($script:resolvedEmbeddingDeployment -ne $fixedEmbeddingDeployment) {
    throw "The application resolved embedding deployment '$($script:resolvedEmbeddingDeployment)', not the fixed one."
  }
  $script:resolvedDeployment = $resolved
}

function Invoke-JsonPost {
  param(
    [Parameter(Mandatory = $true)] [string]$Uri,
    [Parameter(Mandatory = $true)] [object]$Body
  )

  $content = [System.Net.Http.StringContent]::new(
    ($Body | ConvertTo-Json -Depth 10 -Compress),
    [Text.Encoding]::UTF8,
    'application/json')
  try {
    return $httpClient.PostAsync($Uri, $content).GetAwaiter().GetResult()
  } finally {
    $content.Dispose()
  }
}

function Invoke-PolicyIngestion {
  if ($script:indexed) {
    throw 'The policy corpus may be ingested only once per evaluation run.'
  }

  $response = Invoke-JsonPost -Uri "http://127.0.0.1:$Port/doc-qa/documents" -Body @{
    resourceLocations = @('file:02-rag/EPAM_JavaSecureCodingGD.md')
    metadata = @{ evaluationCorpus = 'epam-java-secure-coding-guidelines' }
  }
  try {
    if ([int]$response.StatusCode -ne 202) {
      throw 'Policy ingestion did not return HTTP 202.'
    }
    $script:indexed = $true
  } finally {
    $response.Dispose()
  }
}

function Invoke-ChatEvaluation {
  param(
    [Parameter(Mandatory = $true)] [string]$Deployment,
    [Parameter(Mandatory = $true)] [string]$ResultSuite,
    [Parameter(Mandatory = $true)] [string]$CaseName,
    [Parameter(Mandatory = $true)] [string]$Question,
    [Parameter(Mandatory = $true)] [int]$TopK,
    [Parameter(Mandatory = $true)] [double]$SimilarityThreshold,
    [AllowNull()] [object]$RubricDefinition,
    [AllowNull()] [string]$ExpectedResponse,
    [AllowNull()] [string]$ExpectedDocumentName
  )

  $timer = [Diagnostics.Stopwatch]::StartNew()
  $response = Invoke-JsonPost -Uri "http://127.0.0.1:$Port/doc-qa/chat" -Body @{
    input = $Question
    conversationId = "evaluation-$($Deployment)-$($ResultSuite.ToLowerInvariant())-$($CaseName.ToLowerInvariant())"
  }
  $timer.Stop()
  try {
    $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $body = if ($bodyText) { $bodyText | ConvertFrom-Json } else { $null }
    $sources = @()
    if ($body -and $body.sources) {
      $sources = @($body.sources | ForEach-Object {
        [ordered]@{ documentName = $_.documentName; chunkId = $_.chunkId }
      })
    }

    $sourceAssessment = Get-SourceAttributionAssessment -Sources $sources `
      -ExpectedDocumentName $ExpectedDocumentName
    $refusalCorrect = $null
    if ($ExpectedResponse) {
      $refusalCorrect = $body -and $body.response -ceq $ExpectedResponse -and $sources.Count -eq 0
    }
    $httpSuccess = $response.IsSuccessStatusCode
    $automaticPass = if ($ExpectedResponse) { $refusalCorrect } else { $null }
    $resultStatus = if (-not $httpSuccess -or $automaticPass -eq $false) {
      'FAIL'
    } elseif ($automaticPass -eq $true) {
      'PASS'
    } elseif ($ExpectedDocumentName) {
      $sourceAssessment.status
    } else {
      'NOT_PROVEN'
    }

    $script:results.Add([ordered]@{
      deployment = $Deployment
      suite = $ResultSuite
      case = $CaseName
      question = $Question
      config = [ordered]@{
        topK = $TopK
        similarityThreshold = $SimilarityThreshold
        embeddingDeployment = $fixedEmbeddingDeployment
        collection = $fixedCollection
      }
      latencyMs = [int]$timer.ElapsedMilliseconds
      httpStatus = [int]$response.StatusCode
      status = $resultStatus
      response = if ($body) { $body.response } else { $null }
      sources = $sources
      retrievalScoresAvailable = $false
      rubric = [ordered]@{
        pass = $automaticPass
        notes = if ($ExpectedDocumentName) {
          'NOT_PROVEN: agent review must verify every chunkId against the indexed manifest and answer relevance.'
        } elseif ($RubricDefinition) { $RubricDefinition.passRule } else { $null }
        sourceFaithful = $null
        refusalCorrect = $refusalCorrect
        sourceShapeValid = $sourceAssessment.sourceShapeValid
        manifestMatchVerified = $sourceAssessment.manifestMatchVerified
        contentRelevanceVerified = $sourceAssessment.contentRelevanceVerified
        agentReviewRequired = $sourceAssessment.agentReviewRequired
        sourceAttributionCorrect = $sourceAssessment.sourceAttributionCorrect
      }
    })

    if (-not $httpSuccess) {
      throw 'A bounded chat request returned a non-success HTTP status.'
    }
  } finally {
    $response.Dispose()
  }
}

function Invoke-TwoTurnChatEvaluation {
  # The only case in this runner that reuses one conversationId across two chat calls: turn 1
  # establishes conversational context (recorded as an informational, not-independently-scored
  # "<case>-Turn1" result), then turn 2 is a purely referential follow-up sent on the *same*
  # conversationId (recorded as "<case>-Turn2" using the existing source-attribution machinery),
  # exercising CompressionQueryTransformer in the only mode it is meant for.
  param(
    [Parameter(Mandatory = $true)] [string]$Deployment,
    [Parameter(Mandatory = $true)] [string]$ResultSuite,
    [Parameter(Mandatory = $true)] [string]$CaseName,
    [Parameter(Mandatory = $true)] [string]$Turn1Question,
    [Parameter(Mandatory = $true)] [string]$Turn2Question,
    [Parameter(Mandatory = $true)] [int]$TopK,
    [Parameter(Mandatory = $true)] [double]$SimilarityThreshold,
    [AllowNull()] [string]$ExpectedDocumentName
  )

  $conversationId = "evaluation-$($Deployment)-$($ResultSuite.ToLowerInvariant())-$($CaseName.ToLowerInvariant())"
  $config = [ordered]@{
    topK = $TopK
    similarityThreshold = $SimilarityThreshold
    embeddingDeployment = $fixedEmbeddingDeployment
    collection = $fixedCollection
  }

  $turn1Timer = [Diagnostics.Stopwatch]::StartNew()
  $turn1Response = Invoke-JsonPost -Uri "http://127.0.0.1:$Port/doc-qa/chat" -Body @{
    input = $Turn1Question
    conversationId = $conversationId
  }
  $turn1Timer.Stop()
  try {
    $turn1BodyText = $turn1Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $turn1Body = if ($turn1BodyText) { $turn1BodyText | ConvertFrom-Json } else { $null }
    $turn1Sources = @()
    if ($turn1Body -and $turn1Body.sources) {
      $turn1Sources = @($turn1Body.sources | ForEach-Object {
        [ordered]@{ documentName = $_.documentName; chunkId = $_.chunkId }
      })
    }
    $turn1HttpSuccess = $turn1Response.IsSuccessStatusCode

    $script:results.Add([ordered]@{
      deployment = $Deployment
      suite = $ResultSuite
      case = "$CaseName-Turn1"
      question = $Turn1Question
      config = $config
      latencyMs = [int]$turn1Timer.ElapsedMilliseconds
      httpStatus = [int]$turn1Response.StatusCode
      status = if ($turn1HttpSuccess) { 'NOT_PROVEN' } else { 'FAIL' }
      response = if ($turn1Body) { $turn1Body.response } else { $null }
      sources = $turn1Sources
      retrievalScoresAvailable = $false
      rubric = [ordered]@{
        pass = $null
        notes = 'Context-setting turn: establishes conversation history for Turn2 and is not independently scored.'
        sourceFaithful = $null
        refusalCorrect = $null
        sourceShapeValid = $null
        manifestMatchVerified = $null
        contentRelevanceVerified = $null
        agentReviewRequired = $false
        sourceAttributionCorrect = $null
      }
    })

    if (-not $turn1HttpSuccess) {
      throw 'A bounded chat request returned a non-success HTTP status.'
    }
  } finally {
    $turn1Response.Dispose()
  }

  $turn2Timer = [Diagnostics.Stopwatch]::StartNew()
  $turn2Response = Invoke-JsonPost -Uri "http://127.0.0.1:$Port/doc-qa/chat" -Body @{
    input = $Turn2Question
    conversationId = $conversationId
  }
  $turn2Timer.Stop()
  try {
    $turn2BodyText = $turn2Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $turn2Body = if ($turn2BodyText) { $turn2BodyText | ConvertFrom-Json } else { $null }
    $turn2Sources = @()
    if ($turn2Body -and $turn2Body.sources) {
      $turn2Sources = @($turn2Body.sources | ForEach-Object {
        [ordered]@{ documentName = $_.documentName; chunkId = $_.chunkId }
      })
    }

    $turn2SourceAssessment = Get-SourceAttributionAssessment -Sources $turn2Sources `
      -ExpectedDocumentName $ExpectedDocumentName
    $turn2HttpSuccess = $turn2Response.IsSuccessStatusCode
    $turn2Status = if (-not $turn2HttpSuccess) {
      'FAIL'
    } elseif ($ExpectedDocumentName) {
      $turn2SourceAssessment.status
    } else {
      'NOT_PROVEN'
    }

    $script:results.Add([ordered]@{
      deployment = $Deployment
      suite = $ResultSuite
      case = "$CaseName-Turn2"
      question = $Turn2Question
      config = $config
      latencyMs = [int]$turn2Timer.ElapsedMilliseconds
      httpStatus = [int]$turn2Response.StatusCode
      status = $turn2Status
      response = if ($turn2Body) { $turn2Body.response } else { $null }
      sources = $turn2Sources
      retrievalScoresAvailable = $false
      rubric = [ordered]@{
        pass = $null
        notes = if ($ExpectedDocumentName) {
          'NOT_PROVEN: agent review must verify every chunkId against the indexed manifest and relevance to the resolved standalone query.'
        } else { $null }
        sourceFaithful = $null
        refusalCorrect = $null
        sourceShapeValid = $turn2SourceAssessment.sourceShapeValid
        manifestMatchVerified = $turn2SourceAssessment.manifestMatchVerified
        contentRelevanceVerified = $turn2SourceAssessment.contentRelevanceVerified
        agentReviewRequired = $turn2SourceAssessment.agentReviewRequired
        sourceAttributionCorrect = $turn2SourceAssessment.sourceAttributionCorrect
      }
    })

    if (-not $turn2HttpSuccess) {
      throw 'A bounded chat request returned a non-success HTTP status.'
    }
  } finally {
    $turn2Response.Dispose()
  }
}

function Invoke-ApplicationRun {
  param(
    [Parameter(Mandatory = $true)] [string]$Deployment,
    [Parameter(Mandatory = $true)] [int]$TopK,
    [Parameter(Mandatory = $true)] [double]$SimilarityThreshold,
    [AllowNull()] [Nullable[bool]]$CompressionEnabled = $null,
    [AllowNull()] [Nullable[bool]]$RewriteEnabled = $null,
    [AllowNull()] [Nullable[bool]]$QueryExpansionEnabled = $null,
    [AllowNull()] [Nullable[int]]$NumberOfQueries = $null,
    [AllowNull()] [Nullable[bool]]$IncludeOriginal = $null,
    [AllowNull()] [Nullable[bool]]$MmrEnabled = $null,
    [AllowNull()] [Nullable[int]]$MmrFinalTopK = $null,
    [AllowNull()] [Nullable[double]]$MmrLambda = $null,
    [switch]$Ingest,
    [Parameter(Mandatory = $true)] [scriptblock]$Evaluate
  )

  Start-EvaluationApplication -Deployment $Deployment -TopK $TopK -SimilarityThreshold $SimilarityThreshold `
    -CompressionEnabled $CompressionEnabled -RewriteEnabled $RewriteEnabled `
    -QueryExpansionEnabled $QueryExpansionEnabled -NumberOfQueries $NumberOfQueries `
    -IncludeOriginal $IncludeOriginal -MmrEnabled $MmrEnabled -MmrFinalTopK $MmrFinalTopK `
    -MmrLambda $MmrLambda
  try {
    if ($Ingest) {
      Invoke-PolicyIngestion
    }
    & $Evaluate
  } finally {
    Stop-EvaluationApplication
  }
}

try {
  $heartbeat = $httpClient.GetAsync("$chromaBaseUrl/api/v2/heartbeat").GetAwaiter().GetResult()
  try {
    if (-not $heartbeat.IsSuccessStatusCode) {
      throw 'Chroma heartbeat did not return a success status.'
    }
  } finally {
    $heartbeat.Dispose()
  }

  Invoke-ApplicationRun -Deployment $shippedGeneratorDeployment -TopK $shippedTopK `
    -SimilarityThreshold $shippedSimilarityThreshold -MmrEnabled $shippedMmrEnabled `
    -MmrFinalTopK $shippedMmrFinalTopK -MmrLambda $shippedMmrLambda `
    -Ingest -Evaluate { }

  if ($Suite -in @('All', 'Comparison')) {
    $selectedQuestions = @($questions.cases | Where-Object {
      $ComparisonCase -eq 'All' -or $_.id -eq $ComparisonCase
    })
    # In configured-default mode the point is to exercise exactly one generator - the one
    # application.yml resolves to - so the model-comparison sweep is deliberately not run.
    $comparisonDeployments = if ($UseConfiguredDefaultGenerator) {
      @($shippedGeneratorDeployment)
    } else {
      $generatorDeployments
    }
    foreach ($deployment in $comparisonDeployments) {
      Invoke-ApplicationRun -Deployment $deployment -TopK $shippedTopK `
        -SimilarityThreshold $shippedSimilarityThreshold -MmrEnabled $shippedMmrEnabled `
        -MmrFinalTopK $shippedMmrFinalTopK -MmrLambda $shippedMmrLambda `
        -Evaluate {
          foreach ($case in $selectedQuestions) {
            # Always the deployment the application reported, never the one requested: in
            # configured-default mode nothing was requested at all.
            Invoke-ChatEvaluation -Deployment $script:resolvedDeployment -ResultSuite 'Comparison' `
              -CaseName $case.id -Question $case.question -TopK $shippedTopK `
              -SimilarityThreshold $shippedSimilarityThreshold `
              -RubricDefinition $case.rubric
          }
        }
    }
  }

  if ($Suite -in @('All', 'Experiments')) {
    $experimentDeployment = $shippedGeneratorDeployment
    if ($ExperimentCase -in @('All', 'TopK')) {
      $definition = $experiments.experiments | Where-Object id -eq 'TopK'
      $case = $questions.cases | Where-Object id -eq $definition.questionCase
      foreach ($variant in $definition.variants) {
        $variantTopK = [int]$variant.topK
        $variantThreshold = [double]$variant.similarityThreshold
        Invoke-ApplicationRun -Deployment $experimentDeployment -TopK $variantTopK `
          -SimilarityThreshold $variantThreshold -Evaluate {
            Invoke-ChatEvaluation -Deployment $experimentDeployment -ResultSuite 'TopK' `
              -CaseName "topK-$variantTopK" -Question $case.question -TopK $variantTopK `
              -SimilarityThreshold $variantThreshold -RubricDefinition $case.rubric
          }
      }
    }

    if ($ExperimentCase -in @('All', 'SimilarityThreshold')) {
      $definition = $experiments.experiments | Where-Object id -eq 'SimilarityThreshold'
      $case = $questions.cases | Where-Object id -eq $definition.questionCase
      foreach ($variant in $definition.variants) {
        $variantTopK = [int]$variant.topK
        $variantThreshold = [double]$variant.similarityThreshold
        Invoke-ApplicationRun -Deployment $experimentDeployment -TopK $variantTopK `
          -SimilarityThreshold $variantThreshold -Evaluate {
            Invoke-ChatEvaluation -Deployment $experimentDeployment -ResultSuite 'SimilarityThreshold' `
              -CaseName "threshold-$variantThreshold" -Question $case.question -TopK $variantTopK `
              -SimilarityThreshold $variantThreshold -RubricDefinition $case.rubric
          }
      }
    }

    if ($ExperimentCase -in @('All', 'GroundingRefusalSources')) {
      $definition = $experiments.experiments | Where-Object id -eq 'GroundingRefusalSources'
      Invoke-ApplicationRun -Deployment $experimentDeployment -TopK 5 -SimilarityThreshold 0.3 `
        -Evaluate {
          foreach ($variant in $definition.variants) {
            Invoke-ChatEvaluation -Deployment $experimentDeployment `
              -ResultSuite 'GroundingRefusalSources' -CaseName $variant.case `
              -Question $variant.question -TopK 5 -SimilarityThreshold 0.3 `
              -ExpectedResponse $variant.expectedResponse `
              -ExpectedDocumentName $variant.expectedDocumentName
          }
        }
    }

    if ($ExperimentCase -in @('All', 'QueryTransformationExpansion')) {
      $definition = $experiments.experiments | Where-Object id -eq 'QueryTransformationExpansion'
      $case = $questions.cases | Where-Object id -eq $definition.questionCase
      foreach ($variant in $definition.variants) {
        $variantTopK = [int]$variant.topK
        $variantThreshold = [double]$variant.similarityThreshold
        $variantCompressionEnabled = [bool]$variant.compressionEnabled
        $variantRewriteEnabled = [bool]$variant.rewriteEnabled
        $variantQueryExpansionEnabled = [bool]$variant.queryExpansionEnabled
        $variantNumberOfQueries = if ($null -ne $variant.numberOfQueries) { [int]$variant.numberOfQueries } else { $null }
        $variantIncludeOriginal = if ($null -ne $variant.includeOriginal) { [bool]$variant.includeOriginal } else { $null }
        Invoke-ApplicationRun -Deployment $experimentDeployment -TopK $variantTopK `
          -SimilarityThreshold $variantThreshold -CompressionEnabled $variantCompressionEnabled `
          -RewriteEnabled $variantRewriteEnabled -QueryExpansionEnabled $variantQueryExpansionEnabled `
          -NumberOfQueries $variantNumberOfQueries -IncludeOriginal $variantIncludeOriginal `
          -Evaluate {
            Invoke-ChatEvaluation -Deployment $experimentDeployment `
              -ResultSuite 'QueryTransformationExpansion' -CaseName $variant.case `
              -Question $case.question -TopK $variantTopK -SimilarityThreshold $variantThreshold `
              -RubricDefinition $case.rubric
          }
      }
    }

    if ($ExperimentCase -in @('All', 'QueryCompression')) {
      $definition = $experiments.experiments | Where-Object id -eq 'QueryCompression'
      foreach ($variant in $definition.variants) {
        $variantTopK = [int]$variant.topK
        $variantThreshold = [double]$variant.similarityThreshold
        $variantCompressionEnabled = [bool]$variant.compressionEnabled
        Invoke-ApplicationRun -Deployment $experimentDeployment -TopK $variantTopK `
          -SimilarityThreshold $variantThreshold -CompressionEnabled $variantCompressionEnabled `
          -Evaluate {
            Invoke-TwoTurnChatEvaluation -Deployment $experimentDeployment `
              -ResultSuite 'QueryCompression' -CaseName $variant.case `
              -Turn1Question $variant.turn1Question -Turn2Question $variant.turn2Question `
              -TopK $variantTopK -SimilarityThreshold $variantThreshold `
              -ExpectedDocumentName $variant.expectedDocumentName
          }
      }
    }

    if ($ExperimentCase -in @('All', 'Reranking')) {
      $definition = $experiments.experiments | Where-Object id -eq 'Reranking'
      foreach ($variant in $definition.variants) {
        $variantQuestionCaseId = if ($null -ne $variant.questionCase) { $variant.questionCase } else { $definition.questionCase }
        $case = $questions.cases | Where-Object id -eq $variantQuestionCaseId
        $variantTopK = [int]$variant.topK
        $variantThreshold = [double]$variant.similarityThreshold
        $variantMmrEnabled = [bool]$variant.mmrEnabled
        $variantMmrFinalTopK = if ($null -ne $variant.mmrFinalTopK) { [int]$variant.mmrFinalTopK } else { $null }
        $variantMmrLambda = if ($null -ne $variant.mmrLambda) { [double]$variant.mmrLambda } else { $null }
        Invoke-ApplicationRun -Deployment $experimentDeployment -TopK $variantTopK `
          -SimilarityThreshold $variantThreshold -MmrEnabled $variantMmrEnabled `
          -MmrFinalTopK $variantMmrFinalTopK -MmrLambda $variantMmrLambda `
          -Evaluate {
            Invoke-ChatEvaluation -Deployment $experimentDeployment `
              -ResultSuite 'Reranking' -CaseName $variant.case -Question $case.question `
              -TopK $variantTopK -SimilarityThreshold $variantThreshold `
              -RubricDefinition $case.rubric
          }
      }
    }
  }

  if (-not $indexed) {
    throw 'No evaluation path indexed the policy corpus.'
  }
} catch {
  $runStatus = 'FAILED'
  $failure = New-SafeFailure -Exception $_.Exception
  $runException = $_.Exception
} finally {
  Stop-EvaluationApplication
  $httpClient.Dispose()
  Restore-ProcessEnvironmentVariable -Name 'AZURE_OPEN_AI_DEPLOYMENT_NAME' `
    -WasDefined $deploymentWasDefined -PreviousValue $previousDeployment
}

$modelScores = @($generatorDeployments | ForEach-Object {
  [ordered]@{
    deployment = $_
    score = $null
    status = 'NOT_PROVEN'
    notes = 'Agent review must assess all five current comparison responses against evaluation/questions.json before assigning 1-5.'
  }
})
$artifact = New-EvaluationArtifact -Status $runStatus -Collection $fixedCollection `
  -EmbeddingDeployment $fixedEmbeddingDeployment `
  -IngestionCount $(if ($indexed) { 1 } else { 0 }) -Results @($results) `
  -ModelScores $modelScores -ConfiguredDefaultDeployment $shippedGeneratorDeployment `
  -ResolvedDeployment $script:resolvedDeployment `
  -DeploymentEnvironmentVariable $script:deploymentEnvironmentVariableDuringRun -Failure $failure

$resolvedOutputPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($resolvedOutputPath)
if ($outputDirectory) {
  [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}
$artifactJson = ConvertTo-SanitizedJson -Value $artifact `
  -SecretValues @($env:AZURE_OPEN_AI_KEY)
[IO.File]::WriteAllText(
  $resolvedOutputPath,
  $artifactJson + [Environment]::NewLine,
  [Text.UTF8Encoding]::new($false))

if ($runException) {
  throw 'Live evaluation failed. A sanitized failure artifact was written; no live success is claimed.'
}

$artifactJson
