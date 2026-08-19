$ErrorActionPreference = 'Stop'

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "$Message expected=[$Expected] actual=[$Actual]"
    }
}

$androidScriptRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "glassdemo-depth-overlay-test-$PID"
New-Item -ItemType Directory -Path $tempRoot | Out-Null

try {
    Copy-Item -LiteralPath (Join-Path $androidScriptRoot 'start-depth-overlay-simulation-test.ps1') `
        -Destination $tempRoot

    @'
param(
    [string]$Serial,
    [Parameter(Mandatory = $true)][string]$Activity,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ActivityArguments
)
[pscustomobject]@{
    Serial = $Serial
    Activity = $Activity
    ActivityArguments = $ActivityArguments
} | ConvertTo-Json -Compress
'@ | Set-Content -LiteralPath (Join-Path $tempRoot 'start-activity.ps1') -Encoding UTF8

    $defaultResult = (& (Join-Path $tempRoot 'start-depth-overlay-simulation-test.ps1')) | ConvertFrom-Json
    Assert-Equal '' $defaultResult.Serial 'default launch serial'
    Assert-Equal 'com.rokid.glass.hiddenrisk.DepthOverlaySimulationTestActivity' `
        $defaultResult.Activity `
        'simulation activity'

    $serialResult = (& (Join-Path $tempRoot 'start-depth-overlay-simulation-test.ps1') -Serial 'glass-001') |
        ConvertFrom-Json
    Assert-Equal 'glass-001' $serialResult.Serial 'explicit serial'

    Write-Host 'PASS start-depth-overlay-simulation-test.ps1 tests'
} finally {
    Remove-Item -Recurse -Force -LiteralPath $tempRoot -ErrorAction SilentlyContinue
}
