# Republish all knowledge documents from docs/knowledge-sources (expire + delete old versions, upload + publish).
# Usage:
#   cd D:\yuqiagent
#   powershell -ExecutionPolicy Bypass -File .\scripts\republish-knowledge-corpus.ps1 `
#     -WorkspacePublicId "1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668"

param(
    [Parameter(Mandatory = $true)]
    [string]$WorkspacePublicId
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not $env:INSIGHTFLOW_JWT_SECRET -or $env:INSIGHTFLOW_JWT_SECRET.Length -lt 32) {
    $env:INSIGHTFLOW_JWT_SECRET = "rag-gold-eval-local-dev-secret-32b-minimum"
}

Write-Host "=== Knowledge Corpus Republish ===" -ForegroundColor Cyan
Write-Host "Workspace: $WorkspacePublicId"
Write-Host ""

& .\mvnw.cmd -q "spring-boot:run" "-Dspring-boot.run.profiles=local,knowledge-republish" "-Dspring-boot.run.arguments=--workspace=$WorkspacePublicId"
exit $LASTEXITCODE
