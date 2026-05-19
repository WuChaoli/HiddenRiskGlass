#!/usr/bin/env python3
import argparse
import html
import json
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass
from hashlib import sha256
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import locale
from email.parser import BytesParser
from io import BytesIO
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = ROOT.parent.parent
LATEST_DIR = ROOT / "releases" / "latest"
APK_PATH = LATEST_DIR / "app.apk"
MANIFEST_PATH = LATEST_DIR / "update.json"


@dataclass
class PublishResult:
    kind: str
    message: str


def sha256_file(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest() -> dict | None:
    if not MANIFEST_PATH.is_file():
        return None
    try:
        return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def write_manifest(manifest: dict) -> None:
    LATEST_DIR.mkdir(parents=True, exist_ok=True)
    MANIFEST_PATH.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def parse_positive_int(raw: str, field_name: str) -> int:
    value = raw.strip()
    if not value:
        raise ValueError(f"{field_name} 不能为空")
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ValueError(f"{field_name} 必须是正整数") from exc
    if parsed <= 0:
        raise ValueError(f"{field_name} 必须是正整数")
    return parsed


def read_project_version() -> dict | None:
    build_file = PROJECT_ROOT / "app" / "build.gradle"
    if not build_file.is_file():
        return None
    try:
        text = build_file.read_text(encoding="utf-8")
    except OSError:
        return None

    version_code_match = re.search(r"\bversionCode\s+(\d+)", text)
    version_name_match = re.search(r'\bversionName\s+"([^"]+)"', text)
    if not version_code_match and not version_name_match:
        return None

    result: dict[str, int | str] = {}
    if version_code_match:
        result["versionCode"] = int(version_code_match.group(1))
    if version_name_match:
        result["versionName"] = version_name_match.group(1)
    return result


def publish_apk(
    *,
    apk_field,
    version_code_raw: str,
    version_name_raw: str,
    release_notes: str,
    mandatory_raw: str | None,
    base_url: str,
) -> dict:
    if apk_field is None or not getattr(apk_field, "filename", ""):
        raise ValueError("请选择 APK 文件")

    filename = Path(apk_field.filename).name
    if not filename.lower().endswith(".apk"):
        raise ValueError("APK 文件名必须以 .apk 结尾")

    version_code = parse_positive_int(version_code_raw, "versionCode")
    version_name = version_name_raw.strip()
    if not version_name:
        raise ValueError("versionName 不能为空")

    LATEST_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(delete=False, dir=LATEST_DIR, suffix=".upload") as temp_file:
        temp_path = Path(temp_file.name)
        shutil.copyfileobj(apk_field.file, temp_file)

    try:
        if temp_path.stat().st_size <= 0:
            temp_path.unlink(missing_ok=True)
            raise ValueError("上传文件为空")

        shutil.move(str(temp_path), APK_PATH)
        manifest = {
            "versionCode": version_code,
            "versionName": version_name,
            "apkUrl": f"{base_url.rstrip('/')}/releases/latest/app.apk",
            "sha256": sha256_file(APK_PATH),
            "sizeBytes": APK_PATH.stat().st_size,
            "releaseNotes": release_notes.strip(),
            "mandatory": mandatory_raw == "on",
        }
        write_manifest(manifest)
        return manifest
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise


def format_bytes(size: int | None) -> str:
    if size is None:
        return "-"
    units = ["B", "KB", "MB", "GB"]
    value = float(size)
    unit = units[0]
    for unit in units:
        if value < 1024 or unit == units[-1]:
            break
        value /= 1024
    if unit == "B":
        return f"{int(value)} {unit}"
    return f"{value:.1f} {unit}"


def build_base_url(handler: SimpleHTTPRequestHandler) -> str:
    host = handler.headers.get("Host", f"127.0.0.1:{handler.server.server_port}")
    return f"http://{host}"


def escaped(value) -> str:
    return html.escape("" if value is None else str(value), quote=True)


def render_page(handler: SimpleHTTPRequestHandler, result: PublishResult | None = None) -> str:
    manifest = load_manifest()
    project_version = read_project_version()
    base_url = build_base_url(handler)
    manifest_url = f"{base_url}/releases/latest/update.json"
    apk_url = f"{base_url}/releases/latest/app.apk"

    current_version_code = escaped(manifest.get("versionCode") if manifest else "")
    current_version_name = escaped(manifest.get("versionName") if manifest else "")
    release_notes = escaped(manifest.get("releaseNotes") if manifest else "")
    mandatory_checked = "checked" if manifest and manifest.get("mandatory") else ""

    if manifest:
        current_block = f"""
        <dl class="meta-grid">
          <div><dt>versionCode</dt><dd>{escaped(manifest.get("versionCode"))}</dd></div>
          <div><dt>versionName</dt><dd>{escaped(manifest.get("versionName"))}</dd></div>
          <div><dt>APK size</dt><dd>{format_bytes(manifest.get("sizeBytes"))}</dd></div>
          <div><dt>Mandatory</dt><dd>{'Yes' if manifest.get('mandatory') else 'No'}</dd></div>
          <div class="wide"><dt>SHA-256</dt><dd class="mono">{escaped(manifest.get("sha256"))}</dd></div>
          <div class="wide"><dt>Release notes</dt><dd>{escaped(manifest.get("releaseNotes") or "未填写")}</dd></div>
        </dl>
        """
    else:
        current_block = '<p class="empty">当前还没有发布版本。上传 APK 后会生成 update.json。</p>'

    if project_version:
        project_version_text = (
            f"当前工程版本：versionCode={escaped(project_version.get('versionCode', '-'))}, "
            f"versionName={escaped(project_version.get('versionName', '-'))}"
        )
    else:
        project_version_text = "未能从 app/build.gradle 解析当前工程版本；请以实际构建版本为准。"

    result_block = ""
    if result:
        result_block = f'<div class="alert {escaped(result.kind)}">{escaped(result.message)}</div>'

    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>APK 更新服务器</title>
  <style>
    :root {{
      color-scheme: light;
      --bg: #f5f7fb;
      --panel: #ffffff;
      --text: #1e293b;
      --muted: #64748b;
      --line: #d8dee9;
      --accent: #0f766e;
      --accent-dark: #115e59;
      --danger: #b42318;
      --success: #067647;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: "Segoe UI", Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
    }}
    main {{
      width: min(1040px, calc(100vw - 32px));
      margin: 28px auto 48px;
    }}
    h1 {{ margin: 0 0 8px; font-size: 28px; }}
    h2 {{ margin: 0 0 16px; font-size: 19px; }}
    p {{ line-height: 1.6; }}
    .subtitle {{ margin: 0 0 24px; color: var(--muted); }}
    .panel {{
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 22px;
      margin-top: 18px;
      box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
    }}
    .meta-grid {{
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
      margin: 0;
    }}
    .meta-grid div {{
      border: 1px solid var(--line);
      border-radius: 6px;
      padding: 12px;
      min-width: 0;
    }}
    .meta-grid .wide {{ grid-column: 1 / -1; }}
    dt {{ color: var(--muted); font-size: 13px; margin-bottom: 6px; }}
    dd {{ margin: 0; overflow-wrap: anywhere; }}
    .mono {{ font-family: Consolas, monospace; font-size: 13px; }}
    form {{
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 16px;
    }}
    label {{ display: grid; gap: 7px; font-weight: 600; }}
    input, textarea {{
      width: 100%;
      border: 1px solid var(--line);
      border-radius: 6px;
      padding: 10px 12px;
      font: inherit;
      background: white;
    }}
    textarea {{ min-height: 110px; resize: vertical; }}
    .full {{ grid-column: 1 / -1; }}
    .hint {{ color: var(--muted); font-size: 13px; font-weight: 400; line-height: 1.5; }}
    .checkbox-row {{
      display: flex;
      align-items: center;
      gap: 10px;
      font-weight: 600;
    }}
    .checkbox-row input {{ width: auto; }}
    button {{
      width: fit-content;
      min-width: 132px;
      border: 0;
      border-radius: 6px;
      padding: 11px 18px;
      background: var(--accent);
      color: white;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }}
    button:hover {{ background: var(--accent-dark); }}
    .url-list {{ display: grid; gap: 12px; }}
    .url-item {{
      display: grid;
      grid-template-columns: 150px 1fr auto;
      gap: 10px;
      align-items: center;
    }}
    .url-item code {{
      padding: 9px 10px;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: #f8fafc;
      overflow-wrap: anywhere;
    }}
    .copy-button {{
      min-width: 72px;
      padding: 8px 12px;
      background: #334155;
    }}
    .alert {{
      border-radius: 6px;
      padding: 12px 14px;
      margin-bottom: 16px;
      font-weight: 600;
    }}
    .alert.success {{ color: var(--success); background: #ecfdf3; border: 1px solid #abefc6; }}
    .alert.error {{ color: var(--danger); background: #fef3f2; border: 1px solid #fecdca; }}
    .empty {{ color: var(--muted); margin: 0; }}
    @media (max-width: 720px) {{
      .meta-grid, form {{ grid-template-columns: 1fr; }}
      .url-item {{ grid-template-columns: 1fr; }}
      button {{ width: 100%; }}
    }}
  </style>
</head>
<body>
  <main>
    <h1>APK 更新服务器</h1>
    <p class="subtitle">上传 APK 并生成眼镜端使用的 update.json。此页面仅用于本地和局域网测试发布。</p>
    {result_block}

    <section class="panel">
      <h2>当前发布信息</h2>
      {current_block}
    </section>

    <section class="panel">
      <h2>发布新 APK</h2>
      <form action="/publish" method="post" enctype="multipart/form-data">
        <label class="full">
          APK 文件
          <input type="file" name="apk" accept=".apk" required>
          <span class="hint">请选择已构建完成的 APK，例如 app-standard-debug.apk。</span>
        </label>
        <label>
          versionCode
          <input type="number" name="versionCode" min="1" step="1" value="{current_version_code}" required>
          <span class="hint">安卓端只用 versionCode 判断是否有更新；发布新版本时需要大于当前 App 内版本号。</span>
        </label>
        <label>
          versionName
          <input type="text" name="versionName" value="{current_version_name}" required>
          <span class="hint">展示给用户看的版本名，例如 2.0.6。</span>
        </label>
        <label class="full">
          releaseNotes
          <textarea name="releaseNotes">{release_notes}</textarea>
          <span class="hint">{escaped(project_version_text)}</span>
        </label>
        <label class="checkbox-row full">
          <input type="checkbox" name="mandatory" {mandatory_checked}>
          强制更新
        </label>
        <button type="submit">发布 APK</button>
      </form>
    </section>

    <section class="panel">
      <h2>访问地址</h2>
      <div class="url-list">
        <div class="url-item"><strong>首页</strong><code>{escaped(base_url)}/</code><button class="copy-button" data-copy="{escaped(base_url)}/">复制</button></div>
        <div class="url-item"><strong>Manifest</strong><code>{escaped(manifest_url)}</code><button class="copy-button" data-copy="{escaped(manifest_url)}">复制</button></div>
        <div class="url-item"><strong>APK</strong><code>{escaped(apk_url)}</code><button class="copy-button" data-copy="{escaped(apk_url)}">复制</button></div>
      </div>
    </section>
  </main>
  <script>
    document.querySelectorAll("[data-copy]").forEach((button) => {{
      button.addEventListener("click", async () => {{
        await navigator.clipboard.writeText(button.dataset.copy);
        const oldText = button.textContent;
        button.textContent = "已复制";
        setTimeout(() => button.textContent = oldText, 1200);
      }});
    }});
  </script>
</body>
</html>
"""


class ApkUpdateRequestHandler(SimpleHTTPRequestHandler):
    server_version = "ApkUpdateServer/1.0"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self.respond_html(render_page(self))
            return
        super().do_GET()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/publish":
            self.send_error(HTTPStatus.NOT_FOUND, "Not found")
            return
        self.handle_publish()

    def handle_publish(self) -> None:
        try:
            content_type = self.headers.get("Content-Type", "")
            if not content_type.startswith("multipart/form-data"):
                raise ValueError("请求必须使用 multipart/form-data")

            content_length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(content_length)

            msg = BytesParser().parsebytes(
                b"Content-Type: " + content_type.encode("ascii") + b"\r\n\r\n" + raw_body
            )

            fields: dict[str, str] = {}
            apk_data: bytes | None = None
            apk_filename: str = ""

            if msg.is_multipart():
                for part in msg.get_payload():
                    name = part.get_param("name", header="content-disposition")
                    filename = part.get_filename()
                    payload = part.get_payload(decode=True)
                    if payload is None:
                        continue
                    if filename and name == "apk":
                        apk_data = payload
                        apk_filename = filename
                    elif name and not filename:
                        # 先尝试 UTF-8，失败则回退到系统默认编码（Windows 中文环境 curl 发 GBK）
                        try:
                            fields[name] = payload.decode("utf-8")
                        except UnicodeDecodeError:
                            fields[name] = payload.decode(locale.getpreferredencoding())

            # 构造一个与 cgi.FieldStorage 兼容的 apk_field 对象
            class ApkField:
                filename = apk_filename
                file = BytesIO(apk_data) if apk_data else None

            apk_field = ApkField()

            manifest = publish_apk(
                apk_field=apk_field,
                version_code_raw=fields.get("versionCode", ""),
                version_name_raw=fields.get("versionName", ""),
                release_notes=fields.get("releaseNotes", ""),
                mandatory_raw=fields.get("mandatory"),
                base_url=build_base_url(self),
            )
            print(
                "Published APK "
                f"versionCode={manifest['versionCode']} "
                f"versionName={manifest['versionName']} "
                f"sizeBytes={manifest['sizeBytes']} "
                f"sha256={manifest['sha256']}"
            )
            result = PublishResult("success", f"发布成功：{manifest['versionName']} ({manifest['versionCode']})")
        except Exception as exc:
            print(f"Publish failed: {exc}", file=sys.stderr)
            result = PublishResult("error", str(exc))

        self.respond_html(render_page(self, result))

    def respond_html(self, body: str) -> None:
        encoded = body.encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Serve the local APK update publishing UI.")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind. Default: 0.0.0.0")
    parser.add_argument("--port", type=int, default=8080, help="Port to bind. Default: 8080")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    LATEST_DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), ApkUpdateRequestHandler)
    print(f"Serving APK update UI from {ROOT}")
    print(f"URL: http://{args.host}:{args.port}/")
    print(f"Manifest: http://{args.host}:{args.port}/releases/latest/update.json")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping APK update server")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
