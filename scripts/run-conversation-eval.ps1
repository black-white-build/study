$ErrorActionPreference = "Stop"
$backendDirectory = Join-Path $PSScriptRoot "../heart-pilot-backend"
Push-Location $backendDirectory
try {
  & .\mvnw.cmd "-Dtest=LegacyBaselineReplayTest,DecisionAssistantEvaluationTest" test
  if ($LASTEXITCODE -ne 0) { throw "Conversation evaluation failed with exit code $LASTEXITCODE" }
} finally {
  Pop-Location
}
