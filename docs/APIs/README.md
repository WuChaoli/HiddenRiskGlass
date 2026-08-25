# 智能眼镜 API 接口文档

本文档汇总了"基层应消"智能眼镜 App 所有对外调用的 HTTP 接口，以及对应的 Postman Collection 调试入口。

---

## Postman 快速入口

| 项目 | 链接/ID |
|------|---------|
| **Workspace** | 峰景 |
| **Collection** | [智能眼镜-4090-AI服务](https://www.postman.com/charliwu-chn-4029768/workspace/峰景/collection/51351553-2749-4198-b4fd-6ec18287b5c3) |
| **Environment** | 智能眼镜-测试环境 |

### 环境变量

| 变量名 | 值 | 说明 |
|--------|-----|------|
| `ai_base_url` | `http://183.147.142.133:10010` | AI 推理服务主端口 |
| `ai_gm_url` | `http://183.147.142.133:10012` | AI GM 服务端口 |
| `biz_base_url` | `http://183.147.142.133:7443` | 业务后端端口 |
| `update_base_url` | `http://183.147.142.133:10203` | 应用更新端口 |
| `auth_code` | （需填入） | 企业认证码，扫码获取 |
| `object_id` | （需填入） | 企业对象 ID，扫码获取 |
| `user_id` | （需填入） | 用户 ID，扫码获取 |
| `place_code` | （需填入） | 场所编码，getObjectMessage 返回 |

> **如何获取动态变量**：眼镜端扫描企业二维码后，`EnterpriseQrScanActivity` 日志中会输出 `authCode` / `objectId` / `userId`。`placeCode` 需先调用 `getObjectMessage` 接口从响应中提取。

---

## 接口清单

### 一、AI 推理服务（端口 10010 / 10012）

所有 AI 推理接口的请求体结构统一为：
```json
{
  "task_id": "string",
  "stream": boolean,
  "image": "base64String",
  "text": "string",
  "scene": "placeCode"
}
```

#### 1. 隐患物品自动检测

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{ai_base_url}}/ai/auto` |
| 响应类型 | JSON |

**请求示例**：
```json
{
  "task_id": "task_001",
  "stream": false,
  "image": "/9j/4AAQSkZJRgABAQAAAQABAAD...",
  "text": "",
  "scene": "{{place_code}}"
}
```

**响应示例**：
```json
{
  "code": 200,
  "msg": "success",
  "task_id": "task_001",
  "content": "",
  "inference_result": [
    {
      "label": "灭火器",
      "confidence": 0.95,
      "bbox": [100, 200, 150, 300]
    }
  ],
  "cost": 120
}
```

#### 2. 统一隐患深度分析 V2（结构化 JSON）

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `/ai/deep/v2` · `/ai/general_deep/v2` · `/ai/gm/v2` |
| 响应类型 | JSON |

请求沿用 `task_id`、`stream=false`、Base64 `image`、`text`、`scene` 字段。自动和有 `placeCode` 的手动/拍照走 `/deep/v2`，环境检测走 `/general_deep/v2`，无 `placeCode` 的手动/拍照走 `/gm/v2`。响应的 `detections[].label_id` 与 `hazards[].label_id` 关联；`label_id=others` 表示无框的全局隐患。客户端保留 `check_items`，展示和保存使用 `hazards`。

```json
{
  "code": 0,
  "msg": "success",
  "task_id": "deep-v2-001",
  "type": "deep_v2",
  "detections": [{"label":"燃气灶","bbox":[32.9,293.2,1200.8,1435.2],"score":0.906,"inter":0,"label_id":"det_001"}],
  "hazards": [{"label_id":"det_001","隐患描述":"未安装熄火保护装置","隐患等级":"一般隐患","主要依据":"相关导则","整改建议":"更换合规灶具","隐患编号":"ZJYJ_JX_XCY_009"}],
  "check_items": []
}
```

#### 3. 旧有隐患深度分析（SSE 流式）

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{ai_base_url}}/ai/deep` |
| 请求头 | `Accept: text/event-stream` |
| 响应类型 | SSE 流式文本 |

**请求示例**：
```json
{
  "task_id": "task_002",
  "stream": true,
  "image": "/9j/4AAQSkZJRgABAQAAAQABAAD...",
  "text": "",
  "scene": "{{place_code}}"
}
```

**响应示例**（SSE 格式）：
```
data: {"content": "检测到灭火器位于墙角"}

data: {"content": "该灭火器压力正常，在有效期内"}

data: [DONE]
```

#### 4. GM 版深度分析（SSE 流式）

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{ai_gm_url}}/ai/gm` |
| 请求头 | `Accept: text/event-stream` |
| 响应类型 | SSE 流式文本 |

> **用途**：当 `placeCode` 缺失时的回退接口。

#### 5. 环境隐患识别

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{ai_base_url}}/ai/general` |
| 响应类型 | JSON / SSE 混合 |

#### 6. 环境深度分析（SSE 流式）

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{ai_base_url}}/ai/general_deep` |
| 请求头 | `Accept: text/event-stream` |
| 响应类型 | SSE 流式文本 |

#### 7. 设备指引

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{ai_base_url}}/ai/device` |
| 响应类型 | JSON |

**响应示例**：
```json
{
  "code": 200,
  "msg": "success",
  "task_id": "task_006",
  "type": "device_guide",
  "content": "请检查灭火器压力表是否在绿色区域"
}
```

