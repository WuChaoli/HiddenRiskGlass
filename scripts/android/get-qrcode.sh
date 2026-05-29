#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

LOGIN_URL="https://hyx.hzfj-tech.com/hzfj-emerstand/hzfj-emerstand-mobile/mobile/mobileAccount/login"
QRCODE_DATA_URL="https://hyx.hzfj-tech.com/hzfj-emerstand/hzfj-emerstand-mobile/mobile/smartGlasses/getQrcode"
QRCODE_IMAGE_API="https://api.2dcode.biz/v1/create-qr-code"

# 固定参数
COMPANY_ID="1930142827941588994"
TASK_ID="1942780848134230018"
QRCODE_SIZE="512x512"

# 默认值：可通过环境变量或命令行参数覆盖
: "${HZFJ_ACCOUNT_NAME:=}"
: "${HZFJ_CODE:=21A}"

accountName="${1:-$HZFJ_ACCOUNT_NAME}"
code="${2:-$HZFJ_CODE}"

# ── Step 1: 登录获取 token ──────────────────────────────
info "Step 1: 登录获取 token..."
login_resp=$(curl -s -S -X POST "$LOGIN_URL" \
  -H "Content-Type: application/json" \
  -d "{\"code\":\"$code\",\"accountName\":\"$accountName\"}") || die "登录请求失败"

token=$(printf '%s' "$login_resp" | sed -n 's/.*"data"[ ]*:[ ]*"\([^"]*\)".*/\1/p')

if [[ -z "$token" ]]; then
  die "登录失败，无法提取 token。响应: $login_resp"
fi

info "登录成功，token 已获取"

# ── Step 2: 获取二维码数据 ──────────────────────────────
info "Step 2: 获取二维码数据..."
qrcode_resp=$(curl -s -S -X POST "$QRCODE_DATA_URL" \
  -H "Content-Type: application/json" \
  -H "token: $token" \
  -d "{\"companyId\":\"$COMPANY_ID\",\"taskId\":\"$TASK_ID\"}") || die "获取二维码数据请求失败"

qrcode_data=$(printf '%s' "$qrcode_resp" | sed -n 's/.*"data"[ ]*:[ ]*"\([^"]*\)".*/\1/p')

if [[ -z "$qrcode_data" ]]; then
  die "获取二维码数据失败。响应: $qrcode_resp"
fi

info "二维码数据已获取"

# ── Step 3: 生成二维码图片链接并用浏览器打开 ──────────────
info "Step 3: 生成二维码图片..."

# 使用 python3 进行 URL 编码（比纯 bash 更可靠）
if command -v python3 >/dev/null 2>&1; then
  encoded_data=$(printf '%s' "$qrcode_data" | python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.stdin.read().strip()), end="")')
elif command -v python >/dev/null 2>&1; then
  encoded_data=$(printf '%s' "$qrcode_data" | python -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.stdin.read().strip()), end="")')
else
  die "未找到 python3 或 python，无法进行 URL 编码"
fi

qrcode_url="${QRCODE_IMAGE_API}?data=${encoded_data}&size=${QRCODE_SIZE}"

info "二维码图片链接: $qrcode_url"

info "正在用浏览器打开..."
if command -v start >/dev/null 2>&1; then
  # Windows (Git Bash / MSYS)
  start "$qrcode_url"
elif command -v xdg-open >/dev/null 2>&1; then
  # Linux
  xdg-open "$qrcode_url"
elif command -v open >/dev/null 2>&1; then
  # macOS
  open "$qrcode_url"
else
  info "未找到可用的浏览器打开命令，请手动复制以下链接到浏览器打开:"
  info "$qrcode_url"
fi
