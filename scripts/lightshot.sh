#!/usr/bin/env bash
# lightshot.sh — 闪拍图片管理脚本
#
# 用法：
#   ./scripts/lightshot.sh pull            # 从设备抽取图片到本地 lightshot_samples/
#   ./scripts/lightshot.sh pull <本地目录>  # 抽取到指定目录
#   ./scripts/lightshot.sh delete          # 删除设备上的所有闪拍图片
#   ./scripts/lightshot.sh pull-delete     # 抽取后自动删除设备上的图片

set -euo pipefail

# ── 常量 ──────────────────────────────────────────
DEVICE_DIR="/storage/emulated/0/lightshot"
DEFAULT_LOCAL_DIR="lightshot_samples"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── 颜色输出 ─────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()    { echo -e "${GREEN}[lightshot]${NC} $*"; }
warn()    { echo -e "${YELLOW}[lightshot]${NC} $*"; }
error()   { echo -e "${RED}[lightshot]${NC} $*" >&2; }

# ── 检查 adb 可用性 ───────────────────────────────
check_adb() {
    if ! command -v adb &>/dev/null; then
        error "未找到 adb，请确保 Android SDK platform-tools 已加入 PATH"
        exit 1
    fi
    # 检查是否有设备连接
    local devices
    devices=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l)
    if [[ "$devices" -eq 0 ]]; then
        error "未检测到已连接的 Android 设备，请通过 USB 连接眼镜并确认授权"
        exit 1
    fi
    if [[ "$devices" -gt 1 ]]; then
        warn "检测到多台设备，将使用第一台"
    fi
}

# ── 检查设备上的图片数量 ──────────────────────────
count_device_images() {
    adb shell "ls \"$DEVICE_DIR\"/*.jpg 2>/dev/null | wc -l" 2>/dev/null | tr -d '[:space:]' || echo "0"
}

# ── 抽取：pull ────────────────────────────────────
cmd_pull() {
    local local_dir="${1:-$DEFAULT_LOCAL_DIR}"
    # 若路径是相对路径，则相对于项目根目录
    if [[ "$local_dir" != /* ]]; then
        local_dir="$PROJECT_ROOT/$local_dir"
    fi

    info "目标设备目录：$DEVICE_DIR"
    info "本地保存目录：$local_dir"

    # 检查设备目录是否存在
    if ! adb shell "[ -d \"$DEVICE_DIR\" ]" 2>/dev/null; then
        warn "设备上的 $DEVICE_DIR 目录不存在，可能还没有拍摄任何图片"
        exit 0
    fi

    local count
    count=$(count_device_images)
    if [[ "$count" -eq 0 ]]; then
        warn "设备上暂无闪拍图片（$DEVICE_DIR/*.jpg）"
        exit 0
    fi

    info "发现 $count 张图片，开始抽取..."
    mkdir -p "$local_dir"
    adb pull "$DEVICE_DIR/" "$local_dir/"

    # 统计实际拉取的文件数
    local pulled
    pulled=$(find "$local_dir" -maxdepth 1 -name "*.jpg" | wc -l)
    info "✓ 抽取完成，共 $pulled 张图片保存至：$local_dir"
}

# ── 删除：delete ──────────────────────────────────
cmd_delete() {
    if ! adb shell "[ -d \"$DEVICE_DIR\" ]" 2>/dev/null; then
        warn "设备上的 $DEVICE_DIR 目录不存在，无需删除"
        exit 0
    fi

    local count
    count=$(count_device_images)
    if [[ "$count" -eq 0 ]]; then
        warn "设备上暂无闪拍图片，无需删除"
        exit 0
    fi

    warn "即将删除设备上 $count 张闪拍图片（$DEVICE_DIR/*.jpg）"
    read -r -p "确认删除？[y/N] " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
        adb shell "rm -f \"$DEVICE_DIR\"/*.jpg"
        info "✓ 已删除设备上的所有闪拍图片"
    else
        info "已取消"
    fi
}

# ── 抽取并删除：pull-delete ───────────────────────
cmd_pull_delete() {
    local local_dir="${1:-$DEFAULT_LOCAL_DIR}"
    cmd_pull "$local_dir"
    echo ""
    info "开始清理设备图片..."
    # pull-delete 模式不再交互确认，直接删除
    if adb shell "[ -d \"$DEVICE_DIR\" ]" 2>/dev/null; then
        adb shell "rm -f \"$DEVICE_DIR\"/*.jpg"
        info "✓ 设备上的闪拍图片已清除"
    fi
}

# ── 帮助 ──────────────────────────────────────────
usage() {
    echo "用法：$0 <命令> [本地目录]"
    echo ""
    echo "命令："
    echo "  pull          从设备抽取图片到本地（默认保存到 lightshot_samples/）"
    echo "  delete        删除设备上的所有闪拍图片（交互确认）"
    echo "  pull-delete   抽取图片后自动清除设备上的图片"
    echo ""
    echo "示例："
    echo "  $0 pull                     # 抽取到 lightshot_samples/"
    echo "  $0 pull my_dataset/batch1   # 抽取到指定目录"
    echo "  $0 delete                   # 仅删除设备图片"
    echo "  $0 pull-delete              # 抽取 + 删除"
}

# ── 主入口 ────────────────────────────────────────
main() {
    local cmd="${1:-}"
    shift || true

    case "$cmd" in
        pull)
            check_adb
            cmd_pull "${1:-}"
            ;;
        delete)
            check_adb
            cmd_delete
            ;;
        pull-delete)
            check_adb
            cmd_pull_delete "${1:-}"
            ;;
        ""|--help|-h)
            usage
            ;;
        *)
            error "未知命令：$cmd"
            echo ""
            usage
            exit 1
            ;;
    esac
}

main "$@"
