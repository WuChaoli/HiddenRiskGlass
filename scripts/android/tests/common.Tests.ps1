$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\..\common.ps1"

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "$Message expected=[$Expected] actual=[$Actual]"
    }
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "glassdemo-android-test-$PID"
New-Item -ItemType Directory -Path $tempRoot | Out-Null

try {
    $marker = Join-Path $tempRoot 'must-not-exist.txt'
    $envPath = Join-Path $tempRoot '.env'
    @"
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
ANDROID_HOME=C:\Users\tester\AppData\Local\Android\Sdk
GRADLE_PROXY_HOST=
EVIL=`$(New-Item -ItemType File -Path '$marker')
"@ | Set-Content -Encoding UTF8 -LiteralPath $envPath

    $values = Read-DotEnv -Path $envPath
    Assert-Equal 'C:\Program Files\Android\Android Studio\jbr' $values.JAVA_HOME 'quoted value'
    Assert-Equal '' $values.GRADLE_PROXY_HOST 'empty value'
    Assert-Equal $false (Test-Path -LiteralPath $marker) 'dotenv must not execute commands'
    Assert-Equal '-s,glass-001' ((Get-AdbArguments -Serial 'glass-001') -join ',') 'serial args'
    Assert-Equal 'C:\Users\tester\Android\Sdk' `
        (ConvertFrom-LocalPropertiesSdkDir 'C\:\\Users\\tester\\Android\\Sdk') `
        'local.properties sdk.dir decoding'
    Assert-Equal 'com.rokid.glesse/com.rokid.glass.MainMenuActivity' `
        (Resolve-ActivityComponent -Activity '.MainMenuActivity') `
        'short activity class'
    Assert-Equal 'com.rokid.glesse/com.rokid.glass.hiddenrisk.HiddenRiskProbeActivity' `
        (Resolve-ActivityComponent -Activity 'com.rokid.glass.hiddenrisk.HiddenRiskProbeActivity') `
        'fully qualified activity class'
    Assert-Equal '--es,mode,alignment_calibration,--es,dominantEye,right' `
        ((Get-AlignmentTestActivityArguments -Eye 'right') -join ',') `
        'right-eye alignment activity arguments'
    Assert-Equal '--es,mode,alignment_calibration,--es,dominantEye,right' `
        ((Get-AlignmentTestActivityArguments) -join ',') `
        'default right-eye alignment activity arguments'
    Assert-Equal '--es,mode,alignment_calibration,--es,dominantEye,left' `
        ((Get-AlignmentTestActivityArguments -Eye 'left') -join ',') `
        'left-eye alignment activity arguments'

    $invalidEyeRejected = $false
    try {
        Get-AlignmentTestActivityArguments -Eye 'center' | Out-Null
    } catch {
        $invalidEyeRejected = $true
    }
    Assert-Equal $true $invalidEyeRejected 'invalid dominant eye must be rejected'
    Write-Host 'PASS common.ps1 tests'
} finally {
    Remove-Item -Recurse -Force -LiteralPath $tempRoot -ErrorAction SilentlyContinue
}
