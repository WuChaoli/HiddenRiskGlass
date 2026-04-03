# 依赖安装脚本 (Windows PowerShell)
# 用途：下载 ncnn 和 OpenCV Mobile 预编译库到正确位置

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectRoot = Split-Path -Parent $ScriptDir
$JniDir = Join-Path $ProjectRoot "app\src\main\jni"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Download-And-Extract {
    param(
        [string]$Url,
        [string]$Filename,
        [string]$FinalName
    )

    $FinalPath = Join-Path $JniDir $FinalName

    if (Test-Path $FinalPath) {
        Write-Warn "$FinalName 已存在，跳过下载"
        return $true
    }

    $TempFile = Join-Path $env:TEMP $Filename

    Write-Info "下载 $Filename..."
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $Url -OutFile $TempFile -UseBasicParsing
    } catch {
        Write-Error-Custom "下载失败: $_"
        Write-Error-Custom "请手动下载 $Url 并解压到 $JniDir"
        return $false
    }

    Write-Info "解压到 $JniDir..."
    try {
        Expand-Archive -Path $TempFile -DestinationPath $JniDir -Force
        Remove-Item $TempFile -Force
    } catch {
        Write-Error-Custom "解压失败: $_"
        return $false
    }

    Write-Info "$FinalName 安装完成"
    return $true
}

Write-Info "开始安装项目依赖..."
Write-Info "JNI 目录: $JniDir"

if (-not (Test-Path $JniDir)) {
    New-Item -ItemType Directory -Path $JniDir -Force | Out-Null
}

# 1. 下载 ncnn
$NcnnVersion = "20260113"
$NcnnFilename = "ncnn-${NcnnVersion}-android-vulkan.zip"
$NcnnUrl = "https://github.com/Tencent/ncnn/releases/download/${NcnnVersion}/$NcnnFilename"

$NcnnSuccess = Download-And-Extract -Url $NcnnUrl -Filename $NcnnFilename -FinalName "ncnn-${NcnnVersion}-android-vulkan"

# 2. 下载 OpenCV Mobile
$OpencvVersion = "4.13.0"
$OpencvFilename = "opencv-mobile-${OpencvVersion}-android.zip"
$OpencvUrl = "https://github.com/nihui/opencv-mobile/releases/download/v${OpencvVersion}/$OpencvFilename"

$OpencvSuccess = Download-And-Extract -Url $OpencvUrl -Filename $OpencvFilename -FinalName "opencv-mobile-${OpencvVersion}-android"

# 验证安装
Write-Info "验证依赖安装..."

$NcnnPath = Join-Path $JniDir "ncnn-${NcnnVersion}-android-vulkan"
$OpencvPath = Join-Path $JniDir "opencv-mobile-${OpencvVersion}-android"

if ($NcnnSuccess -and $OpencvSuccess -and (Test-Path $NcnnPath) -and (Test-Path $OpencvPath)) {
    Write-Info "所有依赖安装完成！"
    Write-Info "已安装:"
    Write-Info "  - ncnn-${NcnnVersion}-android-vulkan"
    Write-Info "  - opencv-mobile-${OpencvVersion}-android"
    Write-Info ""
    Write-Info "现在可以运行 .\gradlew.bat assembleDebug 编译项目"
} else {
    Write-Error-Custom "依赖安装不完整，请检查网络或手动下载"
    Write-Error-Custom "手动下载说明请参考 SETUP.md"
    exit 1
}
