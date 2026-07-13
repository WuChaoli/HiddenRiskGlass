Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:AndroidScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:ProjectRoot = (Resolve-Path (Join-Path $script:AndroidScriptRoot '..\..')).Path

# 安全读取简单 KEY=VALUE 配置，不执行文件中的任何表达式。
function Read-DotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (!$trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            throw "Invalid .env line: $line"
        }

        $name = $Matches[1]
        $value = $Matches[2].Trim()
        $isDoubleQuoted = $value.StartsWith('"') -and $value.EndsWith('"')
        $isSingleQuoted = $value.StartsWith("'") -and $value.EndsWith("'")
        if ($value.Length -ge 2 -and ($isDoubleQuoted -or $isSingleQuoted)) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    return $values
}

# 按 .env、当前进程环境变量、Android Studio 默认路径的顺序解析配置。
function Get-AndroidConfiguration {
    $fileValues = @{}
    $envPath = Join-Path $script:ProjectRoot '.env'
    if (Test-Path -LiteralPath $envPath) {
        $fileValues = Read-DotEnv -Path $envPath
    }

    function Get-ConfigValue {
        param([string]$Name, [string]$Default = '')
        if ($fileValues.ContainsKey($Name) -and $fileValues[$Name]) {
            return $fileValues[$Name]
        }
        $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
        if ($processValue) {
            return $processValue
        }
        return $Default
    }

    $defaultSdk = if ($env:LOCALAPPDATA) {
        Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    } else {
        ''
    }
    $defaultDebugKeystore = if ($HOME) {
        Join-Path $HOME '.android\debug.keystore'
    } else {
        ''
    }

    return [pscustomobject]@{
        ProjectRoot = $script:ProjectRoot
        JavaHome = (Get-ConfigValue 'JAVA_HOME' 'C:\Program Files\Android\Android Studio\jbr')
        AndroidHome = (Get-ConfigValue 'ANDROID_HOME' $defaultSdk)
        CompileSdk = (Get-ConfigValue 'ANDROID_COMPILE_SDK' '34')
        BuildToolsVersion = (Get-ConfigValue 'ANDROID_BUILD_TOOLS_VERSION' '35.0.0')
        NdkVersion = (Get-ConfigValue 'ANDROID_NDK_VERSION' '29.0.14206865')
        CmakeVersion = (Get-ConfigValue 'ANDROID_CMAKE_VERSION' '3.22.1')
        DebugKeystorePath = (Get-ConfigValue 'DEBUG_KEYSTORE_PATH' $defaultDebugKeystore)
        GradleProxyHost = (Get-ConfigValue 'GRADLE_PROXY_HOST')
        GradleProxyPort = (Get-ConfigValue 'GRADLE_PROXY_PORT')
        ReleaseKeystorePath = (Get-ConfigValue 'RELEASE_KEYSTORE_PATH')
        ReleaseKeyAlias = (Get-ConfigValue 'RELEASE_KEY_ALIAS')
        ReleaseKeystorePassword = (Get-ConfigValue 'RELEASE_KEYSTORE_PASSWORD')
        ReleaseKeyPassword = (Get-ConfigValue 'RELEASE_KEY_PASSWORD')
        ReleaseCertSha256 = (Get-ConfigValue 'RELEASE_CERT_SHA256')
    }
}

# 定位 Windows Android SDK 中的指定工具。
function Resolve-AndroidTool {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $buildTools = Join-Path $Config.AndroidHome "build-tools\$($Config.BuildToolsVersion)"
    switch ($Name) {
        'adb' { $candidates = @((Join-Path $Config.AndroidHome 'platform-tools\adb.exe')) }
        'apksigner' { $candidates = @((Join-Path $buildTools 'apksigner.bat')) }
        default {
            $candidates = @(
                (Join-Path $buildTools "$Name.exe"),
                (Join-Path $buildTools "$Name.bat")
            )
        }
    }

    $tool = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$tool) {
        throw "Android tool not found: $Name"
    }
    return $tool
}

# 调用外部命令并把非零退出码转换为 PowerShell 错误。
function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath"
    }
}

function Get-AdbArguments {
    param([string]$Serial)
    if ($Serial) {
        return @('-s', $Serial)
    }
    return @()
}

# 将 Java properties 中转义的 Windows SDK 路径还原为普通路径。
function ConvertFrom-LocalPropertiesSdkDir {
    param([Parameter(Mandatory = $true)][string]$Value)
    return $Value.Replace('\:', ':').Replace('\\', '\')
}

# 从 APK badging 第一行读取包名和版本信息。
function Get-ApkMetadata {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$ApkPath
    )

    $aapt = Resolve-AndroidTool -Config $Config -Name 'aapt'
    $line = & $aapt dump badging $ApkPath | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or $line -notmatch "package: name='([^']+)'.*versionCode='([^']+)'.*versionName='([^']+)'") {
        throw "Unable to read APK metadata: $ApkPath"
    }
    return [pscustomobject]@{
        PackageName = $Matches[1]
        VersionCode = $Matches[2]
        VersionName = $Matches[3]
    }
}

# 读取 APK 第一个签名者的 SHA-256 证书摘要。
function Get-ApkCertificateSha256 {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$ApkPath
    )

    $apksigner = Resolve-AndroidTool -Config $Config -Name 'apksigner'
    $output = & $apksigner verify --print-certs $ApkPath
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to verify APK certificate: $ApkPath"
    }
    $line = $output | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: (.+)$' } | Select-Object -First 1
    if (!$line) {
        throw "APK certificate SHA-256 was not found: $ApkPath"
    }
    [void]($line -match '^Signer #1 certificate SHA-256 digest: (.+)$')
    return $Matches[1].Trim()
}
