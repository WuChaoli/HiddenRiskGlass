#!/bin/bash
# 依赖安装脚本 (macOS / Linux)
# 用途：下载 ncnn 和 OpenCV Mobile 预编译库到正确位置

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JNI_DIR="$PROJECT_ROOT/app/src/main/jni"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查命令是否存在
check_command() {
    if ! command -v "$1" &> /dev/null; then
        log_error "$1 未安装，请先安装 $1"
        exit 1
    fi
}

# 下载并解压
download_and_extract() {
    local url="$1"
    local filename="$2"
    local extract_dir="$3"
    local final_name="$4"

    if [ -d "$JNI_DIR/$final_name" ]; then
        log_warn "$final_name 已存在，跳过下载"
        return 0
    fi

    log_info "下载 $filename..."
    if command -v wget &> /dev/null; then
        wget -q --show-progress -O "/tmp/$filename" "$url"
    elif command -v curl &> /dev/null; then
        curl -L -o "/tmp/$filename" "$url"
    else
        log_error "未找到 wget 或 curl，请先安装其中一个"
        exit 1
    fi

    log_info "解压到 $extract_dir..."
    mkdir -p "$extract_dir"
    unzip -q "/tmp/$filename" -d "$extract_dir"
    rm "/tmp/$filename"

    log_info "$final_name 安装完成"
}

log_info "开始安装项目依赖..."
log_info "JNI 目录: $JNI_DIR"

# 检查必要工具
check_command unzip

mkdir -p "$JNI_DIR"

# 1. 下载 ncnn
NCNN_VERSION="20260113"
NCNN_FILENAME="ncnn-${NCNN_VERSION}-android-vulkan.zip"
NCNN_URL="https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_FILENAME}"

download_and_extract "$NCNN_URL" "$NCNN_FILENAME" "$JNI_DIR" "ncnn-${NCNN_VERSION}-android-vulkan"

# 2. 下载 OpenCV Mobile
OPENCV_VERSION="4.13.0"
OPENCV_FILENAME="opencv-mobile-${OPENCV_VERSION}-android.zip"
OPENCV_URL="https://github.com/nihui/opencv-mobile/releases/download/v${OPENCV_VERSION}/${OPENCV_FILENAME}"

download_and_extract "$OPENCV_URL" "$OPENCV_FILENAME" "$JNI_DIR" "opencv-mobile-${OPENCV_VERSION}-android"

# 验证安装
log_info "验证依赖安装..."

if [ -d "$JNI_DIR/ncnn-${NCNN_VERSION}-android-vulkan" ] && [ -d "$JNI_DIR/opencv-mobile-${OPENCV_VERSION}-android" ]; then
    log_info "所有依赖安装完成！"
    log_info "已安装:"
    log_info "  - ncnn-${NCNN_VERSION}-android-vulkan"
    log_info "  - opencv-mobile-${OPENCV_VERSION}-android"
    log_info ""
    log_info "现在可以运行 ./gradlew assembleDebug 编译项目"
else
    log_error "依赖安装不完整，请检查网络或手动下载"
    exit 1
fi
