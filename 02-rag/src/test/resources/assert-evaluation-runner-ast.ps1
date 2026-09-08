param(
  [Parameter(Mandatory = $true)]
  [string]$RunnerPath
)

$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
  $RunnerPath,
  [ref]$tokens,
  [ref]$errors)
if ($errors.Count -gt 0) {
  throw 'Runner has PowerShell parse errors.'
}

$functions = @($ast.FindAll({
  param($node)
  $node -is [Management.Automation.Language.FunctionDefinitionAst]
}, $true))
$applicationRun = $functions | Where-Object Name -eq 'Invoke-ApplicationRun'
$startApplication = $functions | Where-Object Name -eq 'Start-EvaluationApplication'
$applicationTry = @($applicationRun.Body.FindAll({
  param($node)
  $node -is [Management.Automation.Language.TryStatementAst]
}, $true)) | Where-Object {
  $_.Body.Extent.Text -match 'Invoke-PolicyIngestion' -and
  $_.Finally.Extent.Text -match 'Stop-EvaluationApplication'
} | Select-Object -First 1

$rootCleanupTry = @($ast.FindAll({
  param($node)
  $node -is [Management.Automation.Language.TryStatementAst]
}, $true)) | Where-Object {
  $_.Finally.Extent.Text -match 'Stop-EvaluationApplication' -and
  $_.Finally.Extent.Text -match "Restore-ProcessEnvironmentVariable\s+-Name\s+'AZURE_OPEN_AI_DEPLOYMENT_NAME'" -and
  $_.Body.Extent.Text -match 'Invoke-ApplicationRun'
} | Select-Object -First 1

# The comparison loop iterates $comparisonDeployments, which resolves either to the full
# $generatorDeployments allow-list or, in configured-default mode, to the single shipped generator.
$generatorLoop = @($ast.FindAll({
  param($node)
  $node -is [Management.Automation.Language.ForEachStatementAst]
}, $true)) | Where-Object {
  $_.Extent.Text -match '\$comparisonDeployments'
} | Select-Object -First 1

$commands = @($ast.FindAll({
  param($node)
  $node -is [Management.Automation.Language.CommandAst]
}, $true))
$dedicatedIngestion = $commands | Where-Object {
  $_.GetCommandName() -eq 'Invoke-ApplicationRun' -and
  $_.Extent.Text -match '(?m)\s-Ingest(?:\s|`|$)'
} | Select-Object -First 1
$environmentAssignments = @($ast.FindAll({
  param($node)
  $node -is [Management.Automation.Language.AssignmentStatementAst]
}, $true)) | Where-Object {
  $_.Left.Extent.Text -in @(
    '$env:AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME',
    '$env:CHROMA_COLLECTION')
}

[ordered]@{
  parseErrorCount = $errors.Count
  applicationRunHasTryFinally = $null -ne $applicationTry
  ingestionBeforeEvaluationInRun = $null -ne $applicationTry -and
    $applicationTry.Body.Extent.Text.IndexOf('Invoke-PolicyIngestion') -lt
    $applicationTry.Body.Extent.Text.IndexOf('& $Evaluate')
  applicationCleanupIsInFinally = $null -ne $applicationTry -and
    $applicationTry.Finally.Extent.Text -match 'Stop-EvaluationApplication'
  rootCleanupAndRestoreAreInFinally = $null -ne $rootCleanupTry
  dedicatedIngestionExists = $null -ne $dedicatedIngestion
  dedicatedIngestionPrecedesGeneratorLoop = $null -ne $dedicatedIngestion -and
    $null -ne $generatorLoop -and
    $dedicatedIngestion.Extent.StartOffset -lt $generatorLoop.Extent.StartOffset
  generatorLoopCannotIngest = $null -ne $generatorLoop -and
    $generatorLoop.Extent.Text -notmatch 'Invoke-PolicyIngestion|(?m)\s-Ingest(?:\s|`|$)'
  fixedConfigIsPassedInsideStart = $null -ne $startApplication -and
    $startApplication.Body.Extent.Text -match '--spring.ai.azure.openai.embedding.options.deployment-name=\$fixedEmbeddingDeployment' -and
    $startApplication.Body.Extent.Text -match '--app.documents.processing.embedding-model=\$fixedEmbeddingDeployment' -and
    $startApplication.Body.Extent.Text -match '--app.vectorstore.chroma.collection-name=\$fixedCollection'
  fixedEmbeddingAndCollectionAreNeverAssigned = $environmentAssignments.Count -eq 0
} | ConvertTo-Json -Compress
