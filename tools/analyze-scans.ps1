<#
Unified read-only diagnostic report for the reference rota, optionally saved as Markdown.
Does not assert that header dates or start times are correct. No raw OCR or names.
Usage: .\tools\analyze-scans.ps1 -Paths .\scan1.json,.\scan2.json -Output .\report.md
#>
param([Parameter(Mandatory=$true)][string[]]$Paths, [string]$Output)
$ErrorActionPreference = 'Stop'
if ($Paths.Count -lt 2) { throw 'Supply at least two independent diagnostic exports' }
$reports = @($Paths | ForEach-Object {
    $path = $_
    try { Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
    catch { throw "${path}: invalid or mixed JSON export. Re-export as a new file (ShiftWatch v20.8.15 or newer); do not concatenate confirmed examples with scan diagnostics. $($_.Exception.Message)" }
})
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
    $snapshot=($row | ForEach-Object { "$( $_.weekdayColumn):$($_.decision):$($_.topScore):$($_.candidatePipeline | ConvertTo-Json -Compress -Depth 10):$($_.productionByBlock | ConvertTo-Json -Compress -Depth 10):$($_.shadowByBlock | ConvertTo-Json -Compress -Depth 10):$($_.cropExperiments | ConvertTo-Json -Compress -Depth 10):$($_.shadowReplay | ConvertTo-Json -Compress -Depth 10)" }) -join '\n'
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
$lines += '', '## Thursday and Saturday block-level scoring (diagnostic only)', '',
    'These best/runner pairs are selected **inside** each physical block. They may not appear among the overall top three.',
    '', '| Scan | Day | Block 2 production best / runner / margin | Block 2 shadow best |', '|---|---|---|---|'
foreach($r in $reports) {
    foreach($day in @(3,5)) {
        $d=@($r.viewerMarkers.savedProfileDecisions | Where-Object { [int]$_.weekdayColumn -eq $day })[0]
        $prod=@($d.productionByBlock | Where-Object { [int]$_.physicalBlockIndex -eq 2 })
        $shadow=@($d.shadowByBlock | Where-Object { [int]$_.physicalBlockIndex -eq 2 })
        if ($prod.Count -gt 0) {
            $item=$prod[0]
            $runnerText=if ($null -ne $item.runner) { [math]::Round([double]$item.runner.adjustedScore,3) } else { 'none' }
            $marginText=if ($null -ne $item.scoreMargin) { [math]::Round([double]$item.scoreMargin,3) } else { 'n/a' }
            $production="$([math]::Round([double]$item.best.adjustedScore,3)) ($($item.best.candidateOrigin)) / $runnerText / $marginText ($($item.candidateCount) scored)"
        } else { $production='unavailable (requires schema 12)' }
        if ($shadow.Count -gt 0) { $shadowText="$([math]::Round([double]$shadow[0].best.adjustedScore,3)) ($($shadow[0].candidateCount) scored)" }
        else { $shadowText='none or unavailable' }
        $lines += "| $($r.sessionId.Substring(0,8)) | $($dayNames[$day]) | $production | $shadowText |"
    }
}
$lines += '', '## Cross-day selective-crop evaluation (schema 14; diagnostic only)', '',
    'All seven weekday columns are evaluated in physical block 2, including Friday OFF as an independent negative control. A qualifying crop is NOT an accepted shift.',
    '', '| Scan | Day | Candidate / origin | Original score / separation | Trim score / separation | Eligible | Qualifies |',
    '|---|---|---|---|---|---|---|'
foreach ($r in $reports) {
    foreach ($day in 0..6) {
        $d=@($r.viewerMarkers.savedProfileDecisions | Where-Object { [int]$_.weekdayColumn -eq $day })[0]
        if ([int]$r.schemaVersion -lt 14 -or -not $d.cropExperiments) {
            $lines += "| $($r.sessionId.Substring(0,8)) | $($dayNames[$day]) | unavailable | n/a | n/a | n/a | requires schema 14 |"
            continue
        }
        foreach ($item in $d.cropExperiments) {
            $original=@($item.variants | Where-Object { $_.variant -eq 'original' } | Select-Object -First 1)
            $trim=@($item.variants | Where-Object { $_.variant -eq 'trim_12' } | Select-Object -First 1)
            $origText=if($original.Count -gt 0) { "$([math]::Round([double]$original[0].adjustedScore,3)) / $([math]::Round([double]$original[0].rawSeparation,3))" } else { 'unavailable' }
            $trimText=if($trim.Count -gt 0) { "$([math]::Round([double]$trim[0].adjustedScore,3)) / $([math]::Round([double]$trim[0].rawSeparation,3))" } else { 'unavailable' }
            $lines += "| $($r.sessionId.Substring(0,8)) | $($dayNames[$day]) | $($item.candidateRankInBlock) / $($item.candidateOrigin) | $origText | $trimText | $($item.selectiveTrimEligible) | $($item.selectiveTrimQualifies) |"
        }
    }
}
$lines += '', '## Experimental gate summary (never production)', ''
foreach ($r in $reports) {
    if ([int]$r.schemaVersion -lt 14) { $lines += "- $($r.sessionId.Substring(0,8)): schema 14 unavailable"; continue }
    $rows=@($r.viewerMarkers.savedProfileDecisions | ForEach-Object { $_.cropExperiments })
    $eligible=@($rows | Where-Object { $_.selectiveTrimEligible }).Count
    $qualifying=@($rows | Where-Object { $_.selectiveTrimQualifies }).Count
    $off=@($r.viewerMarkers.savedProfileDecisions | Where-Object { [int]$_.weekdayColumn -eq 4 })[0]
    $offQualifying=@($off.cropExperiments | Where-Object { $_.selectiveTrimQualifies }).Count
    $lines += "- $($r.sessionId.Substring(0,8)): eligible $eligible; qualify $qualifying; Friday OFF qualifying $offQualifying"
}
$lines += '', 'An OFF control passing this experimental gate invalidates the proposed gate for production. A single OFF control passing is not the only possible failure: validate on other photographs and negative profiles.',
    'A gate-qualified crop is NOT an accepted shift. Normal production ranking, runner margin and confuser policies would still apply in any future trial.'
$lines += '', '## Shadow full-week decision replay (schema 15; hypothetical only)', '',
    'This section is a simulation, not an app suggestion. Check baseline parity before interpreting changes.', '',
    '| Scan | Day | Production | Replay | Winning block | Score / runner / margin | Trimmed winner | Baseline parity |',
    '|---|---|---|---|---:|---|---|---|'
foreach ($r in $reports) {
    foreach ($day in 0..6) {
        $d=@($r.viewerMarkers.savedProfileDecisions | Where-Object { [int]$_.weekdayColumn -eq $day })[0]
        $short=([string]$r.sessionId).Substring(0,[math]::Min(8,([string]$r.sessionId).Length))
        if ([int]$r.schemaVersion -lt 15 -or $null -eq $d.shadowReplay) {
            $lines += "| $short | $($dayNames[$day]) | $($d.decision) | unavailable | n/a | n/a | n/a | requires schema 15 |"
            continue
        }
        $replay=$d.shadowReplay
        $block=if ($null -ne $replay.replayWinnerBlock) { $replay.replayWinnerBlock } else { 'none' }
        $runner=if ($null -ne $replay.replayRunner) { [math]::Round([double]$replay.replayRunner,3) } else { 'none' }
        $margin=if ($null -ne $replay.replayMargin) { [math]::Round([double]$replay.replayMargin,3) } else { 'none' }
        $score=if ($null -ne $replay.replayScore) { [math]::Round([double]$replay.replayScore,3) } else { 'none' }
        $parity=if ($replay.baselineParity -eq $true) { 'PASS' } else { '**FAIL — disregard replay**' }
        $lines += "| $short | $($dayNames[$day]) | $($replay.originalStatus) | $($replay.replayStatus) | $block | $score / $runner / $margin | $($replay.trimmedWinner) | $parity |"
    }
}
$lines += '', 'Never interpret experimental shadow scores as accepted shift suggestions.'
$report=$lines -join "`n"
Write-Output $report
if ($Output) { [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Output),$report,[System.Text.UTF8Encoding]::new($false)) }
