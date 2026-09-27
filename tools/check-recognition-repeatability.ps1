<#
Compare independent, sanitized ShiftWatch schema-8 diagnostic exports.
Purely offline and read-only. Different sessions are required for independent-scan comparisons.
Usage: .\tools\check-recognition-repeatability.ps1 scan1.json scan2.json [scan3.json ...]
#>
param(
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Paths
)
$ErrorActionPreference = 'Stop'
if ($Paths.Count -lt 2) { throw 'Supply at least two diagnostic JSON files.' }
$days = @('Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday')
$reports = @()
foreach ($path in $Paths) {
    $resolved = (Resolve-Path -LiteralPath $path -ErrorAction Stop).Path
    $data = Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json
    if ([int]$data.schemaVersion -lt 8) { throw "${path}: requires schema version 8 or newer" }
    if ($null -eq $data.viewerMarkers -or $null -eq $data.viewerMarkers.savedProfileDecisions) {
        throw "${path}: no saved-profile decisions present"
    }
    $reports += [pscustomobject]@{ Path = $resolved; Data = $data }
}
$sessionIds = @($reports | ForEach-Object { $_.Data.sessionId } | Select-Object -Unique)
$runIds = @($reports | ForEach-Object { $_.Data.viewerMarkers.recognitionRunId } | Select-Object -Unique)
Write-Host "Files: $($reports.Count); unique scan sessions: $($sessionIds.Count); unique recognition runs: $($runIds.Count)"
if ($sessionIds.Count -ne $reports.Count -or $runIds.Count -ne $reports.Count) {
    Write-Warning 'Some exports reuse the same scan session or recognition run: they are not all independent scans.'
}
foreach ($r in $reports) {
    Write-Host "$(Split-Path -Leaf $r.Path): app=$($r.Data.appVersion) session=$($r.Data.sessionId) run=$($r.Data.viewerMarkers.recognitionRunId) lifecycle=$($r.Data.viewerMarkers.recognitionLifecycle)"
}
$differences = 0
$base = $reports[0]
foreach ($other in @($reports | Select-Object -Skip 1)) {
    Write-Host "`nComparing $(Split-Path -Leaf $base.Path) against $(Split-Path -Leaf $other.Path)"
    foreach ($day in 0..6) {
        $a = @($base.Data.viewerMarkers.savedProfileDecisions | Where-Object { $_.weekdayColumn -eq $day }) | Select-Object -First 1
        $b = @($other.Data.viewerMarkers.savedProfileDecisions | Where-Object { $_.weekdayColumn -eq $day }) | Select-Object -First 1
        if ($null -eq $a -or $null -eq $b) {
            Write-Host "  $($days[$day]): missing weekday decision"
            $differences++
            continue
        }
        $fields = @('decision','candidateLineCount','scoredLineCount','topScore','runnerScore')
        foreach ($field in $fields) {
            if ("$($a.$field)" -cne "$($b.$field)") {
                Write-Host "  $($days[$day]) $($field): $($a.$field) -> $($b.$field)"
                $differences++
            }
        }
        foreach ($field in @('strictCount','looseObserved','looseAdded','eligibleOcrTokens','ocrMerged','ocrAdded',
                             'probesAttempted','probesNearExisting','probesInkRejected','probesAdded',
                             'rejectedHeight','rejectedBody','finalCandidates','finalOcrCandidates')) {
            $left = $a.candidatePipeline.$field
            $right = $b.candidatePipeline.$field
            if ("$left" -cne "$right") {
                Write-Host "  $($days[$day]) pipeline.$($field): $left -> $right"
                $differences++
            }
        }
        $leftDeciles = @($a.candidatePipeline.ocrTokenDeciles) -join ','
        $rightDeciles = @($b.candidatePipeline.ocrTokenDeciles) -join ','
        if ($leftDeciles -cne $rightDeciles) {
            Write-Host "  $($days[$day]) pipeline.ocrTokenDeciles: [$leftDeciles] -> [$rightDeciles]"
            $differences++
        }
        $leftBlocks = $a.candidatePipeline.survivorsByBlock | ConvertTo-Json -Compress
        $rightBlocks = $b.candidatePipeline.survivorsByBlock | ConvertTo-Json -Compress
        if ($leftBlocks -cne $rightBlocks) {
            Write-Host "  $($days[$day]) pipeline.survivorsByBlock: $leftBlocks -> $rightBlocks"
            $differences++
        }
    }
}
if ($differences -eq 0) {
    Write-Host 'RESULT: Compared decisions, scores and pipeline evidence match exactly.'
} else {
    Write-Host "RESULT: $differences observed differences. Use candidatePipeline stages to locate the divergence."
}
# A mismatch is diagnostic information, not a broken build. Do not fail CI on real-world OCR variability.
