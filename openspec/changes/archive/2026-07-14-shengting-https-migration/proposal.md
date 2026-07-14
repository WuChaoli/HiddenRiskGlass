## Why

`shengting` 变体计划使用的网络接口基地址为 `http://jcyxar.yjt.zj.gov.cn:7443`，但正式生产环境应使用 HTTPS 协议以保证通信安全。当前该变体尚未实现，是修改协议的最佳时机——避免先实现后整改。

## What Changes

1. **更新设计文档**：将 `docs/superpowers/specs/2026-07-09-shengting-flavor-auth-design.md` 中所有 `http://jcyxar.yjt.zj.gov.cn:7443` 改为 `https://jcyxar.yjt.zj.gov.cn:7443`
2. **更新实施计划**：将 `docs/superpowers/plans/2026-07-09-shengting-flavor-auth.md` 中所有 `http://` URL 改为 `https://`
3. **实现时直接使用 HTTPS**：后续创建 `inspection_config.shengting.jsonc`、`ShengtingAuthService.kt` 等源码时，URL 均使用 `https://` 前缀

## Capabilities

### New Capabilities
- `shengting-flavor`: shengting Android product flavor，包含 7 个 AI 端点通过 HTTPS 协议连接正式环境，以及配套的鉴权模块

### Modified Capabilities
无（现有 specs 中无 shengting 相关规格）

## Impact

- 设计文档 `docs/superpowers/specs/2026-07-09-shengting-flavor-auth-design.md`：URL 协议从 http 改为 https
- 实施计划 `docs/superpowers/plans/2026-07-09-shengting-flavor-auth.md`：所有示例代码和配置中的 URL 协议从 http 改为 https
- 后续创建的所有 shengting 相关源码文件：直接使用 HTTPS URL
