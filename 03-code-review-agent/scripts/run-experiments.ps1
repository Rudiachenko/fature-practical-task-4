<#
.SYNOPSIS
  Experiments & Edge Cases harness for Module 3 (context/TICKET.md's 8-row table, R13).

.DESCRIPTION
  Every mechanism this script can prove without a live model is proven by the module's own
  JUnit suite (Increments 1-7) - this script's -HermeticOnly mode re-runs exactly the cited
  test classes and reads their real Surefire results back out of target/surefire-reports, per
  experiment, rather than re-implementing the same checks a second time in PowerShell. It never
  fabricates a PASS: if a cited test method cannot be found in the Surefire report (e.g. the
  suite was never run, or a method was renamed), that experiment is reported UNKNOWN, not PASS.

  Without -HermeticOnly, the script additionally looks for real AZURE_OPEN_AI_KEY /
  AZURE_OPEN_AI_ENDPOINT / AZURE_OPEN_AI_DEPLOYMENT_NAME credentials in the process environment.
  If present, it attempts to build and start the application and exercise the live-evaluable
  experiments over real HTTP; if absent, it prints an explicit "SKIPPED - no credentials" line
  per experiment, with the exact manual command an operator needs, rather than silently omitting
  them or inventing a result. See 03-code-review-agent/evaluation/experiments.json for the full,
  structured citation/instruction set this script summarizes, and RESULTS.md for the narrative.

  Known environment limitation (see 03-code-review-agent/RUNBOOK.md and context/PROGRESS.md):
  on a Windows sandbox where java.nio.channels.Selector.open() cannot establish a loopback
  connection, embedded Tomcat cannot start at all (the exact failure already documented for
  HermeticApplicationContextIT). On such a host, the live mode below will fail to reach
  readiness even with real credentials; this is a host/JVM networking limitation, not a defect
  in this script or in the application.

.PARAMETER HermeticOnly
  Run only the hermetic (no live model, no network) portion: re-run the cited Surefire tests
  and summarize their real outcome per experiment. This is the default-safe mode for any
  sandbox without EPAM VPN/DIAL access.

.PARAMETER Port
  Local port to start the application on for the live portion (ignored under -HermeticOnly).

.PARAMETER ReadinessTimeoutSeconds
  How long to wait for the application to accept a TCP connection on -Port before giving up on
  the live portion (ignored under -HermeticOnly).

.PARAMETER SkipBuild
  Skip the `mvnw test`/`mvnw package` step and reuse whatever is already under target/ (useful
  for re-running this script immediately after a manual `mvnw` invocation).

.EXAMPLE
  .\03-code-review-agent\scripts\run-experiments.ps1 -HermeticOnly

.EXAMPLE
  $env:AZURE_OPEN_AI_KEY = '<dial-key>'
  $env:AZURE_OPEN_AI_ENDPOINT = 'https://ai-proxy.lab.epam.com'
  $env:AZURE_OPEN_AI_DEPLOYMENT_NAME = 'gpt-4o'
  .\03-code-review-agent\scripts\run-experiments.ps1
#>
param(
  [switch]$HermeticOnly,

  [ValidateRange(1024, 65535)]
  [int]$Port = 18083,

  [ValidateRange(10, 300)]
  [int]$ReadinessTimeoutSeconds = 60,

  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$moduleDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $moduleDir
$mvnw = Join-Path $repoRoot 'mvnw.cmd'
$surefireReportsDir = Join-Path $moduleDir 'target\surefire-reports'

# One entry per experiments.json id. `testCitations` are "ClassName::methodName" pairs this
# script cross-checks against the real Surefire XML output - the single source of truth for
# whether the hermetic mechanism actually passed in this run, not a hardcoded assumption.
$hermeticExperiments = [ordered]@{
  '2' = @{
    name = 'Ambiguous tool descriptions (regression guard only)'
    testCitations = @(
      'com.epam.codereviewagent.service.CodeReviewToolsTest::shouldHaveNonBlankDescriptionLongerThanTwentyCharacters_forEveryToolAnnotatedMethod'
    )
  }
  '3' = @{
    name = 'Missing convention'
    testCitations = @(
      'com.epam.codereviewagent.service.CodeReviewToolsTest::shouldReturnHonestNoConventionFoundMessageUnmodified_whenLanguageIsGo'
    )
  }
  '4' = @{
    name = 'Evidence enforcement'
    testCitations = @(
      'com.epam.codereviewagent.service.CodeReviewReactAgentTest::shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileNeverSucceedsButModelClaimsFindings',
      'com.epam.codereviewagent.service.CodeReviewReactAgentTest::shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileReturnsTheRealEmptyFileSentinelButModelClaimsFindings'
    )
  }
  '5' = @{
    name = 'Path traversal'
    testCitations = @(
      'com.epam.codereviewagent.controller.CodeReviewControllerTest::shouldReturnBadRequestWithPathSecurityViolationCode_whenUserInputAttemptsPathTraversal',
      'com.epam.codereviewagent.controller.CodeReviewControllerTest::shouldRejectBeforeInvokingTheAgent_whenUserInputIsAnAbsoluteWindowsPath',
      'com.epam.codereviewagent.util.RepositoryPathResolverTest::shouldRejectPathTraversal_whenValidateSecurityBoundaryIsCalledWithDotDotEscape',
      'com.epam.codereviewagent.util.RepositoryPathResolverTest::shouldRejectAbsolutePath_whenValidateSecurityBoundaryIsCalledWithAWindowsAbsolutePath'
    )
  }
  '6' = @{
    name = 'Large file / context overload (truncation)'
    testCitations = @(
      'com.epam.codereviewagent.util.FileUtilsTest::shouldTruncateContentAndAppendMarker_whenLimitIsSmallerThanFileLength',
      'com.epam.codereviewagent.service.CodeReviewToolsTest::shouldReturnContentWrappedInMarkersWithVisibleTruncationMarker_whenContentExceedsConfiguredMaxFileChars',
      'com.epam.codereviewagent.service.CodeReviewReactAgentTest::shouldSetTruncatedTrue_whenAnyToolResultContainsTruncationMarker_evenIfModelClaimsFalse'
    )
  }
  '7' = @{
    name = 'Loop non-termination'
    testCitations = @(
      'com.epam.codereviewagent.service.CodeReviewReactAgentTest::shouldThrowAgentIterationLimitExceededException_afterExactlyMaxIterationsModelCalls_whenModelRequestsToolCallsIndefinitely',
      'com.epam.codereviewagent.service.CodeReviewReactAgentTest::shouldThrowAgentIterationLimitExceededExceptionWithZeroChatModelCalls_whenMaxIterationsIsZero',
      'com.epam.codereviewagent.service.CodeReviewReactAgentTest::shouldThrowAgentIterationLimitExceededExceptionAfterExactlyOneChatModelCall_whenMaxIterationsIsOneAndModelRequestsATool',
      'com.epam.codereviewagent.config.CodeReviewPropertiesTest::shouldFailContextRefresh_whenMaxIterationsPropertyIsZero'
    )
  }
  '8' = @{
    name = 'Empty / non-code input'
    testCitations = @(
      'com.epam.codereviewagent.service.CodeReviewToolsTest::shouldReturnExplicitNonErrorEmptyFileMessage_whenFileExistsButIsEmpty',
      'com.epam.codereviewagent.service.CodeReviewReactAgentTest::shouldReturnEmptyFindingsAndHonestNoEvidenceReview_whenReadFileReturnsTheRealEmptyFileSentinelButModelClaimsFindings'
    )
  }
}

# Experiments 1 and 2's live half, plus 3/4/6/7/8's live half, cannot be automated here without
# either (a) temporarily modifying production code (experiment 1/2's dummy-tool/blank-description
# manipulation) or (b) genuine, non-deterministic model judgment this script cannot itself assess
# (whether a live answer is "graceful" or "honest"). These are documented, not automated.
$liveOnlyInstructions = [ordered]@{
  '1' = 'Requires temporarily adding 2-4 redundant/dummy @Tool methods to AgentConfig#chatOptions''s tool-callback list, then comparing a live run''s tool-call sequence against a baseline. See evaluation/experiments.json id=1.'
  '2' = 'Requires temporarily blanking/vague-ing an @Tool description (e.g. retrieveCodeConvention), then comparing a live run against a baseline, then reverting. See evaluation/experiments.json id=2.'
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

  # The XML report, not the plain-text one, is the source of truth for a single method's
  # outcome: Surefire's plain-text report only lists individual test names when something
  # failed/errored, so a passing class's report never mentions any method name at all and a
  # naive text search would misreport every passing method as UNKNOWN.
  $reportPath = Join-Path $surefireReportsDir "TEST-$ClassName.xml"
  if (-not (Test-Path $reportPath)) {
    return 'UNKNOWN (no Surefire XML report found - was the suite run?)'
  }
  [xml]$report = Get-Content -Raw -Path $reportPath
  $testcase = $report.testsuite.testcase | Where-Object { $_.name -eq $MethodName } | Select-Object -First 1
  if (-not $testcase) {
    return 'UNKNOWN (method not found in Surefire report - was it renamed or removed?)'
  }
  if ($testcase.failure -or $testcase.error) {
    return 'FAIL'
  }
  if ($testcase.skipped) {
    return 'SKIPPED'
  }
  return 'PASS'
}

function Invoke-HermeticExperiments {
  Write-Section 'Hermetic mechanisms (Experiments #2-#8 - no live model, no network)'

  if (-not $SkipBuild) {
    Write-Host "Running: $mvnw -pl 03-code-review-agent test" -ForegroundColor Yellow
    & $mvnw -pl 03-code-review-agent test
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
  }

  Write-Host ''
  Write-Host 'Experiment #1 (Tool overload) has no hermetic mechanism - live model reasoning is inherently required. See evaluation/experiments.json id=1.' -ForegroundColor Yellow
  Write-Host 'Experiment #5 (Path traversal) is fully closed above with zero model calls - there is no separate live half to run.' -ForegroundColor Green

  return $summary
}

function Test-LiveCredentialsPresent {
  return -not [string]::IsNullOrWhiteSpace($env:AZURE_OPEN_AI_KEY) `
    -and -not [string]::IsNullOrWhiteSpace($env:AZURE_OPEN_AI_ENDPOINT) `
    -and -not [string]::IsNullOrWhiteSpace($env:AZURE_OPEN_AI_DEPLOYMENT_NAME)
}

function Invoke-LivePortion {
  Write-Section 'Live model portion (Experiments #1, #2, plus the live half of #3/#4/#6/#7/#8, and R12)'

  if (-not (Test-LiveCredentialsPresent)) {
    Write-Host 'SKIPPED - no credentials: AZURE_OPEN_AI_KEY / AZURE_OPEN_AI_ENDPOINT / AZURE_OPEN_AI_DEPLOYMENT_NAME are not all set in this process environment.' -ForegroundColor Yellow
    Write-Host 'This is an honest skip, not a fabricated result. To run this portion, set real DIAL credentials (EPAM VPN required) and re-run this script without -HermeticOnly:' -ForegroundColor Yellow
    Write-Host '  $env:AZURE_OPEN_AI_KEY = ''<dial-key>'''
    Write-Host '  $env:AZURE_OPEN_AI_ENDPOINT = ''https://ai-proxy.lab.epam.com'''
    Write-Host '  $env:AZURE_OPEN_AI_DEPLOYMENT_NAME = ''gpt-4o'''
    Write-Host '  .\03-code-review-agent\scripts\run-experiments.ps1'
    foreach ($id in $liveOnlyInstructions.Keys) {
      Write-Host "  Experiment #${id}: $($liveOnlyInstructions[$id])"
    }
    Write-Host '  R12 (model comparison, gpt-4o / gpt-4.1-nano-2025-04-14 / gpt-5-mini-2025-08-07, 2 runs each): see evaluation/model-comparison-schema.json and RESULTS.md''s R12 section.'
    return
  }

  Write-Host 'Real DIAL credentials detected in the environment. Attempting to build and start the application...' -ForegroundColor Yellow
  Write-Host 'Known limitation: on a host where java.nio.channels.Selector.open() cannot establish a loopback connection, embedded Tomcat cannot start regardless of credentials - see this script''s own header and RUNBOOK.md.' -ForegroundColor Yellow

  if (-not $SkipBuild) {
    & $mvnw -pl 03-code-review-agent -DskipTests package
    if ($LASTEXITCODE -ne 0) {
      Write-Host 'Build failed - cannot start the application for the live portion. See the Maven output above.' -ForegroundColor Red
      return
    }
  }

  $jarPath = Get-ChildItem -Path (Join-Path $moduleDir 'target') -Filter '*-exec.jar' |
    Select-Object -First 1 -ExpandProperty FullName
  if (-not $jarPath) {
    Write-Host 'No executable JAR found under target/ - cannot start the application for the live portion.' -ForegroundColor Red
    return
  }

  $processArgs = @('-jar', $jarPath, "--server.port=$Port")
  $process = Start-Process -FilePath 'java' -ArgumentList $processArgs -PassThru -NoNewWindow `
    -RedirectStandardOutput (Join-Path $moduleDir 'target\live-experiments-stdout.log') `
    -RedirectStandardError (Join-Path $moduleDir 'target\live-experiments-stderr.log')

  try {
    $ready = $false
    $deadline = (Get-Date).AddSeconds($ReadinessTimeoutSeconds)
    while ((Get-Date) -lt $deadline -and -not $ready) {
      Start-Sleep -Seconds 2
      try {
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $tcpClient.Connect('127.0.0.1', $Port)
        $ready = $tcpClient.Connected
        $tcpClient.Close()
      } catch {
        $ready = $false
      }
    }

    if (-not $ready) {
      Write-Host "Application did not become ready on port $Port within $ReadinessTimeoutSeconds seconds - see target/live-experiments-std{out,err}.log. This is the expected outcome on a sandbox affected by the Selector.open() limitation documented above." -ForegroundColor Red
      return
    }

    Write-Host "Application is ready on port $Port. An operator should now exercise the live-only experiments manually (see instructions above) and record results in RESULTS.md." -ForegroundColor Green
    Write-Host "Example: Invoke-RestMethod -Method Post -Uri http://127.0.0.1:$Port/code-review -ContentType 'application/json' -Body '{\"userInput\":\"README.md\"}'"
  } finally {
    if ($process -and -not $process.HasExited) {
      Stop-Process -Id $process.Id -Force
    }
  }
}

$hermeticResults = Invoke-HermeticExperiments

if (-not $HermeticOnly) {
  Invoke-LivePortion
} else {
  Write-Section 'Live model portion'
  Write-Host '-HermeticOnly was specified: skipping the live portion entirely (Experiments #1, #2, R12, and the live halves of #3/#4/#6/#7/#8 are not attempted). Run without -HermeticOnly, with real DIAL credentials, to attempt them.' -ForegroundColor Yellow
}

Write-Section 'Summary'
$failCount = ($hermeticResults | Where-Object { $_.outcome -eq 'FAIL' }).Count
$unknownCount = ($hermeticResults | Where-Object { $_.outcome -like 'UNKNOWN*' }).Count
Write-Host "Hermetic citations checked: $($hermeticResults.Count); FAIL: $failCount; UNKNOWN: $unknownCount"
if ($failCount -gt 0 -or $unknownCount -gt 0) {
  Write-Host 'At least one cited test did not verifiably pass in this run - do not treat RESULTS.md''s existing narrative as re-confirmed until this is investigated.' -ForegroundColor Red
} else {
  Write-Host 'Every cited hermetic test verifiably passed in this run.' -ForegroundColor Green
}
