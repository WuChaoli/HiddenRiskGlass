#!/usr/bin/env python3
"""
图片转 Base64 工具
按照隐患识别页 /ai/auto 接口中 image 字段的发送格式输出

格式: 纯 Base64 字符串，无 data:image/xxx;base64, 前缀，无换行
对应 Android: Base64.encodeToString(bytes, Base64.NO_WRAP)

用法:
    python image_to_base64.py <图片路径或URL>
    python image_to_base64.py ./test.jpg
    python image_to_base64.py https://example.com/image.jpg
"""

import sys
import base64
import urllib.request
import urllib.parse
from pathlib import Path


def is_url(path: str) -> bool:
    """判断输入是否为网络地址"""
    parsed = urllib.parse.urlparse(path)
    return parsed.scheme in ("http", "https")


def fetch_image_from_url(url: str) -> bytes:
    """从网络地址下载图片数据"""
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    })
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.read()


def read_image_from_file(path: str) -> bytes:
    """从本地文件读取图片数据"""
    file_path = Path(path)
    if not file_path.exists():
        raise FileNotFoundError(f"文件不存在: {path}")
    if not file_path.is_file():
        raise ValueError(f"路径不是文件: {path}")
    return file_path.read_bytes()


def image_to_base64(image_bytes: bytes) -> str:
    """
    将图片字节数组转换为 Base64 字符串
    格式与 Android Base64.NO_WRAP 一致: 纯 Base64，无换行，无前缀
    """
    return base64.b64encode(image_bytes).decode("ascii")


def main():
    if len(sys.argv) < 2:
        print("用法: python image_to_base64.py <图片路径或URL>")
        print("示例:")
        print("  python image_to_base64.py ./photo.jpg")
        print("  python image_to_base64.py https://example.com/img.png")
        sys.exit(1)

    source = sys.argv[1]

    try:
        # 读取图片数据
        if is_url(source):
            print(f"正在下载: {source}")
            image_bytes = fetch_image_from_url(source)
        else:
            print(f"正在读取: {source}")
            image_bytes = read_image_from_file(source)

        # 转换为 Base64
        base64_str = image_to_base64(image_bytes)

        # 输出结果
        print(f"\n原始大小: {len(image_bytes)} bytes")
        print(f"Base64 长度: {len(base64_str)} chars")
        print(f"\n--- Base64 输出 (前200字符预览) ---")
        print(base64_str[:200] + "..." if len(base64_str) > 200 else base64_str)
        print(f"\n--- 完整输出 ---")
        print(base64_str)

    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