#### 8. 建议检查项

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{ai_base_url}}/ai/sug_checks` |
| 响应类型 | JSON |

**请求示例**：
```json
{
  "task_id": "task_007",
  "hazardCode": "HAZARD_001"
}
```

---

### 二、巡检工作流（端口 7443）

#### 9. 隐患上报

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{biz_base_url}}/smartGlasses/pushHidDanger` |
| 响应类型 | JSON |

**请求示例**：
```json
{
  "authCode": "{{auth_code}}",
  "objectId": "{{object_id}}",
  "userId": "{{user_id}}",
  "customParam": "{}",
  "image": "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD...",
  "hidDanger": [
    {
      "hazardCode": "HAZARD_001",
      "hazardName": "灭火器过期",
      "level": "一般",
      "position": "大厅角落",
      "confidence": 0.95
    }
  ]
}
```

**响应示例**：
```json
{
  "code": 200,
  "message": "上报成功",
  "msg": "success"
}
```

#### 10. 结束巡检

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{biz_base_url}}/smartGlasses/pushHidDangerEnd` |
| 响应类型 | JSON |

**请求示例**：
```json
{
  "authCode": "{{auth_code}}",
  "objectId": "{{object_id}}",
  "userId": "{{user_id}}",
  "customParam": "{}",
  "ifEnd": "1"
}
```

#### 11. 企业对象信息查询

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{biz_base_url}}/smartGlasses/getObjectMessage` |
| 响应类型 | JSON |

**请求示例**：
```json
{
  "authCode": "{{auth_code}}",
  "objectId": "{{object_id}}"
}
```

**响应示例**：
```json
{
  "code": 200,
  "message": "success",
  "msg": "success",
  "data": {
    "objectName": "某某工厂",
    "areaName": "A区",
    "domain": "制造业",
    "tags": ["化工", "高温"],
    "riskLevel": "高",
    "hidDanger": [],
    "placeCode": "PLACE_001",
    "lastInspectionDate": "2026-05-20"
  }
}
```

---

### 三、流式分析（端口 7443）

#### 12. 隐患流式分析旧版（SSE）

| 属性 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{{biz_base_url}}/hxy/apis/third/smartGlasses` |
| 请求头 | `Accept: text/event-stream` |
| 响应类型 | SSE 流式文本 |

**请求示例**：
```json
{
  "image": "/9j/4AAQSkZJRgABAQAAAQABAAD...",
  "snCode": "GLASS_SN_001",
  "sessionId": "sess_001",
  "timestamp": 1716960000000,
  "hiddenRisk": [],
  "labels": {}
}
```

---

### 四、应用更新（端口 10203）

#### 13. 版本检查

| 属性 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{{update_base_url}}/api/v1/updates/check` |
| Query 参数 | `nscode={{place_code}}&currentVersionCode=20003` |
| 响应类型 | JSON |

**响应示例**：
```json
{
  "versionCode": 20004,
  "versionName": "2.0.4",
  "apkUrl": "http://183.147.142.133:10203/releases/v2.0.4/app-standard-release.apk",
  "sha256": "abc123...",
  "sizeBytes": 52428800,
  "mandatory": false,
  "changelog": "修复已知问题，优化性能"
}
```

#### 14. 更新清单获取

| 属性 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{{update_base_url}}/releases/latest/update.json` |
| 响应类型 | JSON |

---

## Postman Tests 断言说明

Collection 中每个请求都配置了 Tests 脚本，运行后可在 **Test Results** 标签页查看：

| 检查项 | 断言内容 |
|--------|---------|
| 状态码 | `pm.response.to.have.status(200)` |
| Content-Type | JSON 接口验证 `application/json`，SSE 接口验证 `text/event-stream` |
| 字段存在性 | 验证 `code`、`inference_result`、`data`、`versionCode` 等关键字段 |
| SSE 内容 | 验证响应体包含 `data:` 前缀 |

---

## 源码对应位置

| 接口 | 源码文件 |
|------|---------|
| `/ai/deep/v2` · `/ai/general_deep/v2` · `/ai/gm/v2` | [`DeepV2Client.kt`](../../app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Client.kt) |
| `/ai/auto` · `/ai/deep` · `/ai/gm` · `/ai/general` · `/ai/general_deep` · `/ai/device` · `/ai/sug_checks` | [`AiArSseService.kt`](../../app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt) |
| `pushHidDanger` | [`LocalHazardPushService.kt`](../../app/src/main/java/com/rokid/glass/hiddenrisk/LocalHazardPushService.kt) |
| `pushHidDangerEnd` | [`InspectionFinishService.kt`](../../app/src/main/java/com/rokid/glass/hiddenrisk/InspectionFinishService.kt) |
| `getObjectMessage` | [`EnterpriseObjectMessageService.kt`](../../app/src/main/java/com/rokid/glass/EnterpriseObjectMessageService.kt) |
| 旧版 SSE 流式 | [`SSEUtil.kt`](../../app/src/main/java/com/rokid/glass/utils/SSEUtil.kt) |
| `updates/check` · `update.json` | [`AppUpdateClient.kt`](../../app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt) |

---

## 注意事项

1. **NCNN param + bin 必须成对替换**（架构不变量，与 API 无关但全局生效）
2. **配置只从 `InspectionConfigRepository` 读取**，禁止硬编码推理参数/API 端点
3. **SSE 接口**需要设置 `Accept: text/event-stream` 请求头，Postman 中已预置
4. **动态 URL 构建**：`pushHidDanger`、`pushHidDangerEnd`、`getObjectMessage` 的 URL 基于 `baseUrl` 动态拼接，如果 `baseUrl` 已包含 `/smartGlasses` 路径则直接拼接，否则自动补全
