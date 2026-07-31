# Phase 4A: 同 v5 语料四组消融 × 四切片（retrieval-only）
# 用法:
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-phase4a-ablation.ps1
#
# 四组变体（entity+coverage 始终开启）:
#   p1-baseline      identifier=off subquota=off
#   p2-identifier    identifier=on  subquota=off
#   p3-subquota      identifier=off subquota=on
#   p2p3-full        identifier=on  subquota=on

param(
    [Parameter(Mandatory = $false)]
    [string]$WorkspacePublicId = "1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668",

    [Parameter(Mandatory = $false)]
    [string]$OutputRoot = "output/rag-gold-runs/phase4a",

    [Parameter(Mandatory = $false)]
    [string[]]$Variants = @("p1-baseline", "p2-identifier", "p3-subquota", "p2p3-full"),

    [Parameter(Mandatory = $false)]
    [string[]]$Slices = @("cross-dev-slice", "dev-fast-40", "dev-240", "val-80")
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$variantConfig = @{
    "p1-baseline"   = @{ Identifier = "off"; Subquota = "off" }
    "p2-identifier" = @{ Identifier = "on";  Subquota = "off" }
    "p3-subquota"   = @{ Identifier = "off"; Subquota = "on"  }
    "p2p3-full"     = @{ Identifier = "on";  Subquota = "on"  }
}

$sliceConfig = @{
    "cross-dev-slice" = @{
        DatasetKey      = "ops-rag-v1"
        DatasetVersion  = "dev-240"
        Split           = "DEVELOPMENT"
        CaseKeysFile    = "evaluation/rag/gold/slices/cross-dev-slice.txt"
    }
    "dev-fast-40" = @{
        DatasetKey      = "ops-rag-v1"
        DatasetVersion  = "dev-240"
        Split           = "DEVELOPMENT"
        CaseKeysFile    = "evaluation/rag/gold/slices/dev-fast-40.txt"
    }
    "dev-240" = @{
        DatasetKey      = "ops-rag-v1"
        DatasetVersion  = "dev-240"
        Split           = "DEVELOPMENT"
        CaseKeysFile    = $null
    }
    "val-80" = @{
        DatasetKey      = "ops-rag-v1"
        DatasetVersion  = "val-80"
        Split           = "VALIDATION"
        CaseKeysFile    = $null
    }
}

$results = @()
$total = $Variants.Count * $Slices.Count
$index = 0

foreach ($variant in $Variants) {
    if (-not $variantConfig.ContainsKey($variant)) {
        Write-Error "Unknown variant: $variant"
        exit 3
    }
    $v = $variantConfig[$variant]

    foreach ($slice in $Slices) {
        if (-not $sliceConfig.ContainsKey($slice)) {
            Write-Error "Unknown slice: $slice"
            exit 3
        }
        $index++
        $s = $sliceConfig[$slice]
        $outDir = Join-Path $OutputRoot "$variant/$slice"

        Write-Host ""
        Write-Host "=== [$index/$total] $variant @ $slice ===" -ForegroundColor Cyan

        $params = @{
            WorkspacePublicId = $WorkspacePublicId
            DatasetKey        = $s.DatasetKey
            DatasetVersion    = $s.DatasetVersion
            Split             = $s.Split
            Mode              = "retrieval-only"
            Identifier        = $v.Identifier
            Subquota          = $v.Subquota
            OutputDir         = $outDir
        }
        if ($s.CaseKeysFile) {
            $params.CaseKeysFile = $s.CaseKeysFile
        }

        & "$PSScriptRoot/run-rag-gold-evaluation.ps1" @params
        $exitCode = $LASTEXITCODE
        $results += [PSCustomObject]@{
            Variant  = $variant
            Slice    = $slice
            ExitCode = $exitCode
            OutDir   = $outDir
        }
        if ($exitCode -ne 0 -and $exitCode -ne 4) {
            Write-Warning "Run failed with exit code $exitCode for $variant/$slice"
        }
    }
}

Write-Host ""
Write-Host "=== Phase 4A ablation complete ===" -ForegroundColor Green
$results | Format-Table -AutoSize

$summaryPath = Join-Path $OutputRoot "run-manifest.json"
$results | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 $summaryPath
Write-Host "Manifest: $summaryPath"
