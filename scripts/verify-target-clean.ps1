# Verify Maven target cleanup, process locks, and Knowledge.vue fix in static assets.
# Usage (IDEA Terminal):
#   cd D:\yuqiagent
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-target-clean.ps1

$ErrorActionPreference = "Continue"
$repoRoot = Split-Path -Parent $PSScriptRoot
$targetDir = Join-Path $repoRoot "target"
$staticDir = Join-Path $repoRoot "src\main\resources\static"
$knowledgeVue = Join-Path $repoRoot "frontend\src\views\Knowledge.vue"

Write-Host "=== InsightFlow build / lock check ===" -ForegroundColor Cyan
Write-Host "Repo: $repoRoot"
Write-Host ""

Write-Host "[1] target directory" -ForegroundColor Yellow
if (Test-Path $targetDir) {
    $targetItem = Get-Item $targetDir
    $childCount = (Get-ChildItem $targetDir -Recurse -File -ErrorAction SilentlyContinue | Measure-Object).Count
    Write-Host "  STATUS: EXISTS (mvn clean not complete or already recompiled)" -ForegroundColor Red
    Write-Host "  Path: $($targetItem.FullName)"
    Write-Host "  LastWrite: $($targetItem.LastWriteTime)"
    Write-Host "  File count: $childCount"
    foreach ($rel in @(
            "classes\com\insightflow\task\IncompleteProjectionRecovery.class",
            "classes\com\insightflow\service\analysis\WorkspaceProjectionExecutionService.class",
            "bootstrap-app.stderr.log")) {
        $p = Join-Path $targetDir $rel
        if (Test-Path $p) {
            $f = Get-Item $p
            Write-Host "  - present: $rel ($($f.Length) bytes)" -ForegroundColor DarkYellow
        } else {
            Write-Host "  - absent:  $rel" -ForegroundColor DarkGreen
        }
    }
} else {
    Write-Host "  STATUS: NOT FOUND (mvn clean succeeded)" -ForegroundColor Green
}
Write-Host ""

Write-Host "[2] Java processes that may lock target" -ForegroundColor Yellow
$blockers = @()
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    $cmd = $_.CommandLine
    if ($null -ne $cmd -and $cmd -match 'yuqiagent|insightflow|InsightFlowApplication|spring-boot:run|maven-wrapper') {
        $short = $cmd
        if ($short.Length -gt 160) { $short = $short.Substring(0, 160) + "..." }
        $blockers += [PSCustomObject]@{ PID = $_.ProcessId; Cmd = $short }
    }
}
if ($blockers.Count -eq 0) {
    Write-Host "  No related Java process found" -ForegroundColor Green
} else {
    Write-Host "  Found $($blockers.Count) process(es). Stop them before mvn clean:" -ForegroundColor Red
    $blockers | Format-Table -AutoSize
}
Write-Host ""

Write-Host "[3] Port listen (8080 backend / 5173 vite dev)" -ForegroundColor Yellow
foreach ($port in 8080, 8081, 5173) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        Write-Host "  Port $port : IN USE (PID $($conn.OwningProcess))" -ForegroundColor Red
    } else {
        Write-Host "  Port $port : free" -ForegroundColor Green
    }
}
Write-Host ""

Write-Host "[4b] target/classes static vs src (IDEA runs from target/classes)" -ForegroundColor Yellow
$targetStaticIndex = Join-Path $repoRoot "target\classes\static\index.html"
$srcStaticIndex = Join-Path $staticDir "index.html"
if ((Test-Path $targetStaticIndex) -and (Test-Path $srcStaticIndex)) {
    $targetEntry = (Get-Content $targetStaticIndex -Raw) -match 'src="/assets/(index-[^"]+\.js)"' | Out-Null; $targetJs = $Matches[1]
    $srcEntry = (Get-Content $srcStaticIndex -Raw) -match 'src="/assets/(index-[^"]+\.js)"' | Out-Null; $srcJs = $Matches[1]
    # Re-run match properly
    if ((Get-Content $targetStaticIndex -Raw) -match 'src="/assets/(index-[^"]+\.js)"') { $targetJs = $Matches[1] }
    if ((Get-Content $srcStaticIndex -Raw) -match 'src="/assets/(index-[^"]+\.js)"') { $srcJs = $Matches[1] }
    Write-Host "  src/main/resources/static: $srcJs"
    Write-Host "  target/classes/static:       $targetJs"
    if ($targetJs -eq $srcJs) {
        Write-Host "  Classpath static is in sync" -ForegroundColor Green
    } else {
        Write-Host "  STALE: IDEA/Spring Boot still serves OLD frontend bundle!" -ForegroundColor Red
        Write-Host "  Fix: cd D:\yuqiagent; .\mvnw.cmd compile   (after npm run build)" -ForegroundColor Red
    }
} elseif (Test-Path $srcStaticIndex) {
    Write-Host "  target/classes/static missing - run mvn compile after npm run build" -ForegroundColor Red
}
Write-Host ""

