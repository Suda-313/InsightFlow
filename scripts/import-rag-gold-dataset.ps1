# Import operational RAG gold datasets (dev-240, val-80, frozen-80) into Postgres.
# Requires: running Postgres + Redis, published knowledge corpus, Flyway V27 applied.
#
# Usage:
#   .\scripts\import-rag-gold-dataset.ps1
#   .\scripts\import-rag-gold-dataset.ps1 -Seed evaluation\rag\gold\seeds\ops-rag-v1-dev-240.json

param(
    [string[]]$Seed = @()
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not $env:INSIGHTFLOW_JWT_SECRET -or $env:INSIGHTFLOW_JWT_SECRET.Length -lt 32) {
    $env:INSIGHTFLOW_JWT_SECRET = "rag-gold-import-local-dev-secret-32b-min"
}

$mvnArgs = @(
    "-q",
    "spring-boot:run",
    "-Dspring-boot.run.profiles=local,rag-gold-import",
    "-Dspring-boot.run.jvmArguments=-Dspring.main.web-application-type=none"
)

if ($Seed.Count -gt 0) {
    $seedArgs = ($Seed | ForEach-Object { "--seed=$_" }) -join " "
    $mvnArgs += "-Dspring-boot.run.arguments=$seedArgs"
}

Write-Host "Importing RAG gold datasets from $Root ..."
& .\mvnw.cmd @mvnArgs

if ($LASTEXITCODE -ne 0) {
    Write-Error "RAG gold import failed with exit code $LASTEXITCODE"
}

Write-Host "RAG gold import finished."
