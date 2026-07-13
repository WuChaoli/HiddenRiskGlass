param([switch]$Device)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

function Assert-PathExists {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (!(Test-Path -LiteralPath $Path)) {
        throw "Required path not found: $Path"
    }
}

$config = Get-AndroidConfiguration
$java = Join-Path $config.JavaHome 'bin\java.exe'
$ndkPrebuilt = Join-Path $config.AndroidHome "ndk\$($config.NdkVersion)\toolchains\llvm\prebuilt\windows-x86_64"
$cmakeRoot = Join-Path $config.AndroidHome "cmake\$($config.CmakeVersion)"

# 检查项目固定版本对应的完整 Windows 原生工具链。
$requiredPaths = @(
    $java,
    (Join-Path $config.AndroidHome "platforms\android-$($config.CompileSdk)"),
    (Join-Path $config.AndroidHome "build-tools\$($config.BuildToolsVersion)"),
    (Join-Path $config.AndroidHome "ndk\$($config.NdkVersion)"),
    (Join-Path $config.AndroidHome "cmake\$($config.CmakeVersion)"),
    (Join-Path $ndkPrebuilt 'bin\clang.exe'),
    (Join-Path $ndkPrebuilt 'bin\clang++.exe'),
    (Join-Path $cmakeRoot 'bin\cmake.exe'),
    (Join-Path $cmakeRoot 'bin\ninja.exe'),
    (Join-Path $config.ProjectRoot 'gradlew.bat'),
    (Join-Path $config.ProjectRoot 'gradle\wrapper\gradle-wrapper.properties'),
    (Join-Path $config.ProjectRoot 'local.properties')
)
foreach ($path in $requiredPaths) {
    Assert-PathExists -Path $path
}
foreach ($tool in @('aapt', 'apksigner', 'zipalign', 'adb')) {
    [void](Resolve-AndroidTool -Config $config -Name $tool)
}

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaLine = & $java -version 2>&1 | Select-Object -First 1
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($javaLine -notmatch 'version "21[\.]') {
    throw "JDK 21 required: $javaLine"
}

$wrapper = Get-Content -Raw -LiteralPath (Join-Path $config.ProjectRoot 'gradle\wrapper\gradle-wrapper.properties')
if ($wrapper -notmatch 'gradle-8\.6-') {
    throw 'Gradle wrapper must remain 8.6.'
}

$appGradle = Get-Content -Raw -LiteralPath (Join-Path $config.ProjectRoot 'app\build.gradle')
foreach ($pattern in @('ndkVersion "29\.0\.14206865"', 'standard\s*\{', 'assembleStandardDebug')) {
    if ($appGradle -notmatch $pattern) {
        throw "Android baseline missing: $pattern"
    }
}

$localProperties = Get-Content -LiteralPath (Join-Path $config.ProjectRoot 'local.properties')
$sdkLine = $localProperties | Where-Object { $_ -match '^sdk\.dir=(.*)$' } | Select-Object -First 1
if (!$sdkLine) {
    throw 'local.properties does not contain sdk.dir.'
}
[void]($sdkLine -match '^sdk\.dir=(.*)$')
$configuredSdk = ConvertFrom-LocalPropertiesSdkDir -Value $Matches[1]
if ([IO.Path]::GetFullPath($configuredSdk).TrimEnd('\') -ne [IO.Path]::GetFullPath($config.AndroidHome).TrimEnd('\')) {
    throw "local.properties sdk.dir=$configuredSdk does not match ANDROID_HOME=$($config.AndroidHome)."
}

Write-Host 'Environment OK'
Write-Host "  JAVA_HOME=$($config.JavaHome) ($javaLine)"
Write-Host "  ANDROID_HOME=$($config.AndroidHome)"
Write-Host "  compileSdk=$($config.CompileSdk) ndk=$($config.NdkVersion) cmake=$($config.CmakeVersion) buildTools=$($config.BuildToolsVersion)"
Write-Host '  defaultVariant=standardDebug releaseVariant=standardRelease'

if ($Device) {
    $adb = Resolve-AndroidTool -Config $config -Name 'adb'
    $deviceOutput = & $adb devices -l
    if ($LASTEXITCODE -ne 0) {
        throw 'adb devices failed.'
    }
    $deviceOutput | ForEach-Object { Write-Host $_ }
    $readyDevices = @($deviceOutput | Where-Object { $_ -match '\sdevice(\s|$)' })
    if ($readyDevices.Count -eq 0) {
        throw 'No authorized Android device found. Check USB debugging authorization and adb devices -l.'
    }
}
