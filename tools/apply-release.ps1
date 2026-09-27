<# Safe, optional patch dry-run/apply. Does not commit, push, reset or touch app data. #>
param([Parameter(Mandatory=$true)][string]$Patch,[switch]$Apply)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path '.\app\build.gradle.kts')) { throw 'Run from ShiftWatch repository root' }
$version = Get-Content '.\app\build.gradle.kts' -Raw
if ($version -notmatch 'versionName\s*=\s*"20\.8\.8"') {
    throw 'Expected committed v20.8.8 base version. No changes made.'
}
$path=(Resolve-Path -LiteralPath $Patch -ErrorAction Stop).Path
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
