param(
    [int]$Port = 10203,
    [string]$HostName = "0.0.0.0",
    [switch]$Reload
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $Root

if ([string]::IsNullOrWhiteSpace($env:ADMIN_PASSWORD)) {
    throw "ADMIN_PASSWORD must be set before starting HiddenRiskGlassServer."
}

Write-Host "Serving HiddenRiskGlassServer from $Root"
Write-Host "URL: http://$HostName`:$Port/"
$Args = @(".\server.py", "--host", $HostName, "--port", $Port)
if ($Reload) {
    $Args += "--reload"
}

python @Args
