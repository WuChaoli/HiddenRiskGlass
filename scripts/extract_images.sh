#!/bin/bash
#
# 从 Rokid AR 眼镜中提取保存的图片
# 支持提取闪拍图片和隐患识别结果图片
#

set -e

# 配置
PACKAGE_NAME="com.rokid.glass"
LIGHTSHOT_DIR="/storage/emulated/0/lightshot"
HAZARD_DIR="/sdcard/Android/data/${PACKAGE_NAME}/files/HazardCaptures"
OUTPUT_DIR="./extracted_images"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印帮助信息
show_help() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -a, --all           提取所有图片 (默认)"
    echo "  -l, --lightshot     仅提取闪拍图片 (/sdcard/lightshot/)"
    echo "  -r, --hazard        仅提取隐患识别结果 (/Android/data/${PACKAGE_NAME}/files/HazardCaptures/)"
    echo "  -o, --output DIR    指定输出目录 (默认: ./extracted_images)"
    echo "  -d, --delete        提取后从眼镜中删除已提取的文件"
    echo "  -h, --help          显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0                          # 提取所有图片"
    echo "  $0 -l                       # 仅提取闪拍图片"
    echo "  $0 -r -o ./hazard_images    # 提取隐患图片到指定目录"
    echo "  $0 -a -d                    # 提取所有图片并删除源文件"
}

# 解析参数
EXTRACT_ALL=false
EXTRACT_LIGHTSHOT=false
EXTRACT_HAZARD=false
DELETE_AFTER=false

if [ $# -eq 0 ]; then
    EXTRACT_ALL=true
fi

while [[ $# -gt 0 ]]; do
    case $1 in
        -a|--all)
            EXTRACT_ALL=true
            shift
            ;;
        -l|--lightshot)
            EXTRACT_LIGHTSHOT=true
            EXTRACT_ALL=false
            shift
            ;;
        -r|--hazard)
            EXTRACT_HAZARD=true
            EXTRACT_ALL=false
            shift
            ;;
        -o|--output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -d|--delete)
            DELETE_AFTER=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo -e "${RED}未知选项: $1${NC}"
            show_help
            exit 1
            ;;
    esac
done

# 检查 adb 是否可用
if ! command -v adb &> /dev/null; then
    echo -e "${RED}错误: 未找到 adb 命令，请确保 Android SDK 已安装并添加到 PATH${NC}"
    exit 1
fi

# 检查设备是否连接
echo "正在检查设备连接..."
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}错误: 未检测到已连接的 Android 设备${NC}"
    echo "请确保:"
    echo "  1. 眼镜已通过 USB 连接"
    echo "  2. 已启用开发者选项和 USB 调试"
    exit 1
fi

echo -e "${GREEN}设备已连接${NC}"
echo ""

# 检查应用是否安装
if ! adb shell pm list packages | grep -q "${PACKAGE_NAME}"; then
    echo -e "${YELLOW}警告: 未检测到应用 ${PACKAGE_NAME}${NC}"
fi

# 创建输出目录
mkdir -p "${OUTPUT_DIR}"
LIGHTSHOT_OUTPUT="${OUTPUT_DIR}/lightshot_${TIMESTAMP}"
HAZARD_OUTPUT="${OUTPUT_DIR}/hazard_${TIMESTAMP}"

extract_lightshot() {
    echo "===== 提取闪拍图片 ====="
    echo "源路径: ${LIGHTSHOT_DIR}"

    # 检查目录是否存在
    if ! adb shell "[ -d ${LIGHTSHOT_DIR} ]"; then
        echo -e "${YELLOW}闪拍目录不存在，跳过${NC}"
        return
    fi

    # 获取文件列表
    local files=$(adb shell "ls -1 ${LIGHTSHOT_DIR}/*.jpg 2>/dev/null" | tr -d '\r')

    if [ -z "$files" ]; then
        echo -e "${YELLOW}闪拍目录中没有图片${NC}"
        return
    fi

    # 创建输出目录
    mkdir -p "${LIGHTSHOT_OUTPUT}"

    # 统计
    local count=0

    # 提取文件
    for file in $files; do
        local filename=$(basename "$file")
        echo "  提取: ${filename}"
        adb pull "$file" "${LIGHTSHOT_OUTPUT}/${filename}" > /dev/null 2>&1
        ((count++))

        # 如果指定了删除选项，删除源文件
        if [ "$DELETE_AFTER" = true ]; then
            adb shell "rm \"${file}\""
        fi
    done

    echo -e "${GREEN}成功提取 ${count} 张闪拍图片到: ${LIGHTSHOT_OUTPUT}${NC}"
    echo ""
}

extract_hazard() {
    echo "===== 提取隐患识别结果 ====="
    echo "源路径: ${HAZARD_DIR}"

    # 检查目录是否存在
    if ! adb shell "[ -d ${HAZARD_DIR} ]" 2>/dev/null; then
        echo -e "${YELLOW}隐患识别目录不存在，跳过${NC}"
        return
    fi

    # 获取文件列表
    local files=$(adb shell "ls -1 ${HAZARD_DIR}/ 2>/dev/null" | tr -d '\r')

    if [ -z "$files" ]; then
        echo -e "${YELLOW}隐患识别目录中没有文件${NC}"
        return
    fi

    # 创建输出目录
    mkdir -p "${HAZARD_OUTPUT}"

    # 统计
    local img_count=0
    local json_count=0

    # 提取文件
    for file in $files; do
        local fullpath="${HAZARD_DIR}/${file}"
        echo "  提取: ${file}"
        adb pull "$fullpath" "${HAZARD_OUTPUT}/${file}" > /dev/null 2>&1

        if [[ "$file" == *.jpg ]]; then
            ((img_count++))
        elif [[ "$file" == *.json ]]; then
            ((json_count++))
        fi

        # 如果指定了删除选项，删除源文件
        if [ "$DELETE_AFTER" = true ]; then
            adb shell "rm \"${fullpath}\""
        fi
    done

    echo -e "${GREEN}成功提取 ${img_count} 张图片和 ${json_count} 个 JSON 文件到: ${HAZARD_OUTPUT}${NC}"
    echo ""
}

# 执行提取
if [ "$EXTRACT_ALL" = true ] || [ "$EXTRACT_LIGHTSHOT" = true ]; then
    extract_lightshot
fi

if [ "$EXTRACT_ALL" = true ] || [ "$EXTRACT_HAZARD" = true ]; then
    extract_hazard
fi

# 输出汇总信息
echo "===== 提取完成 ====="
echo "输出目录: ${OUTPUT_DIR}"
echo ""

# 显示提取的文件列表
if [ -d "$LIGHTSHOT_OUTPUT" ]; then
    echo "闪拍图片:"
    ls -1 "${LIGHTSHOT_OUTPUT}"
    echo ""
fi

if [ -d "$HAZARD_OUTPUT" ]; then
    echo "隐患识别结果:"
    ls -1 "${HAZARD_OUTPUT}"
    echo ""
fi

if [ "$DELETE_AFTER" = true ]; then
    echo -e "${YELLOW}已删除眼镜中的源文件${NC}"
fi

echo -e "${GREEN}完成!${NC}"
