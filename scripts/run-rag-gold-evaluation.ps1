# Run manual gold RAG evaluation against a published/frozen dataset snapshot.
# Usage:
#   cd D:\yuqiagent
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-rag-gold-evaluation.ps1 `
#     -WorkspacePublicId "1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668" `
#     -DatasetKey "ops-rag-v1" `
#     -DatasetVersion "dev-240" `
#     -RetryFromRun "1f18b27f-411b-6e32-adae-07226db09086"
#
# Exit codes:
#   0 = success
#   2 = quality regression vs baseline
#   3 = configuration error
#   4 = partial case failures

param(
    [Parameter(Mandatory = $true)]
    [string]$WorkspacePublicId,

    [Parameter(Mandatory = $false)]
    [string]$DatasetKey = "ops-rag-v1",

    [Parameter(Mandatory = $false)]
    [string]$DatasetVersion,

    [Parameter(Mandatory = $false)]
    [string]$DatasetPublicId,

    [Parameter(Mandatory = $false)]
    [string]$Split = "DEVELOPMENT",

    [Parameter(Mandatory = $false)]
    [string]$BaselineRunId,

    [Parameter(Mandatory = $false)]
    [string]$RetryFromRun,

    [Parameter(Mandatory = $false)]
    [string]$RetryFromFile,

    [Parameter(Mandatory = $false)]
    [ValidateSet("end-to-end", "retrieval-only")]
    [string]$Mode = "end-to-end",

    [Parameter(Mandatory = $false)]
    [string]$CaseKeysFile,

    [Parameter(Mandatory = $false)]
    [string]$EmbeddingCacheDir = "output/rag-gold-embedding-cache",

    [Parameter(Mandatory = $false)]
    [ValidateSet("on", "off")]
    [string]$Reranker = "off",

    [Parameter(Mandatory = $false)]
    [ValidateRange(1, 50)]
    [int]$RerankCandidateLimit = 30,

    [Parameter(Mandatory = $false)]
    [ValidateRange(0.0, 1.0)]
    [double]$RerankerRrfWeight = 0.0,

    [Parameter(Mandatory = $false)]
    [ValidateRange(0.0, 1.0)]
    [double]$RerankerDiversityPenalty = 0.0,

    [Parameter(Mandatory = $false)]
    [string]$OutputDir = "output/rag-gold-runs"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

# spring-boot:run 会加载完整 Security 上下文；未配置 jwt-secret 时 JwtTokenService 要求至少 32 字节
if (-not $env:INSIGHTFLOW_JWT_SECRET -or $env:INSIGHTFLOW_JWT_SECRET.Length -lt 32) {
    $env:INSIGHTFLOW_JWT_SECRET = "rag-gold-eval-local-dev-secret-32b-minimum"
}

# 每批实验在独立进程内显式冻结参数，避免调用方 shell 的环境变量漂移。
$env:KNOWLEDGE_RERANKER_CANDIDATE_LIMIT = [string]$RerankCandidateLimit
$env:KNOWLEDGE_RERANKER_RRF_WEIGHT = [string]$RerankerRrfWeight
$env:KNOWLEDGE_RERANKER_DIVERSITY_PENALTY = [string]$RerankerDiversityPenalty

if (-not $DatasetPublicId -and (-not $DatasetKey -or -not $DatasetVersion)) {
    Write-Error "Provide -DatasetKey and -DatasetVersion, or -DatasetPublicId"
    exit 3
}

$argsList = @(
    "--rag-gold-eval",
    "--workspace=$WorkspacePublicId",
    "--split=$Split",
    "--output-dir=$OutputDir"
)

if ($DatasetPublicId) {
    $argsList += "--dataset-public-id=$DatasetPublicId"
} else {
    $argsList += "--dataset-key=$DatasetKey"
    $argsList += "--dataset-version=$DatasetVersion"
}

if ($BaselineRunId) {
    $argsList += "--baseline-run-id=$BaselineRunId"
}

if ($RetryFromRun) {
    $argsList += "--retry-from-run=$RetryFromRun"
}

if ($RetryFromFile) {
    $argsList += "--retry-from-file=$RetryFromFile"
}

$argsList += "--mode=$Mode"

if ($CaseKeysFile) {
    $argsList += "--case-keys-file=$CaseKeysFile"
}

if ($Mode -eq "retrieval-only") {
    $argsList += "--embedding-cache-dir=$EmbeddingCacheDir"
}

$argsList += "--reranker=$Reranker"

$joinedArgs = $argsList -join " "
if ($Reranker -eq "on") {
    # 作为 Spring Boot 命令行属性传递，优先级高于本机环境变量，保证批次可复现。
    $joinedArgs = "$joinedArgs --insightflow.knowledge.reranker.candidate-limit=$($RerankCandidateLimit) --insightflow.knowledge.reranker.rrf-weight=$($RerankerRrfWeight) --insightflow.knowledge.reranker.diversity-penalty=$($RerankerDiversityPenalty)"
}
Write-Host "=== RAG Gold Evaluation ===" -ForegroundColor Cyan
Write-Host "Workspace: $WorkspacePublicId"
Write-Host "Dataset: $(if ($DatasetPublicId) { $DatasetPublicId } else { "$DatasetKey / $DatasetVersion" })"
Write-Host "Split: $Split"
Write-Host "Mode: $Mode"
Write-Host "Reranker: $Reranker"
if ($Reranker -eq "on") {
    Write-Host "Reranker selection: input=$RerankCandidateLimit rrf-weight=$RerankerRrfWeight diversity=$RerankerDiversityPenalty"
}
Write-Host "Output: $OutputDir"
if ($CaseKeysFile) { Write-Host "Case keys file: $CaseKeysFile" }
if ($BaselineRunId) { Write-Host "Baseline: $BaselineRunId" }
if ($RetryFromRun) { Write-Host "Retry failed from run: $RetryFromRun" -ForegroundColor Yellow }
if ($RetryFromFile) { Write-Host "Retry failed from file: $RetryFromFile" -ForegroundColor Yellow }
Write-Host ""

Write-Host "Running: .\mvnw.cmd -q spring-boot:run -Dspring-boot.run.arguments=`"$joinedArgs`"" -ForegroundColor DarkGray
& .\mvnw.cmd -q "spring-boot:run" "-Dspring-boot.run.arguments=$joinedArgs"
exit $LASTEXITCODE
