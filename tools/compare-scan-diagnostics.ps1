<#
Compare two or more sanitized ShiftWatch diagnostic JSON exports.
Does not upload files or require Python. Run in PowerShell from repository root:
  .\tools\compare-scan-diagnostics.ps1 "$env:USERPROFILE\Desktop\scan1.json" "$env:USERPROFILE\Desktop\scan2.json"
#>
param([Parameter(Mandatory=$true, ValueFromRemainingArguments=$true)][string[]]$Files)
$ErrorActionPreference = 'Stop'
if ($Files.Count -lt 2) { throw 'Supply at least two diagnostic JSON files from repeated scans of the same image and profile.' }
$days = @('Mon','Tue','Wed','Thu','Fri','Sat','Sun')
$reports = @()
foreach ($path in $Files) {
    $report = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    if ([int]$report.schemaVersion -lt 3) { throw "$path has no per-column recognition decisions (requires schema >= 3)." }
    if ($report.scanStage -ne 'COMPLETE') { Write-Warning "$path was exported before scan completion." }
    if ($report.viewerMarkers.status -ne 'observed_in_viewer') { Write-Warning "$path: open image viewer and wait for its matching pass before export." }
    $reports += [pscustomobject]@{ Name=(Split-Path -Leaf $path); Data=$report }
}
$base = $reports[0].Data.layout
foreach ($r in $reports) {
    $l = $r.Data.layout
    if ($l.imageWidth -ne $base.imageWidth -or $l.imageHeight -ne $base.imageHeight -or $l.ocrTokenCount -ne $base.ocrTokenCount) {
        Write-Warning "$($r.Name): image dimensions or OCR token count differ; compare score variability cautiously."
    }
    Write-Host "`n$($r.Name): v$($r.Data.appVersion), $($r.Data.scanElapsedMs) ms, $($r.Data.viewerMarkers.suggestedCount) suggestions"
}
Write-Host "`nSaved-profile matcher outcomes (the same source image/profile is required for fair comparison):"
foreach ($d in 0..6) {
    Write-Host "`n$($days[$d]):"
    foreach ($r in $reports) {
        $dec = @($r.Data.viewerMarkers.savedProfileDecisions | Where-Object { $_.weekdayColumn -eq $d }) | Select-Object -First 1
        if ($null -eq $dec) { Write-Host "  $($r.Name): no recorded saved-profile decision"; continue }
        $score = if ($null -eq $dec.topScore) { 'n/a' } else { [math]::Round([double]$dec.topScore,3).ToString('0.000') }
        $top = if ($null -eq $dec.topCandidates) { @() } else { @($dec.topCandidates) }
        $alternatives = if ($top.Count -gt 0) { ($top | ForEach-Object { "B$($_.physicalBlockIndex):$([math]::Round([double]$_.adjustedScore,3))" }) -join ', ' } else { 'not available (pre-v20.8.2)' }
        Write-Host "  $($r.Name): $($dec.decision), score=$score, scored=$($dec.scoredLineCount); top blocks=$alternatives"
    }
}
Write-Host "`nA changed accepted day or a large score difference does not by itself prove OCR instability."
Write-Host 'Compare source image, saved-profile training, candidate block ranks and preprocessing before tuning thresholds.'
