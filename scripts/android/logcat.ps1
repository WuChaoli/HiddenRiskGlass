param(
    [string]$Serial,
    [switch]$Clear,
    [string]$Tag
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$config = Get-AndroidConfiguration
$adb = Resolve-AndroidTool -Config $config -Name 'adb'
$serialArguments = @(Get-AdbArguments -Serial $Serial)
if ($Clear) {
    Invoke-External -FilePath $adb -Arguments ($serialArguments + @('logcat', '-c'))
}
if ($Tag) {
    & $adb @serialArguments logcat | Select-String -Pattern $Tag
} else {
    & $adb @serialArguments logcat
}
if ($LASTEXITCODE -ne 0) {
    throw "adb logcat failed with exit code $LASTEXITCODE."
}
