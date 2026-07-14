## Context

`shengting` 变体是待实现的 Android product flavor，其 AI 服务接口需指向 `jcyxar.yjt.zj.gov.cn:7443` 正式环境。现有设计文档和计划均使用 `http://` 协议。由于变体尚未实现，不存在历史兼容问题，可以直接在文档和后续源码中使用 `https://`。

涉及以下 8 个网络端点：
- 7 个 AI 端点：`/ai/auto`, `/ai/deep`, `/ai/gm`, `/ai/general`, `/ai/general_deep`, `/ai/device`, `/ai/sug_checks`
- 1 个鉴权端点：`/glasses/apis/auth/check`

## Goals / Non-Goals

**Goals:**
- 将 `docs/superpowers/specs/2026-07-09-shengting-flavor-auth-design.md` 中所有 `http://jcyxar.yjt.zj.gov.cn:7443` 改为 `https://jcyxar.yjt.zj.gov.cn:7443`
- 将 `docs/superpowers/plans/2026-07-09-shengting-flavor-auth.md` 中所有 `http://` URL 改为 `https://`
- 后续实现时确保所有 shengting 相关源码使用 HTTPS

**Non-Goals:**
- 不修改 shengting 以外的 flavor（standard/dataBackup）
- 不修改非 shengting 范围的网络接口（`/hxy/apis/`、版本更新等）
- 不修改鉴权协议、AI 请求体/响应体语义

## Decisions

1. **统一替换基地址而非逐条替换**：基地址 `http://jcyxar.yjt.zj.gov.cn:7443` 改为 `https://jcyxar.yjt.zj.gov.cn:7443`，所有以此开头的 URL 自然升级为 HTTPS。端口 `7443` 保持不变，因为 HTTPS 默认端口 443 与服务器实际监听端口不一致。

2. **文档先行，源码跟随**：因为 shengting 尚未实现，先更新设计文档和实施计划中的 URL，后续实现时直接产出含有 HTTPS 的代码。这不产生任何过渡成本。

3. **不增加 SSL 配置**：OkHttp 内置支持 HTTPS，使用系统 CA 证书即可。无需额外配置信任锚点或自定义 SSL Context，除非服务器使用非公开 CA 证书。如有此情况需后续补充。

## Risks / Trade-offs

- [服务器证书] 如果 `jcyxar.yjt.zj.gov.cn:7443` 使用自签名证书或非公开 CA，OkHttp 会拒绝连接。→ 需在真机验证阶段确认证书链，必要时在 `HttpClientProvider` 中配置 SSL Pinning。
- [无回退路径] HTTPS 连接失败不会自动降级到 HTTP。→ 由现有重试机制和错误提示页处理，不做自动降级。
