## ADDED Requirements

### Requirement: shengting flavor 的 AI 端点使用 HTTPS 协议

shengting 变体的 7 个 AI 服务接口 SHALL 使用 `https://jcyxar.yjt.zj.gov.cn:7443` 作为基地址。
鉴权接口 SHALL 同样使用 `https://jcyxar.yjt.zj.gov.cn:7443` 作为基地址。
standard 和 dataBackup 变体 MUST NOT 受影响。

| 配置项 | URL |
| --- | --- |
| `aiAutoApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/auto` |
| `aiDeepApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/deep` |
| `aiGmApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/gm` |
| `aiGeneralApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general` |
| `aiGeneralDeepApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general_deep` |
| `aiDeviceApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/device` |
| `aiSuggestionChecksApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/sug_checks` |
| 鉴权 URL | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/auth/check` |

#### Scenario: AI 端点解析为 HTTPS URL
- **WHEN** `InspectionConfigRepository.buildConfig` 应用 shengting overlay
- **THEN** 所有 7 个 AI 端点 SHALL 以 `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/` 开头

#### Scenario: 鉴权端点解析为 HTTPS URL
- **WHEN** `ShengtingAuthService.AUTH_URL` 被访问
- **THEN** SHALL 为 `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/auth/check`

#### Scenario: standard flavor 不受影响
- **WHEN** standard 变体加载配置
- **THEN** AI 端点 SHALL 保持原有 `http://183.147.142.133:*` 地址
