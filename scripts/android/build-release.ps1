param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

& "$PSScriptRoot\doctor.ps1"
& "$PSScriptRoot\gradle.ps1" ':app:assembleStandardRelease' @GradleArguments

$config = Get-AndroidConfiguration
$apk = Join-Path $config.ProjectRoot 'app\build\outputs\apk\standard\release\app-standard-release-unsigned.apk'
if (!(Test-Path -LiteralPath $apk)) {
    throw "Expected unsigned APK was not generated: $apk"
}
Write-Host "Unsigned release APK: $apk"
