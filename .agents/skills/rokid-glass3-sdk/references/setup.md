# Rokid Glass3 SDK 接入说明

## 用途

修改 Gradle 接线、依赖配置或本地 demo 对齐时，先读这个文件。

当前参考资料明确记录了：

- Maven repository: `https://maven.rokid.com/repository/maven-public/`
- SDK dependency: `com.rokid.security:glass3.open.sdk:2.1.5-E`
- Packaging note: handle `libr2aud.so` conflicts with `pickFirst` when needed

## 本地参考工程

这些内容在源文档里被记录为对照参考，不参与当前工程构建：

- `references/rokid/glass3_enterprise_demo.zip`
- `references/rokid/glass3_enterprise_demo_extracted/glassdemo`
- `references/rokid/glass3_enterprise_demo_extracted/glass3sdkphonedemo`

如果当前仓库已经放了这些文件或等价 demo，优先对照：

- `settings.gradle`
- `app/build.gradle`
- `app/src/main/`

## 源文档提到的官方来源

- Quick start: `https://x-docs.rokid.com/docs/快速开始.html`
- Demo download: `https://tatooine.rokidcdn.com/security/sdk/glass3_企业版.zip`

## 默认接入清单

1. Add or verify the Rokid Maven repository.
2. Add or verify `com.rokid.security:glass3.open.sdk:2.1.5-E`.
3. Resolve packaging conflicts such as `libr2aud.so`.
4. Implement `GlassSdk.bindSecurityService()`.
5. Register the client in `onServiceConnected()`.
6. Fetch the required typed services, usually starting with media, message, or device.

## 实践建议

- 优先复用仓库里现有的依赖接线，不要重复新增一套配置块。
- 如果仓库已经有 Rokid 接入文档，先以仓库文档为准，再去改 Gradle。
- 如果行为和预期不一致，先对比企业版 demo 的生命周期顺序和 service 注册方式，再决定是否改代码。
