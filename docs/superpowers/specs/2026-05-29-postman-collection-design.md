# Postman Collection 设计文档 — 智能眼镜-4090-AI服务

## 范围

将当前 App 中所有对外调用的 API 接口（除 `has_hazard_answer` 旧接口外）整理到 Postman workspace=峰景 的 Collection=智能眼镜-4090-AI服务 中。

## 接口清单

### AI 推理服务（端口 10010 / 10012）
1. `POST /ai/auto` — 隐患物品自动检测
2. `POST /ai/deep` — 隐患深度分析（SSE）
3. `POST /ai/gm` — GM 版深度分析（SSE）
4. `POST /ai/general` — 环境隐患识别
5. `POST /ai/general_deep` — 环境深度分析（SSE）
6. `POST /ai/device` — 设备指引
7. `POST /ai/sug_checks` — 建议检查项

### 巡检工作流（端口 7443）
8. `POST /smartGlasses/pushHidDanger` — 隐患上报
9. `POST /smartGlasses/pushHidDangerEnd` — 结束巡检
10. `POST /smartGlasses/getObjectMessage` — 企业对象信息查询

### 流式分析（端口 7443）
11. `POST /hxy/apis/third/smartGlasses` — 隐患流式分析旧版（SSE）

### 应用更新（端口 10203）
12. `GET /api/v1/updates/check` — 版本检查
13. `GET /releases/latest/update.json` — 更新清单

## 环境变量设计

采用多变量拆分，按微服务端口区分：

| 变量名 | 值 | 用途 |
|--------|-----|------|
| `ai_base_url` | `http://183.147.142.133:10010` | AI 推理服务主端口 |
| `ai_gm_url` | `http://183.147.142.133:10012` | AI GM 服务端口 |
| `biz_base_url` | `http://183.147.142.133:7443` | 业务后端端口 |
| `update_base_url` | `http://183.147.142.133:10203` | 应用更新端口 |
| `auth_code` | （空，需手动填入） | 认证码 |
| `object_id` | （空，需手动填入） | 企业对象 ID |
| `user_id` | （空，需手动填入） | 用户 ID |
| `place_code` | （空，需手动填入） | 场所编码 |

## 测试用例策略

每个接口配置至少 2 个请求示例：
1. **正常请求** — 标准参数，200 成功响应
2. **边界测试** — 空参数/超长参数/缺失必填字段/非法格式

SSE 接口额外配置 `Accept: text/event-stream` 请求头。
