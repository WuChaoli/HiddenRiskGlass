param(
    [int]$Port = 8080,
    [string]$HostName = "0.0.0.0"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $Root

Write-Host "Serving APK update UI from $Root"
Write-Host "URL: http://$HostName`:$Port/"
python .\server.py --host $HostName --port $Port
