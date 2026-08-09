param(
    [string]$OutputPath = ".\heart-pilot-deploy.tar.gz"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Push-Location $ProjectRoot
try {
    $Maven = if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) { "mvn.cmd" } else { ".\mvnw.cmd" }
    & $Maven clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Backend package failed" }

    if (-not (Test-Path .\heart-pilot-frontend\node_modules\.bin\vite.cmd)) {
        $NpmCache = Join-Path $ProjectRoot "tmp\npm-cache"
        npm.cmd --prefix .\heart-pilot-frontend --cache $NpmCache ci
        if ($LASTEXITCODE -ne 0) { throw "Frontend dependency install failed" }
    }

    npm.cmd --prefix .\heart-pilot-frontend run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend package failed" }

    $ResolvedOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
    tar.exe -czf $ResolvedOutputPath `
        target\heart-pilot-backend-0.0.1-SNAPSHOT.jar `
        Dockerfile.deploy `
        heart-pilot-frontend\dist `
        heart-pilot-frontend\Dockerfile.deploy `
        heart-pilot-frontend\nginx.conf `
        docker-compose.yml `
        docker-compose.deploy.yml `
        .env.example
    if ($LASTEXITCODE -ne 0) { throw "Deployment archive failed" }

    Write-Host "Deployment archive created: $ResolvedOutputPath"
} finally {
    Pop-Location
}
