<#
Unified read-only diagnostic report for the reference rota, optionally saved as Markdown.
Does not assert that header dates or start times are correct. No raw OCR or names.
Usage: .\tools\analyze-scans.ps1 -Paths .\scan1.json,.\scan2.json -Output .\report.md
#>
param([Parameter(Mandatory=$true)][string[]]$Paths, [string]$Output)
$ErrorActionPreference = 'Stop'
if ($Paths.Count -lt 2) { throw 'Supply at least two independent diagnostic exports' }
$reports = @($Paths | ForEach-Object { Get-Content -LiteralPath $_ -Raw | ConvertFrom-Json })
$ids = @($reports | ForEach-Object { $_.sessionId } | Select-Object -Unique)
$runs = @($reports | ForEach-Object { $_.viewerMarkers.recognitionRunId } | Select-Object -Unique)
if ($ids.Count -ne $reports.Count -or $runs.Count -ne $reports.Count) { throw 'Not independent scans: repeated session or run ID' }
$dayNames = @('Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday')
$baselineBlocks = @(0,2,2,2,-1,2,2)
$lines = @('# ShiftWatch sanitized scan analysis','','Diagnostic comparison only; start times and document week remain unverified.','',"Independent scans: $($reports.Count)", '')
$reference = $null; $drift = 0; $maxMs = 0; $minMs = [long]::MaxValue
foreach ($r in $reports) {
    if ([int]$r.schemaVersion -lt 9) { throw 'Requires schema 9 or newer' }
    $seconds = [math]::Round([double]$r.scanElapsedMs/1000,1)
    if ($r.scanElapsedMs -gt $maxMs) { $maxMs = $r.scanElapsedMs }
    if ($r.scanElapsedMs -lt $minMs) { $minMs = $r.scanElapsedMs }
    $hits=0; $fp=0
    $row=@($r.viewerMarkers.savedProfileDecisions | Sort-Object weekdayColumn)
    if ($row.Count -ne 7) { throw 'Expected seven weekday decisions' }
    foreach($d in $row) {
        $day=[int]$d.weekdayColumn; $accepted=([string]$d.decision -like 'accepted*')
        $block=$null; if (@($d.topCandidates).Count -gt 0) { $block=[int]$d.topCandidates[0].physicalBlockIndex }
        if ($baselineBlocks[$day] -eq -1) { if ($accepted) { $fp++ } }
        elseif ($accepted -and $block -eq $baselineBlocks[$day]) { $hits++ }
    }
    $lines += "- $($r.appVersion): $hits/6 location hits, $fp false suggestions, scan ${seconds}s"
    $snapshot=($row | ForEach-Object { "$( $_.weekdayColumn):$($_.decision):$($_.topScore):$($_.candidatePipeline | ConvertTo-Json -Compress -Depth 10)" }) -join '\n'
    if ($null -ne $reference -and $snapshot -cne $reference) { $drift++ }
    if ($null -eq $reference) { $reference=$snapshot }
}
$geometryStatus = 'Unavailable (requires schema 11 exports)'
if (@($reports | Where-Object { -not $_.ocrGeometryFingerprint }).Count -eq 0) {
    $fingerprints = @($reports | ForEach-Object { $_.ocrGeometryFingerprint } | Select-Object -Unique)
    if ($fingerprints.Count -eq 1) {
        $geometryStatus = 'Geometry inputs match; inspect OCR text, grouping or scorer if decisions drift'
    } else {
        $geometryStatus = 'Geometry inputs DIFFER; compare the per-x-bucket fingerprints below'
    }
}
$lines += '', "Decision/pipeline drift against first scan: $drift comparison(s)", "OCR geometry: $geometryStatus", "Duration spread: $([math]::Round(($maxMs-$minMs)/1000,1))s"
if ($geometryStatus -like '*DIFFER*') {
    $lines += '', '## OCR geometry fingerprints by x-bucket (approximate, not weekdays)', ''
    foreach ($i in 0..6) {
        $codes = @($reports | ForEach-Object { $_.ocrGeometryXBucketFingerprints[$i] })
        $status = if (@($codes | Select-Object -Unique).Count -eq 1) { 'same' } else { 'CHANGED' }
        $lines += "- Bucket ${i}: $status"
    }
}
$lines += '', '## Weekday identity evidence' , '', '| Day | Expected block | Decisions / top scores | Shadow OCR best (experimental only) |', '|---|---:|---|---|'
foreach($i in 0..6) {
    $cell=@(); $shadow=@()
    foreach($r in $reports) {
        $d=@($r.viewerMarkers.savedProfileDecisions | Where-Object { [int]$_.weekdayColumn -eq $i })[0]
        $cell += "$($d.decision) / $([math]::Round([double]$d.topScore,3))"
        if ($d.shadowOcr -and $d.shadowOcr.best) { $shadow += "block $($d.shadowOcr.best.physicalBlockIndex), score $([math]::Round([double]$d.shadowOcr.best.adjustedScore,3)) ($($d.shadowOcr.scoredCount) alternatives)" }
        else { $shadow += 'not available' }
    }
    $expected=if($baselineBlocks[$i] -eq -1){'off'}else{"$($baselineBlocks[$i])"}
    $lines += "| $($dayNames[$i]) | $expected | $($cell -join '; ') | $($shadow -join '; ') |"
}
$lines += '', '## Tuesday merged OCR evidence', ''
foreach ($r in $reports) {
    $t=@($r.viewerMarkers.savedProfileDecisions | Where-Object { $_.weekdayColumn -eq 1 })[0]
    $p=$t.candidatePipeline
    $added=if($p.ocrAddedByBlock.'2'){$p.ocrAddedByBlock.'2'}else{0}
    $lines += "- eligible in block 2: $($p.eligibleOcrTokensByBlock.'2'); merged: $($p.ocrMergedByBlock.'2'); added: $added; shadow originals scored: $($t.shadowOcr.scoredCount)"
}
$lines += '', 'Never interpret experimental shadow scores as accepted shift suggestions.'
$report=$lines -join "`n"
Write-Output $report
if ($Output) { [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Output),$report,[System.Text.UTF8Encoding]::new($false)) }
