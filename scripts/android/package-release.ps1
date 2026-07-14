param([switch]$ReplaceCurrent)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$config = Get-AndroidConfiguration
$appGradlePath = Join-Path $config.ProjectRoot 'app\build.gradle'
$appGradle = Get-Content -Raw -LiteralPath $appGradlePath
if ($appGradle -notmatch 'versionCode\s+([0-9]+)') {
    throw 'Unable to read versionCode from app/build.gradle.'
}
$versionCode = [int]$Matches[1]
if ($appGradle -notmatch 'versionName\s+"([^"]+)"') {
    throw 'Unable to read versionName from app/build.gradle.'
}
$versionName = $Matches[1]

$releaseRoot = Join-Path $config.ProjectRoot 'release'
$maxVersionCode = -1
$sameVersionReference = $null
if (Test-Path -LiteralPath $releaseRoot) {
    foreach ($existingApk in Get-ChildItem -LiteralPath $releaseRoot -Filter '*.apk' -File -Recurse) {
        try {
            $metadata = Get-ApkMetadata -Config $config -ApkPath $existingApk.FullName
            $existingCode = [int]$metadata.VersionCode
            if ($existingCode -gt $maxVersionCode) {
                $maxVersionCode = $existingCode
            }
            if ($existingCode -eq $versionCode -and !$sameVersionReference) {
                $sameVersionReference = $existingApk.FullName
            }
        } catch {
            Write-Warning "Skipping unreadable APK: $($existingApk.FullName)"
        }
    }
}
if ($maxVersionCode -ge 0) {
    if ($versionCode -lt $maxVersionCode) {
        throw "versionCode=$versionCode is lower than existing release versionCode=$maxVersionCode."
    }
    if ($versionCode -eq $maxVersionCode -and !$ReplaceCurrent) {
        throw "versionCode=$versionCode already exists. Increment it, or pass -ReplaceCurrent."
    }
}

& "$PSScriptRoot\build-release.ps1"
$sourceApk = Join-Path $config.ProjectRoot 'app\build\outputs\apk\standard\release\app-standard-release-unsigned.apk'
$sourceMetadata = Get-ApkMetadata -Config $config -ApkPath $sourceApk
if ([int]$sourceMetadata.VersionCode -ne $versionCode -or $sourceMetadata.VersionName -ne $versionName) {
    throw 'Built APK metadata does not match app/build.gradle.'
}

$formalValues = @(
    $config.ReleaseKeystorePath,
    $config.ReleaseKeyAlias,
    $config.ReleaseKeystorePassword,
    $config.ReleaseKeyPassword,
    $config.ReleaseCertSha256
)
$formalSigning = @($formalValues | Where-Object { !$_ }).Count -eq 0
if ($formalSigning) {
    $outputDirectory = $releaseRoot
    $outputApk = Join-Path $outputDirectory "全省版-v$versionName.apk"
    $keystore = $config.ReleaseKeystorePath
    $alias = $config.ReleaseKeyAlias
    $storePassword = $config.ReleaseKeystorePassword
    $keyPassword = $config.ReleaseKeyPassword
    $expectedCertificate = $config.ReleaseCertSha256.ToLowerInvariant()
    $signingLabel = 'release'
} else {
    $outputDirectory = Join-Path $releaseRoot 'local'
    $outputApk = Join-Path $outputDirectory "全省版-v$versionName-debug-signed.apk"
    $keystore = $config.DebugKeystorePath
    $alias = 'androiddebugkey'
    $storePassword = 'android'
    $keyPassword = 'android'
    $expectedCertificate = ''
    $signingLabel = 'debug-local-demo'
    Write-Warning 'Release signing is incomplete. Producing a local demo APK signed with the debug keystore.'
}
if (!(Test-Path -LiteralPath $keystore -PathType Leaf)) {
    throw "Keystore not found: $keystore"
}
[void](New-Item -ItemType Directory -Force -Path $outputDirectory)

$zipalign = Resolve-AndroidTool -Config $config -Name 'zipalign'
$apksigner = Resolve-AndroidTool -Config $config -Name 'apksigner'
$temporaryApk = "$outputApk.aligned.tmp.apk"
Remove-Item -LiteralPath $temporaryApk -Force -ErrorAction SilentlyContinue
try {
    Invoke-External -FilePath $zipalign -Arguments @('-f', '-p', '4', $sourceApk, $temporaryApk)
    Invoke-External -FilePath $apksigner -Arguments @(
        'sign', '--ks', $keystore, '--ks-key-alias', $alias,
        '--ks-pass', "pass:$storePassword", '--key-pass', "pass:$keyPassword",
        '--out', $outputApk, $temporaryApk
    )
} finally {
    Remove-Item -LiteralPath $temporaryApk -Force -ErrorAction SilentlyContinue
}

$actualCertificate = Get-ApkCertificateSha256 -Config $config -ApkPath $outputApk
if ($expectedCertificate -and $actualCertificate.ToLowerInvariant() -ne $expectedCertificate) {
    Remove-Item -LiteralPath $outputApk, "$outputApk.idsig" -Force -ErrorAction SilentlyContinue
    throw 'Signed certificate SHA-256 does not match RELEASE_CERT_SHA256.'
}
if ($ReplaceCurrent -and $sameVersionReference -and $sameVersionReference -ne $outputApk) {
    $referenceCertificate = Get-ApkCertificateSha256 -Config $config -ApkPath $sameVersionReference
    if ($referenceCertificate.ToLowerInvariant() -ne $actualCertificate.ToLowerInvariant()) {
        Remove-Item -LiteralPath $outputApk, "$outputApk.idsig" -Force -ErrorAction SilentlyContinue
        throw "Refusing same-version replacement: certificate differs from $sameVersionReference."
    }
}

& "$PSScriptRoot\verify-apk.ps1" $outputApk
Write-Host "Signing type: $signingLabel"
Write-Host "Staged APK: $outputApk"
