<#
.SYNOPSIS
  Experiments & Edge Cases harness for Module 4 (README.md's 8-row table, context/TICKET.md
  Requirement 13). Adapted from 03-code-review-agent/scripts/run-experiments.ps1.

.DESCRIPTION
  Every mechanism this script can prove without a live model/real PR is proven by the module's own
  JUnit suite - this script's -HermeticOnly mode (the default; see below) re-runs exactly the cited
  test classes and reads their real Surefire results back out of target/surefire-reports, per
  experiment, rather than re-implementing the same checks a second time in PowerShell. It never
  fabricates a PASS: if a cited test method cannot be found in the Surefire report (e.g. the suite
  was never run, or a method was renamed), that experiment is reported UNKNOWN, not PASS.

  This script deliberately runs `.\mvnw.cmd -pl 04-mcp-code-review-agent test` (Surefire, unit tests
  only) for the hermetic portion, per this increment's own explicit instruction - not `verify`. One
  consequence, disclosed here rather than silently: two experiments (#1 and #5) are additionally,
  partially proven by HermeticApplicationContextIT, an *IT.java-suffixed integration test that only
  Failsafe (`.\mvnw.cmd -pl 04-mcp-code-review-agent verify`) runs, not Surefire - this script does not
  re-verify that half and says so explicitly in its own output rather than silently omitting it.

  The live portion checks for AZURE_OPEN_AI_KEY / AZURE_OPEN_AI_ENDPOINT /
  AZURE_OPEN_AI_DEPLOYMENT_NAME **and** GITHUB_TOKEN in the process environment. If any are absent, it
  prints an honest "SKIPPED - no credentials" line naming exactly which ones are missing, plus the
  exact manual steps an operator needs for every REQUIRES_OPERATOR experiment (see
  evaluation/experiments.json). If all four are present, it prints clear, explicit manual
  next-step instructions (build the app, run it, POST a real PR reference) - it deliberately does
  **not** build, start, or POST anything itself. This is stricter than 03-code-review-agent's own
  script (which does build+start the app for a TCP-only readiness check with no real external
  dependency beyond Azure OpenAI): starting this application with a real GITHUB_TOKEN immediately
  attempts a real MCP handshake against GitHub's own remote server, a materially more
  externally-visible action than Module 3's Azure-only readiness probe, so this script treats
  "print clear manual instructions for what to do next" literally rather than auto-starting anything.
  It never POSTs to a real GitHub PR under any circumstance - that is the user's own step, per
  context/TICKET.md's explicit scope boundary (real, public, irreversible side effects).

.PARAMETER HermeticOnly
  Run only the hermetic (no live model, no network) portion: re-run the cited Surefire tests and
  summarize their real outcome per experiment. This is the default-safe mode for any sandbox without
  EPAM VPN/DIAL/GitHub access.

.PARAMETER SkipBuild
  Skip the `mvnw test` step and reuse whatever is already under target/surefire-reports (useful for
  re-running this script immediately after a manual `mvnw` invocation).

.EXAMPLE
  .\04-mcp-code-review-agent\scripts\run-experiments.ps1 -HermeticOnly

.EXAMPLE
  $env:AZURE_OPEN_AI_KEY = '<dial-key>'
  $env:AZURE_OPEN_AI_ENDPOINT = 'https://ai-proxy.lab.epam.com'
  $env:AZURE_OPEN_AI_DEPLOYMENT_NAME = 'gpt-4o'
  $env:GITHUB_TOKEN = '<github-personal-access-token>'
  .\04-mcp-code-review-agent\scripts\run-experiments.ps1
#>
param(
  [switch]$HermeticOnly,

  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$moduleDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $moduleDir
$mvnw = Join-Path $repoRoot 'mvnw.cmd'
$surefireReportsDir = Join-Path $moduleDir 'target\surefire-reports'

# One entry per experiments.json id whose hermeticStatus.status is HERMETICALLY_PROVEN.
# `testCitations` are "ClassName::methodName" pairs this script cross-checks against the real
# Surefire XML output - the single source of truth for whether the hermetic mechanism actually
# passed in this run, not a hardcoded assumption. Experiments 3, 6, 7 (NOT_APPLICABLE) and 8
# (ARCHITECTURAL_COMPARISON) have no hermetic test citations at all - see evaluation/experiments.json.
$hermeticExperiments = [ordered]@{
  '1' = @{
    name = 'Tool overload (local+MCP tool-callback merge mechanism only)'
    testCitations = @(
      'com.epam.codereview.config.AgentConfigTest::shouldMergeLocalAndMcpToolCallbacks_whenMcpToolsAreDiscovered'
    )
    partialNote = 'Additionally, partially confirmed through the real, fully-booted application context by HermeticApplicationContextIT#shouldAttachExactlyTheTwoLocalToolCallbacksAndZeroMcpToolCallbacks_whenTheRealContextIsBooted - an *IT.java test that only `mvnw verify` (Failsafe) runs, not re-verified by this script''s own `mvnw test` run.'
  }
  '2' = @{
    name = 'Ambiguous request (zero-signal sub-case only)'
    testCitations = @(
      'com.epam.codereview.util.PrReferenceResolverTest::shouldThrowPrReferenceNotFoundException_whenUserInputHasNoPrShapedSignal',
      'com.epam.codereview.util.PrReferenceResolverTest::shouldNotThrow_whenUserInputCarriesAtLeastOnePrShapedSignal',
      'com.epam.codereview.controller.CodeReviewControllerTest::shouldReturnBadRequestWithPrReferenceNotFoundCode_whenUserInputHasNoPrSignal'
    )
  }
  '4' = @{
    name = 'Large PR (iteration-limit guard + AI-provider-failure mapping)'
    testCitations = @(
      'com.epam.codereview.service.CodeReviewReactAgentTest::shouldThrowAgentIterationLimitExceededException_afterExactlyMaxIterationsChatModelCalls_whenModelRequestsToolCallsIndefinitely',
      'com.epam.codereview.service.CodeReviewReactAgentTest::shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsZero',
      'com.epam.codereview.service.CodeReviewReactAgentTest::shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsNegative',
      'com.epam.codereview.service.CodeReviewReactAgentTest::shouldThrowAgentIterationLimitExceededExceptionAfterExactlyOneChatModelCall_whenMaxIterationsIsOneAndModelRequestsATool',
      'com.epam.codereview.controller.CodeReviewControllerTest::shouldReturnInternalServerErrorWithIterationLimitCode_whenAgentExhaustsMaxIterations',
      'com.epam.codereview.config.CodeReviewPropertiesTest::shouldFailContextRefresh_whenMaxIterationsPropertyIsZero',
      'com.epam.codereview.exception.CodeReviewExceptionHandlerTest::shouldReturnBadGatewayWithStaticMessage_whenTransientAiExceptionIsThrown',
      'com.epam.codereview.exception.CodeReviewExceptionHandlerTest::shouldReturnBadGatewayWithStaticMessage_whenNonTransientAiExceptionIsThrown'
    )
  }
  '5' = @{
    name = 'MCP unavailability (clean MCP-disabled boot only - not a genuinely bad endpoint)'
    testCitations = @(
      'com.epam.codereview.config.AgentConfigTest::shouldAttachExactlyTheTwoLocalToolCallbacks_whenNoMcpClientsAreConfigured',
      'com.epam.codereview.config.McpToolDiscoveryLoggerTest::shouldLogZeroToolsWithAnEmptyNameList_whenNoMcpClientsAreConfigured'
    )
    partialNote = 'Additionally, partially confirmed through the real, fully-booted application context by all 4 HermeticApplicationContextIT test methods - an *IT.java test class that only `mvnw verify` (Failsafe) runs, not re-verified by this script''s own `mvnw test` run.'
  }
}

$noHermeticMechanism = [ordered]@{
  '3' = 'Invalid / inaccessible PR - no test in this module constructs a genuinely failing/erroring MCP tool call; the claimed absorption behavior is Spring AI framework reasoning from planning, not exercised here. See evaluation/experiments.json id=3.'
  '6' = 'Line anchoring - no local anchoring logic exists in this module to test; anchoring is entirely delegated to the model''s own tool-call arguments and GitHub''s own rendering. See evaluation/experiments.json id=6.'
  '7' = 'Hallucinated tool - no test constructs a request for a nonexistent tool name; this module''s actual recovery behavior is genuinely unverified, not merely unexercised. See evaluation/experiments.json id=7.'
  '8' = 'Local vs MCP comparison - an architectural comparison, not something a test proves or a live run measures. See evaluation/RESULTS.md''s own comparison section; needs neither a hermetic test nor a live run.'
}

function Write-Section {
  param([string]$Title)
  Write-Host ''
  Write-Host "=== $Title ===" -ForegroundColor Cyan
}

function Get-SurefireTestOutcome {
  param(
    [Parameter(Mandatory = $true)] [string]$ClassName,
    [Parameter(Mandatory = $true)] [string]$MethodName
  )

  # The XML report, not the plain-text one, is the source of truth for a single method's outcome:
  # Surefire's plain-text report only lists individual test names when something failed/errored, so
  # a passing class's report never mentions any method name at all and a naive text search would
  # misreport every passing method as UNKNOWN.
  $reportPath = Join-Path $surefireReportsDir "TEST-$ClassName.xml"
  if (-not (Test-Path $reportPath)) {
    return 'UNKNOWN (no Surefire XML report found - was the suite run?)'
  }
  [xml]$report = Get-Content -Raw -Path $reportPath
  # A plain @Test method's <testcase name="..."> matches $MethodName exactly. A @ParameterizedTest
  # method instead produces one <testcase> per invocation, named "MethodName(ParamTypes)[N]" - match
  # every such invocation by prefix too and aggregate their outcomes, rather than only the bare name,
  # which would otherwise always report UNKNOWN for a parameterized method even after every one of
  # its invocations passed (several of this module's own citations, e.g. PrReferenceResolverTest's
  # accept/reject cases, are @ParameterizedTest methods).
  $testcases = @($report.testsuite.testcase | Where-Object {
    $_.name -eq $MethodName -or $_.name -like "$MethodName(*"
  })
  if ($testcases.Count -eq 0) {
    return 'UNKNOWN (method not found in Surefire report - was it renamed or removed?)'
  }
  if ($testcases | Where-Object { $_.failure -or $_.error }) {
    return 'FAIL'
  }
  if ($testcases | Where-Object { $_.skipped }) {
    return 'SKIPPED'
  }
  return 'PASS'
}

function Invoke-HermeticExperiments {
  Write-Section 'Hermetic mechanisms (Experiments #1, #2, #4, #5 - no live model, no network, no real PR)'

  if (-not $SkipBuild) {
    Write-Host "Running: $mvnw -pl 04-mcp-code-review-agent test" -ForegroundColor Yellow
    & $mvnw -pl 04-mcp-code-review-agent test
    if ($LASTEXITCODE -ne 0) {
      Write-Host 'mvnw test reported a non-zero exit code - see the Maven output above for the real failure before trusting any PASS below.' -ForegroundColor Red
    }
  } else {
    Write-Host 'Skipping build (-SkipBuild); reading whatever Surefire reports already exist under target/surefire-reports.' -ForegroundColor Yellow
  }

  $summary = @()
  foreach ($id in $hermeticExperiments.Keys) {
    $experiment = $hermeticExperiments[$id]
    Write-Host ''
    Write-Host "Experiment #${id}: $($experiment.name)"
    foreach ($citation in $experiment.testCitations) {
      $parts = $citation -split '::'
      $className = $parts[0]
      $methodName = $parts[1]
      $outcome = Get-SurefireTestOutcome -ClassName $className -MethodName $methodName
      $color = if ($outcome -eq 'PASS') { 'Green' } elseif ($outcome -eq 'FAIL') { 'Red' } else { 'Yellow' }
      Write-Host "  [$outcome] $className#$methodName" -ForegroundColor $color
      $summary += [ordered]@{
        experimentId = $id
        experimentName = $experiment.name
        testClass = $className
        testMethod = $methodName
        outcome = $outcome
      }
    }
    if ($experiment.partialNote) {
      Write-Host "  NOTE: $($experiment.partialNote)" -ForegroundColor Yellow
    }
  }

  Write-Host ''
  foreach ($id in $noHermeticMechanism.Keys) {
    Write-Host "Experiment #${id}: $($noHermeticMechanism[$id])" -ForegroundColor Yellow
  }

  return $summary
}

function Test-LiveCredentialsPresent {
  $missing = @()
  if ([string]::IsNullOrWhiteSpace($env:AZURE_OPEN_AI_KEY)) { $missing += 'AZURE_OPEN_AI_KEY' }
  if ([string]::IsNullOrWhiteSpace($env:AZURE_OPEN_AI_ENDPOINT)) { $missing += 'AZURE_OPEN_AI_ENDPOINT' }
  if ([string]::IsNullOrWhiteSpace($env:AZURE_OPEN_AI_DEPLOYMENT_NAME)) { $missing += 'AZURE_OPEN_AI_DEPLOYMENT_NAME' }
  if ([string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) { $missing += 'GITHUB_TOKEN' }
  return $missing
}

function Invoke-LivePortion {
  Write-Section 'Live model / live PR portion (Experiments #1, #2, #3, #4, #5, #6, #7, plus Requirement 12)'

  $missing = Test-LiveCredentialsPresent
  if ($missing.Count -gt 0) {
    Write-Host "SKIPPED - no credentials: the following are not all set in this process environment: $($missing -join ', ')." -ForegroundColor Yellow
    Write-Host 'This is an honest skip, not a fabricated result. To see the manual next-step instructions, set all four of:' -ForegroundColor Yellow
    Write-Host '  $env:AZURE_OPEN_AI_KEY = ''<dial-key>'''
    Write-Host '  $env:AZURE_OPEN_AI_ENDPOINT = ''https://ai-proxy.lab.epam.com'''
    Write-Host '  $env:AZURE_OPEN_AI_DEPLOYMENT_NAME = ''gpt-4o'''
    Write-Host '  $env:GITHUB_TOKEN = ''<github-personal-access-token-with-pr-read-write-scope>'''
    Write-Host '  .\04-mcp-code-review-agent\scripts\run-experiments.ps1'
    Write-Host 'See evaluation/experiments.json for every REQUIRES_OPERATOR experiment''s exact manual steps, and evaluation/RESULTS.md''s Requirement 12 section for the 3-model Subtask.'
    return
  }

  Write-Host 'All four credentials detected in the environment (AZURE_OPEN_AI_KEY, AZURE_OPEN_AI_ENDPOINT, AZURE_OPEN_AI_DEPLOYMENT_NAME, GITHUB_TOKEN).' -ForegroundColor Green
  Write-Host ''
  Write-Host 'This script deliberately does NOT itself build, start the application, or POST anything to a real GitHub PR - that is your own next step, per context/TICKET.md''s explicit scope boundary (real, public, irreversible side effects). Manual next steps:' -ForegroundColor Yellow
  Write-Host ''
  Write-Host '  1. Build:  .\mvnw.cmd -pl 04-mcp-code-review-agent -DskipTests package'
  Write-Host '  2. Run:    .\mvnw.cmd -pl 04-mcp-code-review-agent spring-boot:run'
  Write-Host '             (see RUNBOOK.md''s Windows host note if embedded Tomcat fails to start)'
  Write-Host '  3. POST a real PR reference you control, e.g.:'
  Write-Host '     Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/code-review -ContentType ''application/json'' -Body ''{"userInput":"https://github.com/<owner>/<repo>/pull/<number>"}'''
  Write-Host '  4. Observe the response, the server''s per-iteration/per-tool-call INFO logs, and the actual inline comments + summary posted to that real PR.'
  Write-Host '  5. Record what happened in evaluation/RESULTS.md, replacing each REQUIRES_OPERATOR entry you exercised with what was actually observed.'
  Write-Host ''
  Write-Host 'For the specific manipulation each REQUIRES_OPERATOR experiment needs (e.g. Experiment #1''s system-prompt tool-list edit, Experiment #7''s hallucinated-tool-name edit), see evaluation/experiments.json''s own liveEvaluation.instructions per id.'
  Write-Host 'For the required 3-model Subtask (Requirement 12), see evaluation/RESULTS.md''s own section - it must be run once per deployment name (gpt-4o, gpt-4.1-nano-2025-04-14, gpt-5-mini-2025-08-07) against the same real PR.'
}

$hermeticResults = Invoke-HermeticExperiments

if (-not $HermeticOnly) {
  Invoke-LivePortion
} else {
  Write-Section 'Live model / live PR portion'
  Write-Host '-HermeticOnly was specified: skipping the live portion entirely. Run without -HermeticOnly to see whether live credentials are present and, if so, the manual next-step instructions.' -ForegroundColor Yellow
}

Write-Section 'Summary'
$failCount = ($hermeticResults | Where-Object { $_.outcome -eq 'FAIL' }).Count
$unknownCount = ($hermeticResults | Where-Object { $_.outcome -like 'UNKNOWN*' -or $_.outcome -eq 'SKIPPED' }).Count
$sourceNote = if ($SkipBuild) { 'in the existing Surefire reports (this run did not itself execute mvnw test - see -SkipBuild)' } else { 'in this run' }
Write-Host "Hermetic citations checked: $($hermeticResults.Count); FAIL: $failCount; UNKNOWN: $unknownCount"
if ($failCount -gt 0 -or $unknownCount -gt 0) {
  Write-Host 'At least one cited test did not verifiably pass - do not treat RESULTS.md''s existing narrative as re-confirmed until this is investigated.' -ForegroundColor Red
} else {
  Write-Host "Every cited hermetic test verifiably passed $sourceNote." -ForegroundColor Green
}
Write-Host 'No live experiment or the Requirement 12 Subtask was executed by this script - see the live portion output above.' -ForegroundColor Yellow
