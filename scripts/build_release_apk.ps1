param()

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

Push-Location $RepoRoot
try {
    .\gradlew.bat :app:assembleRelease

    $Apk = Get-Item ".\app\build\outputs\apk\release\app-release.apk"
    Write-Host ""
    Write-Host "Release APK:"
    $Apk | Select-Object FullName, Length, LastWriteTime | Format-List
} finally {
    Pop-Location
}
