param(
    [string]$Serial,
    [Parameter(Mandatory = $true)][string]$Activity,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ActivityArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$config = Get-AndroidConfiguration
& "$PSScriptRoot\doctor.ps1" -Device | Out-Null
$adb = Resolve-AndroidTool -Config $config -Name 'adb'
$component = Resolve-ActivityComponent -Activity $Activity
$arguments = @(Get-AdbArguments -Serial $Serial) + @('shell', 'am', 'start', '-n', $component) + $ActivityArguments
Invoke-External -FilePath $adb -Arguments $arguments
