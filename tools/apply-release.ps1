<# Safe, optional patch dry-run/apply. Does not commit, push, reset or touch app data. #>
param([Parameter(Mandatory=$true)][string]$Patch,[switch]$Apply)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path '.\app\build.gradle.kts')) { throw 'Run from ShiftWatch repository root' }
$path=(Resolve-Path -LiteralPath $Patch -ErrorAction Stop).Path
# Derive the required base release from the patch, not from the installer's own version.
# That permits safe future upgrades using the same script without changing it each release.
$patchText = Get-Content -LiteralPath $path -Raw
$baseVersion = [regex]::Match($patchText, '(?m)^\-\s*versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $baseVersion) {
    throw 'Patch does not declare an expected base version; review it and use git apply --check manually.'
}
$current = Get-Content '.\app\build.gradle.kts' -Raw
$declaredVersion = [regex]::Match($current, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if ($declaredVersion -cne $baseVersion) {
    throw "Expected committed v$baseVersion base, found v$declaredVersion. No changes made."
}
$changes=@(& git status --porcelain)
if ($LASTEXITCODE -ne 0) { throw 'git status failed' }
if ($changes.Count -gt 0) { Write-Host ($changes -join "`n"); throw 'Dirty repository: commit, stash or review local changes first; nothing applied' }
& git apply --check -- "$path"
if ($LASTEXITCODE -ne 0) { throw 'Patch conflicts with this checkout; no changes made' }
& git apply --stat -- "$path"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect patch' }
if (-not $Apply) { Write-Host 'PASS: patch can apply. Re-run with -Apply after reviewing file list.'; exit 0 }
& git apply -- "$path"
if ($LASTEXITCODE -ne 0) { throw 'Patch application failed; inspect git status' }
& git diff --check
if ($LASTEXITCODE -ne 0) { throw 'Applied patch has whitespace errors; inspect git diff' }
Write-Host 'PASS: patch applied. Run dev-preflight.ps1 before committing.'
