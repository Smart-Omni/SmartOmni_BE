# Dev only: build all jars on the host, then build and start the Docker stack from them
# (docker-compose.prebuilt.yml), so Maven never runs inside Docker.
#
#   .\scripts\up-dev.ps1                                      # whole stack
#   .\scripts\up-dev.ps1 order-service integration-service    # only these services
#   .\scripts\up-dev.ps1 -EnvFile D:\secrets\smartomni.env
#
# The Compose project is always "smartomni_platform" (-ProjectName to change it), so running from a git worktree
# reuses the same containers and database volume instead of starting an empty project named after the folder.
# Without -EnvFile, the .env of this checkout is used, else the .env of the main checkout (worktrees have none).
[CmdletBinding(PositionalBinding = $false)] # bare words are service names, never -ProjectName/-EnvFile
param(
    [string]$ProjectName = "smartomni_platform",
    [string]$EnvFile,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Services = @()
)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent

if ($EnvFile) {
    $EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
} else {
    $candidates = @(Join-Path $repoRoot ".env")
    $commonGitDir = git -C $repoRoot rev-parse --path-format=absolute --git-common-dir 2>$null
    if ($LASTEXITCODE -eq 0 -and $commonGitDir) {
        $candidates += Join-Path (Split-Path $commonGitDir -Parent) ".env"
    }
    $EnvFile = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $EnvFile) {
        throw "No .env found (looked in: $($candidates -join ', ')). Copy .env.example to .env or pass -EnvFile."
    }
}
Write-Host "Compose project: $ProjectName | env file: $EnvFile"

Push-Location $repoRoot
try {
    # Always clean: a stale, partial or duplicate *-exec.jar would otherwise be packaged as is.
    mvn -B -DskipTests clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed; Docker images were not rebuilt." }

    docker compose -p $ProjectName --env-file $EnvFile -f docker-compose.yml -f docker-compose.prebuilt.yml `
        up -d --build @Services
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed." }
} finally {
    Pop-Location
}
