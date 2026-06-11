# network/ — HTTP 网络客户端

## 业务概述
提供全局 OkHttp 单例，供 `hiddenrisk/` 和 `updater/` 模块进行 HTTP 请求和 SSE 连接。统一管理超时配置与连接池，避免多处独立创建 OkHttpClient 导致的资源浪费。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `HttpClientProvider.kt` | OkHttpClient 单例提供，区分巡检 API 客户端（30s 超时）和 SSE 长连接客户端（无读超时） | `inspectionClient`、`sseClient` |

## 依赖关系

- **依赖：** OkHttp 4.12.0
- **被依赖：** `hiddenrisk/`（在线推理、隐患上传）、`updater/`（版本检查、APK 下载）
