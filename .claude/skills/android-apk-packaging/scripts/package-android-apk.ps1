[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot,

    [Parameter(Mandatory = $true)]
    [string]$GradleTask,

    [Parameter(Mandatory = $true)]
    [string]$DestinationApk,

    [string]$ModuleName = "app",

    [switch]$AllowDebugSigning
)

$ErrorActionPreference = "Stop"

function Get-LatestApk {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkRoot
    )

    $apk = Get-ChildItem -Path $ApkRoot -Recurse -File -Filter *.apk |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $apk) {
        throw "No APK found under $ApkRoot"
    }

    return $apk
}

function Get-BuildToolsDir {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SdkDir
    )

    $dir = Get-ChildItem -Path (Join-Path $SdkDir "build-tools") -Directory |
        Sort-Object Name -Descending |
        Select-Object -First 1

    if (-not $dir) {
        throw "No Android build-tools directory found under $SdkDir"
    }

    return $dir.FullName
}

$projectRootResolved = (Resolve-Path $ProjectRoot).Path
$destinationResolved = [System.IO.Path]::GetFullPath((Join-Path $projectRootResolved $DestinationApk))
$destinationDir = Split-Path -Path $destinationResolved -Parent

if (-not (Test-Path $destinationDir)) {
    New-Item -ItemType Directory -Path $destinationDir -Force | Out-Null
}

$gradlew = Join-Path $projectRootResolved "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    throw "gradlew.bat not found under $projectRootResolved"
}

Push-Location $projectRootResolved
try {
    & $gradlew $GradleTask

    $apkRoot = Join-Path $projectRootResolved "$ModuleName\build\outputs\apk"
    $builtApk = Get-LatestApk -ApkRoot $apkRoot
    $stagedApk = $destinationResolved

    Copy-Item -LiteralPath $builtApk.FullName -Destination $stagedApk -Force

    $isUnsigned = $builtApk.Name -like "*-unsigned.apk"
    if ($isUnsigned -and $AllowDebugSigning) {
        $sdkDir = $env:ANDROID_SDK_ROOT
        if (-not $sdkDir) {
            $sdkDir = $env:ANDROID_HOME
        }
        if (-not $sdkDir) {
            $sdkDir = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        }

        $keystore = Join-Path $env:USERPROFILE ".android\debug.keystore"
        if (-not (Test-Path $keystore)) {
            throw "Debug keystore not found at $keystore"
        }

        $buildToolsDir = Get-BuildToolsDir -SdkDir $sdkDir
        $apksigner = Join-Path $buildToolsDir "apksigner.bat"
        if (-not (Test-Path $apksigner)) {
            throw "apksigner.bat not found under $buildToolsDir"
        }

        & $apksigner sign `
            --ks $keystore `
            --ks-key-alias androiddebugkey `
            --ks-pass pass:android `
            --key-pass pass:android `
            $stagedApk
    }

    Get-Item -LiteralPath $stagedApk | Select-Object FullName, Length, LastWriteTime
}
finally {
    Pop-Location
}
