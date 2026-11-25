# Dev only: build all jars on the host, then build and start the Docker stack from them
# (docker-compose.prebuilt.yml), so Maven never runs inside Docker.
# Extra arguments go to `docker compose up`, e.g.:
#   .\scripts\up-dev.ps1 order-service integration-service
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

# Always clean: a stale, partial or duplicate *-exec.jar would otherwise be packaged as is.
mvn -B -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed; Docker images were not rebuilt." }

docker compose -f docker-compose.yml -f docker-compose.prebuilt.yml up -d --build @args
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed." }
