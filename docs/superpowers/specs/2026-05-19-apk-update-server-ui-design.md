# APK 更新服务器网页 UI 设计

## 目标

为现有局域网 APK 更新服务器增加一个简单网页发布控制台，让开发者可以在浏览器中上传 APK、手动填写版本信息，并生成眼镜端使用的 `update.json`。

本次只服务本地和局域网测试发布，不做账号登录、多版本管理、历史回滚或公网部署。

## 当前上下文

现有更新服务器位于 `tools/apk_update_server/`：

- `generate_manifest.py`：命令行复制 APK、计算 SHA-256、生成 `releases/latest/update.json`。
- `serve.ps1`：通过 `python -m http.server` 托管当前目录。
- `releases/latest/app.apk`：眼镜端下载的 APK。
- `releases/latest/update.json`：眼镜端检查更新的 manifest。

安卓端只依赖以下两个稳定地址：

- `/releases/latest/update.json`
- `/releases/latest/app.apk`

因此网页 UI 不改变安卓端协议，只替换服务器启动方式。

## 推荐方案

新增 `tools/apk_update_server/server.py`，使用 Python 标准库实现轻量 HTTP 服务。`serve.ps1` 保持为启动入口，但改为运行 `server.py`。

服务端职责：

- 渲染首页发布控制台。
- 读取当前 `releases/latest/update.json`，展示当前已发布版本。
- 接收 APK 上传和表单字段。
- 覆盖写入 `releases/latest/app.apk`。
- 计算 APK 的 SHA-256 和文件大小。
- 写入新的 `releases/latest/update.json`。
- 继续静态托管 `releases/latest/update.json` 和 `releases/latest/app.apk`。

保留 `generate_manifest.py` 作为命令行发布入口，避免网页 UI 出问题时失去 fallback。

## 页面结构

首页 `/` 分为三块。

### 当前发布信息

展示当前 `update.json` 的内容：

- `versionCode`
- `versionName`
- APK 文件大小
- SHA-256
- 是否强制更新
- 更新说明
- manifest 地址
- APK 下载地址

如果 `update.json` 不存在，显示“当前还没有发布版本”，但页面仍可上传新版本。

### 发布新 APK

表单字段：

- APK 文件：必填，只接受 `.apk` 文件名后缀。
- `versionCode`：必填，整数。
- `versionName`：必填，展示给眼镜端用户的版本名，例如 `2.0.6`。
- `releaseNotes`：可选，写入 manifest 并展示在眼镜端更新页。
- `mandatory`：可选，勾选后写入 `true`。

版本填写辅助：

- 如果当前 `update.json` 存在，表单默认填入当前 `versionCode` 和 `versionName`。
- 页面提示 `versionCode` 必须大于当前 App 内版本号，安卓端只用它判断是否有新版本。
- 页面提示发布新版本时应递增 `versionCode`，`versionName` 只是展示名。
- 服务端尝试读取项目 Gradle 配置中的当前工程版本，仅作为辅助展示；解析失败不阻塞发布。

### 访问地址

展示并允许复制：

- 服务器首页地址。
- 眼镜端 manifest 地址。
- APK 下载地址。

地址使用当前请求的 `Host` 动态生成，方便局域网 IP 变化时直接复制。

## 服务端接口

### `GET /`

返回 HTML 页面。

### `POST /publish`

接收 `multipart/form-data`：

- `apk`
- `versionCode`
- `versionName`
- `releaseNotes`
- `mandatory`

校验规则：

- APK 必须存在且文件名以 `.apk` 结尾。
- `versionCode` 必须是正整数。
- `versionName` 不能为空。
- 上传文件不能为空。

成功后：

- 写入 `releases/latest/app.apk`。
- 生成 `update.json`。
- 返回首页并显示发布成功状态。

失败后：

- 返回首页并显示明确错误。
- 不覆盖已有 `update.json`。
- 如果 APK 写入中途失败，尽量不留下半成品 `app.apk`。

### `GET /releases/latest/update.json`

继续返回 manifest 文件，供安卓端检查更新。

### `GET /releases/latest/app.apk`

继续返回 APK 文件，供安卓端下载。

## 数据格式

`update.json` 保持现有格式：

```json
{
  "versionCode": 3,
  "versionName": "2.0.6",
  "apkUrl": "http://192.168.1.152:8080/releases/latest/app.apk",
  "sha256": "...",
  "sizeBytes": 12345678,
  "releaseNotes": "本次更新说明",
  "mandatory": false
}
```

`apkUrl` 使用当前请求的 scheme 和 host 生成，不要求用户手动填写 `baseUrl`。

## 错误处理

页面显示以下错误：

- 未选择 APK。
- APK 文件名不是 `.apk`。
- `versionCode` 不是正整数。
- `versionName` 为空。
- 上传文件为空。
- 写入 APK 或 manifest 失败。

服务端日志输出：

- 启动端口和根目录。
- 每次发布成功的版本号、文件大小和 SHA-256。
- 发布失败的异常信息。

## 测试与验证

实现后验证：

- `python tools/apk_update_server/server.py --help` 可输出参数说明。
- `.\tools\apk_update_server\serve.ps1 -Port 8080` 可启动服务。
- 浏览器打开 `http://127.0.0.1:8080/` 可看到发布控制台。
- 上传一个 APK 后，`releases/latest/app.apk` 和 `update.json` 被生成。
- `update.json` 中 `sha256` 与实际 APK 一致。
- `http://127.0.0.1:8080/releases/latest/update.json` 可直接访问。
- 现有 `generate_manifest.py --help` 仍可用。

## 非目标

- 不做用户登录和权限系统。
- 不做公网安全加固。
- 不做多版本列表、历史回滚或灰度发布。
- 不在网页中自动解析 APK 版本号。
- 不修改安卓端更新协议和眼镜端更新弹窗。
