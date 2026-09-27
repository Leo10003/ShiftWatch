# Run from the project root in PowerShell on Windows, after Android Studio SDK setup.
$ErrorActionPreference = 'Stop'
Write-Host 'Checking anonymized regression-case metadata...'
python tools/verify_regression_cases.py
if ($LASTEXITCODE -ne 0) { throw 'Regression-case metadata validation failed.' }
Write-Host 'Running complete Android build, unit tests and lint...'
& .\gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Android quality gate failed. Inspect the first actual compiler error.' }
Write-Host 'All local build checks passed. Device gesture/recognition tests are still required.'
