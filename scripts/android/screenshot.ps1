param(
    [string]$Serial,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$config = Get-AndroidConfiguration
$adb = Resolve-AndroidTool -Config $config -Name 'adb'
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath
} else {
    Join-Path $config.ProjectRoot $OutputPath
}
$outputDirectory = Split-Path -Parent $resolvedOutput
if ($outputDirectory) {
    [void](New-Item -ItemType Directory -Force -Path $outputDirectory)
}

# Windows PowerShell 文本管道会破坏 PNG 字节，必须直接复制标准输出流。
$argumentParts = @()
if ($Serial) {
    $argumentParts += @('-s', $Serial)
}
$argumentParts += @('exec-out', 'screencap', '-p')
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $adb
$startInfo.Arguments = ($argumentParts | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo
[void]$process.Start()
$file = [IO.File]::Open($resolvedOutput, [IO.FileMode]::Create, [IO.FileAccess]::Write)
try {
    $process.StandardOutput.BaseStream.CopyTo($file)
} finally {
    $file.Dispose()
}
$errorText = $process.StandardError.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -ne 0) {
    Remove-Item -LiteralPath $resolvedOutput -Force -ErrorAction SilentlyContinue
    throw "adb screencap failed ($($process.ExitCode)): $errorText"
}
Write-Host "Screenshot: $resolvedOutput"
