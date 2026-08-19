$ErrorActionPreference = 'Stop'

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "$Message expected=[$Expected] actual=[$Actual]"
    }
}

$androidScriptRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "glassdemo-alignment-test-$PID"
New-Item -ItemType Directory -Path $tempRoot | Out-Null

try {
    Copy-Item -LiteralPath (Join-Path $androidScriptRoot 'common.ps1') -Destination $tempRoot
    Copy-Item -LiteralPath (Join-Path $androidScriptRoot 'start-alignment-test.ps1') -Destination $tempRoot

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

    $defaultResult = (& (Join-Path $tempRoot 'start-alignment-test.ps1')) | ConvertFrom-Json
    Assert-Equal '' $defaultResult.Serial 'default launch must not bind an Activity extra as serial'
    Assert-Equal 'com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity' `
        $defaultResult.Activity `
        'alignment activity'
    Assert-Equal '--es,mode,alignment_calibration,--es,dominantEye,right' `
        ($defaultResult.ActivityArguments -join ',') `
        'default right-eye extras'

    $leftResult = (& (Join-Path $tempRoot 'start-alignment-test.ps1') -Serial 'glass-001' -Eye left) |
        ConvertFrom-Json
    Assert-Equal 'glass-001' $leftResult.Serial 'explicit serial'
    Assert-Equal '--es,mode,alignment_calibration,--es,dominantEye,left' `
        ($leftResult.ActivityArguments -join ',') `
        'left-eye extras'

    Write-Host 'PASS start-alignment-test.ps1 tests'
} finally {
    Remove-Item -Recurse -Force -LiteralPath $tempRoot -ErrorAction SilentlyContinue
}
