param(
    [string]$Server = "82.157.205.6",
    [string]$RemoteUser = "ubuntu",
    [string]$RemoteDir = "/opt/heart-pilot",
    [string]$IdentityFile,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Archive = Join-Path $ProjectRoot "heart-pilot-deploy.tar.gz"
$EnvFile = Join-Path $ProjectRoot ".env"
$Target = "${RemoteUser}@${Server}"
$SshArgs = @()
$Sudo = if ($RemoteUser -eq "root") { "" } else { "sudo " }

if ($IdentityFile) {
    $ResolvedIdentity = (Resolve-Path $IdentityFile).Path
    $SshArgs += @("-i", $ResolvedIdentity)
}

if (-not (Test-Path $EnvFile)) {
    throw "Missing $EnvFile. Copy .env.example to .env and fill production secrets first."
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "package-deploy.ps1") -OutputPath $Archive
    if ($LASTEXITCODE -ne 0) { throw "Local package failed" }
} elseif (-not (Test-Path $Archive)) {
    throw "Missing $Archive; run without -SkipBuild first."
}

Write-Host "Preparing $Target`:$RemoteDir ..."
& ssh @SshArgs $Target "${Sudo}mkdir -p '$RemoteDir' && ${Sudo}chown '$RemoteUser' '$RemoteDir'"
if ($LASTEXITCODE -ne 0) { throw "Unable to prepare remote directory" }

& scp @SshArgs $Archive "${Target}:/tmp/heart-pilot-deploy.tar.gz"
if ($LASTEXITCODE -ne 0) { throw "Archive upload failed" }

& scp @SshArgs $EnvFile "${Target}:/tmp/heart-pilot.env"
if ($LASTEXITCODE -ne 0) { throw "Environment upload failed" }

$RemoteCommand = "set -e; tar -xzf /tmp/heart-pilot-deploy.tar.gz -C '$RemoteDir'; install -m 600 /tmp/heart-pilot.env '$RemoteDir/.env'; cd '$RemoteDir'; ${Sudo}docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d --build --remove-orphans; ${Sudo}docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps; rm -f /tmp/heart-pilot-deploy.tar.gz /tmp/heart-pilot.env"
& ssh @SshArgs $Target $RemoteCommand
if ($LASTEXITCODE -ne 0) { throw "Remote deployment failed" }

Write-Host "Deployment complete: http://$Server`:8081"
