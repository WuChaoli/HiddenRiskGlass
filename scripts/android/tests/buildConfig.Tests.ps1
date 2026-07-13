$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$appGradle = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'app\build.gradle')

if ($appGradle -notmatch 'ndk\s*\{\s*abiFilters\s+"arm64-v8a"\s*\}') {
    throw 'defaultConfig must restrict native builds to arm64-v8a.'
}

Write-Host 'PASS arm64 ABI configuration'
