#!/bin/bash

# Hazard Capture 管理脚本
# 用于提取、查看和清理眼镜上的隐患识别图片

set -e

# 配置
PACKAGE_NAME="com.rokid.glesse"
REMOTE_DIR="/sdcard/Android/data/${PACKAGE_NAME}/files/HazardCaptures"
LOCAL_DIR="./hazard_captures"

# 自动检测 adb 路径
find_adb() {
    # 检查 PATH 中的 adb
    if command -v adb &> /dev/null; then
        echo "adb"
        return
    fi

    # 检查 Windows 默认路径 (通过 WSL)
    if [ -f "/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then
        echo "/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        return
    fi

    # 检查其他常见 Windows 路径
    if [ -f "/mnt/c/Program Files (x86)/Android/android-sdk/platform-tools/adb.exe" ]; then
        echo "/mnt/c/Program Files (x86)/Android/android-sdk/platform-tools/adb.exe"
        return
    fi

    # 检查 ANDROID_HOME/Sdk
    if [ -n "$ANDROID_HOME" ] && [ -f "$ANDROID_HOME/platform-tools/adb" ]; then
        echo "$ANDROID_HOME/platform-tools/adb"
        return
    fi

    if [ -n "$ANDROID_SDK_ROOT" ] && [ -f "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
        echo "$ANDROID_SDK_ROOT/platform-tools/adb"
        return
    fi

    # 未找到
    echo ""
}

ADB_CMD=$(find_adb)

# 如果未找到 adb，提示用户
if [ -z "$ADB_CMD" ]; then
    echo -e "${RED}错误: 未找到 adb 命令${NC}"
    echo "请确保以下之一："
    echo "  1. Android SDK platform-tools 已添加到 PATH"
    echo "  2. 设置 ANDROID_HOME 环境变量"
    echo "  3. 安装到默认位置: C:\Users\%USERNAME%\AppData\Local\Android\Sdk"
    exit 1
fi

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印帮助信息
show_help() {
    cat << EOF
Hazard Capture 管理脚本

用法: bash scripts/hazard_capture_manager.sh [命令] [选项]

命令:
    list, ls          列出远程目录中的所有文件
    pull, p           拉取所有文件到本地 (默认: ./hazard_captures/)
    pull-new, pn      只拉取新文件 (本地已存在的不拉取)
    view, v           查看最新的 JSON 内容
    count, c          统计文件数量
    clean-old, co     清理旧文件，只保留最近的 N 个 (默认: 100)
    clean-all, ca     清理所有文件
    sync, s           拉取所有文件并清理远程 (转移模式)

选项:
    -d, --dir DIR     指定本地保存目录 (默认: ./hazard_captures)
    -n, --keep N      清理时保留的文件数量 (默认: 100)
    -h, --help        显示帮助信息

adb 路径自动检测:
    脚本会自动检测以下位置的 adb:
    1. PATH 环境变量中的 adb
    2. Windows: C:\Users\%USERNAME%\AppData\Local\Android\Sdk\platform-tools\adb.exe
    3. 环境变量 ANDROID_HOME 或 ANDROID_SDK_ROOT

示例:
    bash scripts/hazard_capture_manager.sh list              # 查看远程文件列表
    bash scripts/hazard_capture_manager.sh pull              # 拉取所有文件
    bash scripts/hazard_capture_manager.sh pull-new          # 只拉取新文件
    bash scripts/hazard_capture_manager.sh view              # 查看最新 JSON 内容
    bash scripts/hazard_capture_manager.sh count             # 统计文件数量
    bash scripts/hazard_capture_manager.sh clean-old -n 50   # 只保留最近的 50 张
    bash scripts/hazard_capture_manager.sh clean-all         # 清空所有文件
    bash scripts/hazard_capture_manager.sh sync              # 拉取并清空远程

EOF
}

# 检查 adb 连接
check_adb() {
    if [ -z "$ADB_CMD" ]; then
        echo -e "${RED}错误: 未找到 adb 命令${NC}"
        echo "请确保 Android SDK platform-tools 已安装并添加到 PATH"
        exit 1
    fi

    if ! "$ADB_CMD" devices | grep -qE "device[[:space:]]*$"; then
        echo -e "${RED}错误: 未检测到连接的设备${NC}"
        echo "请连接眼镜并启用调试模式"
        exit 1
    fi
}

# 检查远程目录是否存在
check_remote_dir() {
    if ! "$ADB_CMD" shell "[ -d ${REMOTE_DIR} ]"; then
        echo -e "${YELLOW}警告: 远程目录不存在${NC}"
        echo "路径: ${REMOTE_DIR}"
        return 1
    fi
    return 0
}

# 列出文件
list_files() {
    check_adb
    if ! check_remote_dir; then
        return
    fi

    echo -e "${BLUE}=== 远程文件列表 ===${NC}"
    echo "路径: ${REMOTE_DIR}"
    echo ""

    "$ADB_CMD" shell "ls -la ${REMOTE_DIR}/" | while read line; do
        # 高亮 .jpg 和 .json 文件
        if echo "$line" | grep -q "\.jpg$"; then
            echo -e "${GREEN}${line}${NC}"
        elif echo "$line" | grep -q "\.json$"; then
            echo -e "${YELLOW}${line}${NC}"
        else
            echo "$line"
        fi
    done

    local count=$("$ADB_CMD" shell "ls ${REMOTE_DIR}/*.jpg 2>/dev/null | wc -l" | tr -d '\r')
    echo ""
    echo -e "图片数量: ${GREEN}${count}${NC}"
}

# 统计文件
count_files() {
    check_adb
    if ! check_remote_dir; then
        return
    fi

    local jpg_count=$("$ADB_CMD" shell "ls ${REMOTE_DIR}/*.jpg 2>/dev/null | wc -l" | tr -d '\r')
    local json_count=$("$ADB_CMD" shell "ls ${REMOTE_DIR}/*.json 2>/dev/null | wc -l" | tr -d '\r')
    local total_size=$("$ADB_CMD" shell "du -sh ${REMOTE_DIR} 2>/dev/null | cut -f1" | tr -d '\r')

    echo -e "${BLUE}=== 文件统计 ===${NC}"
    echo -e "图片文件 (.jpg): ${GREEN}${jpg_count}${NC}"
    echo -e "JSON 文件 (.json): ${YELLOW}${json_count}${NC}"
    echo -e "总大小: ${BLUE}${total_size}${NC}"
}

# 拉取所有文件
pull_files() {
    local pull_new_only=${1:-false}
    check_adb
    if ! check_remote_dir; then
        return
    fi

    mkdir -p "${LOCAL_DIR}"
    echo -e "${BLUE}=== 拉取文件 ===${NC}"
    echo "远程: ${REMOTE_DIR}"
    echo "本地: ${LOCAL_DIR}"
    echo ""

    local pulled=0
    local skipped=0

    # 获取所有文件列表
    local files=$("$ADB_CMD" shell "ls ${REMOTE_DIR}/" | grep -E "\.(jpg|json)$" | tr -d '\r')

    for file in ${files}; do
        local local_file="${LOCAL_DIR}/${file}"

        if [ "$pull_new_only" = true ] && [ -f "${local_file}" ]; then
            echo -e "跳过 (已存在): ${YELLOW}${file}${NC}"
            ((skipped++))
            continue
        fi

        echo -n "拉取: ${file} ... "
        if "$ADB_CMD" pull "${REMOTE_DIR}/${file}" "${local_file}" > /dev/null 2>&1; then
            echo -e "${GREEN}成功${NC}"
            ((pulled++))
        else
            echo -e "${RED}失败${NC}"
        fi
    done

    echo ""
    echo -e "拉取: ${GREEN}${pulled}${NC}, 跳过: ${YELLOW}${skipped}${NC}"
}

# 查看最新 JSON
view_latest() {
    check_adb
    if ! check_remote_dir; then
        return
    fi

    local latest_json=$("$ADB_CMD" shell "ls -t ${REMOTE_DIR}/*.json 2>/dev/null | head -1" | tr -d '\r')

    if [ -z "$latest_json" ] || [ "$latest_json" = "${REMOTE_DIR}/*.json" ]; then
        echo -e "${YELLOW}没有找到 JSON 文件${NC}"
        return
    fi

    echo -e "${BLUE}=== 最新检测记录 ===${NC}"
    echo "文件: $(basename ${latest_json})"
    echo ""
    "$ADB_CMD" shell "cat '${latest_json}'"
    echo ""

    # 同时显示对应的图片信息
    local latest_jpg="${latest_json%.json}.jpg"
    if "$ADB_CMD" shell "[ -f '${latest_jpg}' ]"; then
        local size=$("$ADB_CMD" shell "ls -lh '${latest_jpg}' | awk '{print \$5}'" | tr -d '\r')
        echo -e "对应图片: ${GREEN}$(basename ${latest_jpg})${NC} (${size})"
    fi
}

# 清理旧文件
clean_old() {
    local keep_count=${1:-100}
    check_adb
    if ! check_remote_dir; then
        return
    fi

    echo -e "${BLUE}=== 清理旧文件 ===${NC}"
    echo "保留最近: ${keep_count} 张图片"
    echo ""

    local total_count=$("$ADB_CMD" shell "ls ${REMOTE_DIR}/*.jpg 2>/dev/null | wc -l" | tr -d '\r')

    if [ "$total_count" -le "$keep_count" ]; then
        echo -e "${YELLOW}当前只有 ${total_count} 张图片，无需清理${NC}"
        return
    fi

    echo "当前图片数: ${total_count}"

    # 按时间排序，获取要删除的文件
    local files_to_delete=$("$ADB_CMD" shell "ls -t ${REMOTE_DIR}/*.jpg | tail -n +$((keep_count + 1))" | tr -d '\r')

    local deleted=0
    for jpg_file in ${files_to_delete}; do
        local json_file="${jpg_file%.jpg}.json"
        local base_name=$(basename "${jpg_file}")

        echo -n "删除: ${base_name} ... "
        "$ADB_CMD" shell "rm '${jpg_file}'" && "$ADB_CMD" shell "rm '${json_file}'" 2>/dev/null
        echo -e "${GREEN}完成${NC}"
        ((deleted++))
    done

    echo ""
    echo -e "已删除: ${RED}${deleted}${NC} 组文件"
}

# 清理所有文件
clean_all() {
    check_adb
    if ! check_remote_dir; then
        return
    fi

    echo -e "${RED}警告: 这将删除所有隐患识别记录！${NC}"
    read -p "确认删除所有文件? (yes/no): " confirm

    if [ "$confirm" = "yes" ]; then
        "$ADB_CMD" shell "rm -f ${REMOTE_DIR}/*"
        echo -e "${GREEN}已清空目录${NC}"
    else
        echo "已取消"
    fi
}

# 同步模式 (拉取后删除远程)
sync_files() {
    check_adb
    if ! check_remote_dir; then
        return
    fi

    echo -e "${YELLOW}警告: 此操作将拉取所有文件并删除远程副本${NC}"
    read -p "确认继续? (yes/no): " confirm

    if [ "$confirm" != "yes" ]; then
        echo "已取消"
        return
    fi

    pull_files false

    echo ""
    echo -n "清理远程文件 ... "
    "$ADB_CMD" shell "rm -f ${REMOTE_DIR}/*"
    echo -e "${GREEN}完成${NC}"
}

# 主程序
main() {
    local command=""
    local keep_count=100

    # 解析参数
    while [[ $# -gt 0 ]]; do
        case $1 in
            list|ls)
                command="list"
                shift
                ;;
            pull|p)
                command="pull"
                shift
                ;;
            pull-new|pn)
                command="pull-new"
                shift
                ;;
            view|v)
                command="view"
                shift
                ;;
            count|c)
                command="count"
                shift
                ;;
            clean-old|co)
                command="clean-old"
                shift
                ;;
            clean-all|ca)
                command="clean-all"
                shift
                ;;
            sync|s)
                command="sync"
                shift
                ;;
            -d|--dir)
                LOCAL_DIR="$2"
                shift 2
                ;;
            -n|--keep)
                keep_count="$2"
                shift 2
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            *)
                echo "未知选项: $1"
                show_help
                exit 1
                ;;
        esac
    done

    # 执行命令
    case $command in
        list)
            list_files
            ;;
        pull)
            pull_files false
            ;;
        pull-new)
            pull_files true
            ;;
        view)
            view_latest
            ;;
        count)
            count_files
            ;;
        clean-old)
            clean_old $keep_count
            ;;
        clean-all)
            clean_all
            ;;
        sync)
            sync_files
            ;;
        *)
            show_help
            exit 1
            ;;
    esac
}

main "$@"