Write-Host "[5] Knowledge page freeze fix (source + static bundle)" -ForegroundColor Yellow
if (Test-Path $knowledgeVue) {
    $vue = Get-Content $knowledgeVue -Raw -Encoding UTF8
    if ($vue -match 'setAppendInput|:ref="el => setAppendInput') {
        Write-Host "  Source Knowledge.vue: OLD bug (setAppendInput) still present" -ForegroundColor Red
    } elseif ($vue -match "document\.createElement\('input'\)") {
        Write-Host "  Source Knowledge.vue: fix present (dynamic file input)" -ForegroundColor Green
    } else {
        Write-Host "  Source Knowledge.vue: cannot verify fix marker" -ForegroundColor DarkYellow
    }
} else {
    Write-Host "  Missing: $knowledgeVue" -ForegroundColor Red
}

$indexHtml = Join-Path $staticDir "index.html"
if (Test-Path $indexHtml) {
    $indexContent = Get-Content $indexHtml -Raw -Encoding UTF8
    if ($indexContent -match 'src="/assets/(index-[^"]+\.js)"') {
        Write-Host "  static/index.html entry: $($Matches[1])"
        $knowledgeChunks = Get-ChildItem (Join-Path $staticDir "assets") -Filter "Knowledge-*.js" -ErrorAction SilentlyContinue
        $staleChunks = @()
        $hasDynamicInput = $false
        foreach ($chunk in $knowledgeChunks) {
            $c = Get-Content $chunk.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            # Minified old bug: ref callback writes reactive ref (ref:a=>Y(id,a) + .value={...})
            if ($c -match 'setAppendInput|appendInputs|ref_for:!0,ref:a=>|ref:\s*\w+\s*=>\s*\w+\(\w+\.\w+,') {
                $staleChunks += $chunk.Name
            }
            if ($c -match "createElement\(""input""\)|createElement\('input'\)") {
                $hasDynamicInput = $true
            }
        }
        if ($staleChunks.Count -gt 0) {
            Write-Host "  Static chunks with OLD ref-loop bug: $($staleChunks -join ', ')" -ForegroundColor Red
            Write-Host "  Run: cd frontend; npm run build  (vite.config now uses emptyOutDir)" -ForegroundColor Red
        } elseif ($hasDynamicInput) {
            Write-Host "  Static Knowledge chunks: dynamic createElement('input') fix detected" -ForegroundColor Green
        } else {
            Write-Host "  Static Knowledge chunks: cannot confirm fix; rebuild recommended" -ForegroundColor DarkYellow
        }
        Write-Host "  Total Knowledge-*.js files on disk: $($knowledgeChunks.Count)"
    }
} else {
    Write-Host "  Missing static/index.html - run npm run build if using Spring Boot UI" -ForegroundColor Red
}
Write-Host ""

Write-Host "[6] Next steps" -ForegroundColor Yellow
if ($blockers.Count -gt 0) {
    Write-Host "  - Stop InsightFlow / spring-boot:run in IDEA (or kill PIDs above)"
}
if (Test-Path $targetDir) {
    Write-Host "  - mvn clean:  cd D:\yuqiagent; .\mvnw.cmd clean compile"
} else {
    Write-Host "  - target clean OK; compile: .\mvnw.cmd compile"
}
Write-Host "  - Rebuild frontend: cd D:\yuqiagent\frontend; npm run build"
Write-Host "  - Sync to classpath: cd D:\yuqiagent; .\mvnw.cmd compile"
Write-Host "  - Restart backend; hard refresh /knowledge (Ctrl+Shift+R)"
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Cyan
