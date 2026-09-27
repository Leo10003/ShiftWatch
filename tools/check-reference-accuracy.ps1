<#
Offline accuracy regression against the user-confirmed reference rota (week of 2026-09-07).
The diagnostic exports contain suggestions and block locations, NOT validated start times.
Usage: .\tools\check-reference-accuracy.ps1 .\scan1.json .\scan2.json
No Python and no modification to user data. Use only with exports of the same reference photo.
#>
param(
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Paths
)
$ErrorActionPreference = 'Stop'
$reference = Join-Path $PSScriptRoot '..\test-data\recognition\reference-september-07-2026-v2087.json'
$groundTruth = Get-Content -LiteralPath $reference -Raw | ConvertFrom-Json
$dayNames = @('Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday')
$previousSessions = @{}
$previousRuns = @{}
$failed = $false
foreach ($path in $Paths) {
    $report = Get-Content -LiteralPath (Resolve-Path -LiteralPath $path).Path -Raw | ConvertFrom-Json
    if ([int]$report.schemaVersion -lt 8) { throw "${path}: schema 8 or newer required" }
    $decisions = @($report.viewerMarkers.savedProfileDecisions)
    if ($decisions.Count -ne 7) { throw "${path}: expected seven weekday decisions" }
    if (-not $report.sessionId -or -not $report.viewerMarkers.recognitionRunId) {
        throw "${path}: missing independent-scan identity"
    }
    if ($previousSessions.ContainsKey([string]$report.sessionId) -or
        $previousRuns.ContainsKey([string]$report.viewerMarkers.recognitionRunId)) {
        Write-Warning "${path}: duplicated session/recognition ID; not an independent scan"
    }
    $previousSessions[[string]$report.sessionId] = $true
    $previousRuns[[string]$report.viewerMarkers.recognitionRunId] = $true
    $trueHits = 0
    $falseSuggestions = 0
    $misses = 0
    Write-Host "`n$(Split-Path -Leaf $path) - app $($report.appVersion), schema $($report.schemaVersion)"
    foreach ($i in 0..6) {
        $matches = @($decisions | Where-Object { [int]$_.weekdayColumn -eq $i })
        if ($matches.Count -ne 1) { throw "${path}: missing/duplicate weekday $i" }
        $entry = $matches[0]
        $expected = $groundTruth.expectedByWeekday."$i"
        $accepted = [string]$entry.decision -like 'accepted*'
        $top = @($entry.topCandidates | Select-Object -First 1)
        $block = if ($top.Count -gt 0) { $top[0].physicalBlockIndex } else { $null }
        $correct = $accepted -and ($null -ne $block) -and ([int]$block -eq [int]$expected.block)
        if (-not [bool]$expected.working) {
            if ($accepted) {
                $falseSuggestions++
                $failed = $true
                Write-Host "  $($dayNames[$i]): FALSE suggestion, block $block" -ForegroundColor Red
            } else { Write-Host "  $($dayNames[$i]): correctly no suggestion" }
        } elseif ($correct) {
            $trueHits++
            Write-Host "  $($dayNames[$i]): found in correct block $block (start time unverified)"
        } else {
            $misses++
            if ($i -ne 1) { $failed = $true }
            $candidate = if ($null -ne $entry.topScore) { [math]::Round([double]$entry.topScore, 3) } else { 'n/a' }
            Write-Host "  $($dayNames[$i]): MISSED (top score $candidate; known baseline miss only for Tuesday)" -ForegroundColor Yellow
        }
        if ($i -eq 1 -and $null -ne $entry.candidatePipeline -and
            $null -ne $entry.candidatePipeline.eligibleOcrTokensByBlock) {
            $pipe = $entry.candidatePipeline
            Write-Host "    Tuesday block 2: eligible=$($pipe.eligibleOcrTokensByBlock.'2') merged=$($pipe.ocrMergedByBlock.'2') added=$($pipe.ocrAddedByBlock.'2')"
        }
    }
    Write-Host "  Accuracy: $trueHits/6 expected working days; misses=$misses; false suggestions=$falseSuggestions"
    if ($trueHits -lt 5 -or $falseSuggestions -gt 0) { $failed = $true }
    if ($trueHits -eq 6 -and $falseSuggestions -eq 0) {
        Write-Host '  Tuesday recovered; evaluate independently on more photos before declaring general improvement.' -ForegroundColor Green
    }
    if ($report.header.authoritative -eq $false) {
        Write-Host '  Date: unverified fallback; do not count as correctly resolved.'
    }
}
if ($failed) {
    Write-Error 'REFERENCE REGRESSION: lost a previously stable working-day match, gained a false suggestion or changed physical block.'
    exit 1
}
Write-Host "`nPASS: No regression against the 5/6 reference identity-location baseline (start times not evaluated)." -ForegroundColor Green
