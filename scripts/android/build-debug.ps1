param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

& "$PSScriptRoot\doctor.ps1"
& "$PSScriptRoot\gradle.ps1" ':app:assembleStandardDebug' @GradleArguments

$config = Get-AndroidConfiguration
$apk = Join-Path $config.ProjectRoot 'app\build\outputs\apk\standard\debug\app-standard-debug.apk'
if (!(Test-Path -LiteralPath $apk)) {
    throw "Expected APK was not generated: $apk"
}
Write-Host "Debug APK: $apk"
