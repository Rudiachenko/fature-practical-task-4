param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArguments
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$shortTempDirectory = 'C:\jtmp'
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
$exitCode = 1

New-Item -ItemType Directory -Path $shortTempDirectory -Force | Out-Null

Push-Location $repoRoot
try {
    # JDK selectors use TEMP/TMP to create their local wakeup socket on Windows.
    $env:TEMP = $shortTempDirectory
    $env:TMP = $shortTempDirectory

    & .\mvnw.cmd -pl 02-rag spring-boot:run @MavenArguments
    $exitCode = $LASTEXITCODE
}
finally {
    if ($null -eq $previousTemp) {
        Remove-Item Env:TEMP -ErrorAction SilentlyContinue
    }
    else {
        $env:TEMP = $previousTemp
    }

    if ($null -eq $previousTmp) {
        Remove-Item Env:TMP -ErrorAction SilentlyContinue
    }
    else {
        $env:TMP = $previousTmp
    }

    Pop-Location
}

exit $exitCode
