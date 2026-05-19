param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $Root

Write-Host "Serving APK update files from $Root"
Write-Host "URL: http://0.0.0.0:$Port/"
python -m http.server $Port --bind 0.0.0.0
