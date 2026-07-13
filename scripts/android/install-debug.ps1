param([string]$Serial)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$config = Get-AndroidConfiguration
$apk = Join-Path $config.ProjectRoot 'app\build\outputs\apk\standard\debug\app-standard-debug.apk'
if (!(Test-Path -LiteralPath $apk)) {
    & "$PSScriptRoot\build-debug.ps1"
}
& "$PSScriptRoot\doctor.ps1" -Device

$adb = Resolve-AndroidTool -Config $config -Name 'adb'
$arguments = @(Get-AdbArguments -Serial $Serial) + @('install', '-r', $apk)
$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $output = & $adb @arguments 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$output | ForEach-Object { Write-Host $_ }
if (($output -join "`n") -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    throw 'APK signing certificate does not match the installed app. The script will not uninstall user data automatically.'
}
if ($exitCode -ne 0) {
    throw "adb install failed with exit code $exitCode."
}
