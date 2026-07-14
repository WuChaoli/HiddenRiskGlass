# shengting 变体鉴权与 AI 接口迁移设计

## 背景

当前工程已有 `standard` 与 `dataBackup` 两个 Android product flavor，配置系统通过
`inspection_config.base.jsonc` 加 flavor overlay 的方式管理巡检参数和网络端点。需要新增
`shengting` 变体，将 7 个 AI 服务接口切到 shengting 正式环境，并在企业扫码成功后完成鉴权，
后续 AI 服务请求带上 `Authorization` 请求头。

本设计只覆盖 AI 服务接口，即以下 7 个 `/ai/*` 端点：

1. `/ai/auto`
2. `/ai/deep`
3. `/ai/gm`
4. `/ai/general`
5. `/ai/general_deep`
6. `/ai/device`
7. `/ai/sug_checks`

本次不迁移、不加鉴权的接口包括 `/hxy/apis/third/smartGlasses`、隐患保存、结束巡检、版本更新、
`has_hazard_answer`。

## 成功标准

1. `shengtingDebug` 可构建，并加载 `inspection_config.shengting.jsonc`。
2. `shengting` 变体的 7 个 AI 端点均指向 `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/...`。
3. 企业扫码并成功调用 `getObjectMessage` 后，应用使用 Rokid SN 与当天日期完成鉴权。
4. 后续 7 个 AI 请求均带 `Authorization: <token>`。
5. 鉴权失败会自动重试一次；仍失败则弹出提示并返回主菜单。
6. AI 请求收到 `401` 或 `403` 会刷新 token 并重试原请求一次；仍失败则弹出提示并返回主菜单。
7. `standard` 与 `dataBackup` 行为保持不变。

## Flavor 与地址配置

新增 `shengting` product flavor，继续使用现有 `edition` 维度。新增
`app/src/main/assets/inspection_config.shengting.jsonc`，只覆盖 7 个 AI 端点。

shengting 正式环境变量为：

- `baseUrl`: `https://jcyxar.yjt.zj.gov.cn`
- `xf_port`: `7443`
- `gm_port`: `7443`
- `prefix`: `/glasses/apis/proxy`

最终端点为：

| 配置项 | URL |
| --- | --- |
| `aiAutoApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/auto` |
| `aiDeepApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/deep` |
| `aiGmApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/gm` |
| `aiGeneralApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general` |
| `aiGeneralDeepApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general_deep` |
| `aiDeviceApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/device` |
| `aiSuggestionChecksApi.url` | `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/sug_checks` |

## 鉴权协议

以 `scripts/java/AESUtil.java` 为协议基准。Android 侧新增可运行的小工具类实现同等加密能力，
不直接依赖该参考文件中的 Spring、fastjson、lombok 相关代码。

加密规则：

- 算法：`AES/ECB/PKCS5Padding`
- 密钥规格：`SecretKeySpec(key.getBytes(), "AES")`
- 输出：Base64 字符串
- 固定密钥：`Btm/Cb6N6glbcOEvjV8qGnyQELjWFUkD`

AES 明文为紧凑 JSON：

```json
{"snCode":"<RokidSdkManager.getSerialNumber()>","date":"yyyy-MM-dd"}
```

鉴权请求：

- Method: `POST`
- URL: `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/auth/check`
- Body:

```json
{"body":"<AES密文>"}
```

