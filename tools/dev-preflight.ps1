<#
Fast, read-only checks before pushing a ShiftWatch change.
Optional -Patch validates an external patch without applying it.
Run from the existing repository root; does not alter user data or Git state.
#>
param([string]$Patch, [switch]$Android, [string[]]$Diagnostics)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path '.\app\build.gradle.kts') -or -not (Test-Path '.\tools\verify_regression_cases.py')) {
    throw 'Run this script from the ShiftWatch repository root.'
}
& git diff --check
if ($LASTEXITCODE -ne 0) { throw 'git diff --check failed' }
if ($Patch) {
    & git apply --check $Patch
    if ($LASTEXITCODE -ne 0) { throw 'Patch failed git apply --check; nothing applied' }
}
$parseFailures = @()
$scriptFiles = @(Get-ChildItem '.\tools' -Filter '*.ps1' -File)
foreach ($file in $scriptFiles) {
    $tokens = $null; $issues = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$issues)
    if ($issues) { $parseFailures += $issues }
}
if ($parseFailures.Count -gt 0) {
    $parseFailures | Format-Table Extent, Message -Wrap
    throw 'One or more PowerShell scripts have syntax errors.'
}
# A Windows Store app-execution alias can exist even when Python is not installed.
# Probe the interpreter, not merely whether its command appears on PATH.
$pythonRunner = $null
if (Get-Command py -ErrorAction SilentlyContinue) {
    try {
        & py -3 -c 'import sys' 2>$null
        if ($LASTEXITCODE -eq 0) { $pythonRunner = 'py' }
    } catch { }
}
if (-not $pythonRunner -and (Get-Command python -ErrorAction SilentlyContinue)) {
    try {
        & python -c 'import sys' 2>$null
        if ($LASTEXITCODE -eq 0) { $pythonRunner = 'python' }
    } catch { }
}
if ($pythonRunner -eq 'py') {
    & py -3 '.\tools\verify_regression_cases.py'
    if ($LASTEXITCODE -ne 0) { throw 'Regression metadata validation failed' }
    & py -3 '.\tools\verify-recognition-baseline.py'
    if ($LASTEXITCODE -ne 0) { throw 'Sanitized recognition baseline validation failed' }
    Write-Host 'PASS: regression metadata validation.'
} elseif ($pythonRunner -eq 'python') {
    & python '.\tools\verify_regression_cases.py'
    if ($LASTEXITCODE -ne 0) { throw 'Regression metadata validation failed' }
    & python '.\tools\verify-recognition-baseline.py'
    if ($LASTEXITCODE -ne 0) { throw 'Sanitized recognition baseline validation failed' }
    Write-Host 'PASS: regression metadata validation.'
} else {
    Write-Warning 'No working Python interpreter found; optional regression metadata validation skipped. CI will run it.'
}
Write-Host 'PASS: Git whitespace and PowerShell syntax checks.'
Write-Host 'Full Android compilation must pass in GitHub Actions unless independently built locally.'
# The tracked fixture is metadata, NOT a replacement for photographs or image-based OCR tests.
$fixture = Get-Content '.\test-data\recognition\v2088-sanitized-decision-baseline.json' -Raw | ConvertFrom-Json
if ($fixture.baseline.Count -ne 7 -or
    @($fixture.baseline | Where-Object { $_.decision -like 'accepted*' }).Count -ne 5 -or
    @($fixture.baseline | Where-Object { $_.weekdayColumn -eq 4 -and $_.decision -like 'accepted*' }).Count -ne 0) {
    throw 'Sanitized recognition baseline is invalid'
}
Write-Host 'PASS: sanitized reference metadata (5/6, Friday protected).'
if (@($Diagnostics | Where-Object { $null -ne $_ }).Count -gt 0) {
    & "$PSScriptRoot\analyze-scans.ps1" -Paths $Diagnostics
    if ($LASTEXITCODE -ne 0) { throw 'Diagnostic comparison failed' }
}
if ($Android) {
    if (-not (Test-Path '.\gradlew.bat')) { throw 'Missing Gradle wrapper' }
    & '.\gradlew.bat' --no-daemon :app:testDebugUnitTest :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android checks failed' }
    Write-Host 'PASS: local Android tests and lint.'
} else {
    Write-Host 'SKIP: optional Android Gradle tests. Pass -Android when SDK and JDK are installed.'
}
