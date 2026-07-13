param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$config = Get-AndroidConfiguration
if ([bool]$config.GradleProxyHost -xor [bool]$config.GradleProxyPort) {
    throw 'GRADLE_PROXY_HOST and GRADLE_PROXY_PORT must both be set, or both be empty.'
}

$effectiveArguments = @()
if ($config.GradleProxyHost) {
    $effectiveArguments += @(
        "-Dhttp.proxyHost=$($config.GradleProxyHost)",
        "-Dhttp.proxyPort=$($config.GradleProxyPort)",
        "-Dhttps.proxyHost=$($config.GradleProxyHost)",
        "-Dhttps.proxyPort=$($config.GradleProxyPort)"
    )
}
$effectiveArguments += $GradleArguments

$env:JAVA_HOME = $config.JavaHome
$env:ANDROID_HOME = $config.AndroidHome
$env:ANDROID_SDK_ROOT = $config.AndroidHome
$gradlew = Join-Path $config.ProjectRoot 'gradlew.bat'

Push-Location $config.ProjectRoot
try {
    & $gradlew @effectiveArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
