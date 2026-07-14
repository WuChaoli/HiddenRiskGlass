param([Parameter(Mandatory = $true, Position = 0)][string]$ApkPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$config = Get-AndroidConfiguration
$resolvedApk = if ([IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $config.ProjectRoot $ApkPath
}
if (!(Test-Path -LiteralPath $resolvedApk -PathType Leaf)) {
    throw "APK not found: $resolvedApk"
}
$resolvedApk = (Resolve-Path -LiteralPath $resolvedApk).Path

$apksigner = Resolve-AndroidTool -Config $config -Name 'apksigner'
Invoke-External -FilePath $apksigner -Arguments @('verify', '--verbose', $resolvedApk)
$metadata = Get-ApkMetadata -Config $config -ApkPath $resolvedApk
$certificate = Get-ApkCertificateSha256 -Config $config -ApkPath $resolvedApk
$size = (Get-Item -LiteralPath $resolvedApk).Length

Write-Host "APK: $resolvedApk"
Write-Host "Size: $size bytes"
Write-Host "Package: $($metadata.PackageName)"
Write-Host "Version: versionCode=$($metadata.VersionCode) versionName=$($metadata.VersionName)"
Write-Host "Certificate SHA-256: $certificate"
Write-Host 'Signature: verified'
