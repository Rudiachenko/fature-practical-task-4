# Displays the recorded build verification. Reads saved evidence only: it never runs Maven and
# never contacts a service. The record is resolved as the newest *-verification.json in this
# directory rather than by name, so regenerating the evidence for a new commit does not leave this
# script pointing at a file that no longer exists.
$ErrorActionPreference = 'Stop'

$recordPath = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*-verification.json' |
  Sort-Object Name -Descending | Select-Object -First 1
if (-not $recordPath) { throw 'No verification record found next to this script.' }
$record = Get-Content -Raw -LiteralPath $recordPath.FullName | ConvertFrom-Json

$logPath = Join-Path $PSScriptRoot $record.log
if (-not (Test-Path -LiteralPath $logPath)) { throw "The recorded log $($record.log) is missing." }
$lines = Get-Content -LiteralPath $logPath
if (-not ($lines -match '^\[INFO\] BUILD SUCCESS$')) {
  throw 'The referenced log does not contain BUILD SUCCESS.'
}
$actualHash = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $record.logSha256) { throw 'Verification log checksum mismatch.' }

Clear-Host
Write-Host 'Build & Test Verification' -ForegroundColor Cyan
Write-Host 'Saved Maven output - selected lines from a recorded run, not a new execution'
Write-Host ('Recorded command: ' + $record.command)
Write-Host ('Source commit:    ' + $record.sourceCommit)
Write-Host ('JDK:              ' + $record.toolchain)
Write-Host ''
$lines | Where-Object {
  $_ -match '^\[INFO\] --- (surefire:|failsafe:.*integration-test \(|jacoco:.*:(report|check) )' -or
  $_ -match '^\[(INFO|WARNING)\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$' -or
  $_ -match '^\[INFO\] (Analyzed bundle|All coverage checks|BUILD SUCCESS|Total time:|Finished at:)'
} | ForEach-Object { Write-Host $_ }
Write-Host ''
Write-Host ('JaCoCo LINE coverage: {0:F2}% ({1}/{2}); gate >= {3:P0} - {4}' -f
  (100 * $record.jacoco.lineCoverage), $record.jacoco.coveredLines, $record.jacoco.totalLines,
  $record.jacoco.minimumLineCoverage, $record.jacoco.gate) -ForegroundColor Green
Write-Host $record.scopeNote
Write-Host ('Full sanitized log: ' + $record.log)