鉴权响应成功条件：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "<token>"
}
```

`code == 200` 且 `data` 为非空字符串时，`data` 即后续 AI 请求的 token。请求头格式为：

```text
Authorization: <token>
```

## 模块边界

新增 shengting 鉴权模块，建议放在 `com.rokid.glass.network` 或与 AI 链路更近的包内，保持以下职责：

### ShengtingAuthService

负责一次真实鉴权请求：

1. 从调用方获得或读取 SN。
2. 使用当前日期生成 `yyyy-MM-dd`。
3. 构造 AES 明文并加密。
4. 调用鉴权接口。
5. 解析 `code == 200 && data 非空`。

### ShengtingAuthManager

负责 flavor 判断、token 缓存与刷新：

1. 仅 `BuildConfig.FLAVOR == "shengting"` 时启用。
2. 提供 `getToken()`，无 token 时触发鉴权。
3. 提供 `refreshToken()`，用于 `401/403` 后强制刷新。
4. 提供 `clear()`，用于返回主菜单或企业身份清理时释放 token。
5. 保证同一时间只有一个鉴权请求在途，避免并发 AI 请求重复刷新 token。

## 业务流程

企业扫码链路保持原有顺序：

1. 解析二维码并写入 `InspectionWorkflowSession`。
2. 调用现有 `getObjectMessage`。
3. `getObjectMessage` 成功后写入企业信息。
4. `shengting` 变体立即执行鉴权。
5. 鉴权成功后继续进入企业信息页和后续 AI 巡检链路。

鉴权失败处理：

1. 第一次鉴权失败后自动重试一次。
2. 第二次仍失败时，当前页面显示提示：`身份鉴权失败，请检查网络或联系管理员`。
3. 弹窗只有确认操作。
4. 用户确认后返回 `MainMenuActivity`，并清理企业扫码状态与 shengting token。

AI 请求处理：

1. 7 个 `/ai/*` 请求在构建 `Request` 时获取 token。
2. 获取 token 失败时，本次 AI 请求失败并进入统一鉴权失败提示。
3. 收到 `401` 或 `403` 时调用 `refreshToken()` 并重试原请求一次。
4. 重试仍失败时显示同一提示，确认后返回主菜单。
5. 非 `401/403` 错误沿用原有网络失败路径。

## UI 提示

错误提示遵循现有眼镜端页面内遮罩和统一输入风格，不随意使用系统 `AlertDialog`。

提示文案：

```text
身份鉴权失败，请检查网络或联系管理员
```

交互规则：

1. 支持单击确认。
2. 支持语音“确认/确定”。
3. 确认后跳转 `MainMenuActivity`。
4. 返回主菜单前清理 `InspectionWorkflowSession` 企业数据和 shengting token。

## 日志与安全

日志允许记录：

- 是否 shengting flavor。
- SN 是否为空。
- 鉴权 HTTP code。
- 鉴权耗时。
- token 是否为空。
- AI 请求 taskId、lane、HTTP code。

日志禁止记录：

- 完整 SN。
- AES 明文。
- AES 密文。
- 完整 token。

SN 为空时直接视为鉴权失败，不写死兜底 SN，不使用测试 SN。

## 测试计划

### 单元测试

1. 配置 overlay 测试：`shengting` 的 7 个 AI URL 均解析为正式环境地址；`standard` 保持原值。
2. AES 固定向量测试：固定 `snCode` 与 `date`，断言 Android 实现与 `scripts/java/AESUtil.java` 的算法规则一致。
3. 鉴权响应解析测试：`{"code":200,"data":"token"}` 成功；`code != 200`、`data` 空、非 JSON 均失败。
4. token 管理测试：首次获取后缓存；失败自动重试一次；`refreshToken()` 强制刷新；并发请求不重复发起多次鉴权。
5. 请求头范围测试：只在 `shengting` 的 7 个 `/ai/*` 请求添加 `Authorization`；非 shengting、非 AI 请求不添加。

### 构建与真机验证

1. 运行 `./gradlew :app:testShengtingDebugUnitTest`。
2. 运行 `./gradlew :app:assembleShengtingDebug`。
3. 回归 `./gradlew :app:testStandardDebugUnitTest` 与 `./gradlew :app:assembleStandardDebug`。
4. 真机扫码验证链路：`getObjectMessage` 成功后鉴权成功，AI 请求带 `Authorization`。
5. 模拟鉴权失败：确认自动重试一次，第二次失败后弹出指定文案，确认后返回主菜单并清理状态。
6. 模拟 AI `401/403`：确认刷新 token 并重试一次；仍失败时弹出指定文案并返回主菜单。

## 非目标

1. 不迁移 `/hxy/apis/third/smartGlasses`。
2. 不迁移隐患保存和结束巡检接口。
3. 不迁移版本更新接口。
4. 不迁移 `has_hazard_answer`。
5. 不调整 AI 请求体和响应解析语义。
6. 不升级 Rokid SDK。
