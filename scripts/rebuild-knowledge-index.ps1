param(
  [string]$SourceDirectory = "../knowledge",
  [long]$UserId = 1
)

$ErrorActionPreference = "Stop"
$backendDirectory = Join-Path $PSScriptRoot "../heart-pilot-backend"
$absoluteSource = (Resolve-Path (Join-Path $backendDirectory $SourceDirectory)).Path

Push-Location $backendDirectory
try {
  $env:SPRING_PROFILES_ACTIVE = "knowledge-reindex"
  $env:KNOWLEDGE_SOURCE_DIRECTORY = $absoluteSource
  $env:KNOWLEDGE_REINDEX_USER_ID = "$UserId"
  & .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none"
  if ($LASTEXITCODE -ne 0) { throw "Knowledge index rebuild failed with exit code $LASTEXITCODE" }
} finally {
  Pop-Location
}
