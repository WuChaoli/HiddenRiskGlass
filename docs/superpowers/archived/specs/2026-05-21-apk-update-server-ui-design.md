# APK Update Server UI 优化设计文档

## 项目上下文

`tools/apk_update_server` 是一个基于 FastAPI + Jinja2 的小型 APK 更新后台服务。当前所有功能（APK 发布、设备规则、发布列表、检查日志）堆叠在单一页面中，操作体验较差。

## 目标

将现有单页后台重构为标签页式多页面后台，并增强以下能力：

1. **APK 管理**：支持基于历史版本复制发布、编辑版本信息、删除版本并清理本地文件
2. **设备管理**：支持弹窗新增设备、搜索筛选、批量操作（改版本/允许/拒绝/删除）
3. **检查日志**：支持搜索筛选、分页浏览

## 方案概述

- **单页结构**：三个标签页内容在同一个 `admin.html` 中，首次加载时服务端一次性渲染全部数据
- **无刷新切换**：JS 控制 `section` 显示/隐藏，URL hash 标记当前标签（`#apk` / `#devices` / `#logs`）
- **轻量 JS 增强**：约 200 行原生 JS，不引入任何前端框架。批量操作通过 `fetch` 提交，成功后局部刷新列表

## 页面结构

### 顶部标签导航

三个标签水平排列，当前标签带下划线高亮：

| 标签 | Hash | 内容 |
|------|------|------|
| APK 管理 | `#apk` | 发布表单 + 版本列表 |
| 设备管理 | `#devices` | 新增按钮 + 搜索筛选 + 批量操作栏 + 规则列表 |
| 检查日志 | `#logs` | 搜索筛选 + 分页表格 |

### APK 管理页

**左侧面板：发布新 APK**

表单字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| 基于已有版本 | select | 否 | 选择历史版本自动填充信息 |
| versionCode | number | 是 | 整数，必须递增 |
| versionName | text | 是 | 用户可见版本名称 |
| releaseNotes | textarea | 否 | 客户端更新弹窗展示 |
| 强制更新 | checkbox | 否 | 客户端不可跳过 |
| 设为默认版本 | checkbox | 否 | 发布后自动设为默认 |
| APK 文件 | file | 是 | 仅支持 .apk |

**基于历史版本复制**：选择已有版本后，JS 自动填充 `versionName`、`releaseNotes`、`mandatory`，`versionCode` 自动设为 `原值 + 1`。

**右侧面板：版本列表**

表格列：版本、大小、强制、默认、操作。

- 默认版本行高亮显示（浅绿色背景）
- 每行操作：「编辑」和「删除」按钮
- 列表只显示 `status='active'` 的版本，`GET /admin` 服务端过滤返回

**编辑版本（弹窗）**：可修改 `versionName`、`releaseNotes`、`mandatory`。不可修改 `versionCode` 和 APK 文件。

**删除版本（确认弹窗）**：警告提示删除后将同时删除本地 APK 文件且已绑定设备规则将失效。确认后执行软删除（`status='deleted'`）并清理本地 release 目录（`releases/{id}/` 整个目录）。

### 设备管理页

**搜索筛选栏**（顶部）：

| 控件 | 功能 |
|------|------|
| NSCODE 搜索 | 实时前端过滤 |
| 状态筛选 | 全部 / 允许 / 拒绝 |
| 版本筛选 | 全部版本 / 具体版本 |
| 重置按钮 | 清空所有筛选条件 |
| + 新增设备按钮 | 打开新增弹窗 |

**批量操作栏**（筛选栏下方）：

- 全选 checkbox
- 已选计数（"已选 X 项"）
- 批量改版本、批量允许、批量拒绝、批量删除

**设备规则列表**：

表格列：checkbox、NSCODE、目标版本、状态（绿/红圆点 + 文字）、备注、更新时间、操作。

- 单行操作：「编辑」和「删除」按钮
- 底部统计：共 X 条规则 · 允许 Y 条 · 拒绝 Z 条 · 显示 N 条（筛选后）

**新增/编辑设备规则（弹窗）**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| NSCODE | text | 是 | 设备唯一标识码 |
| 目标版本 | select | 是 | 该设备将更新到此版本 |
| 备注 | text | 否 | 方便识别用途 |
| 状态 | select | 是 | 允许 / 拒绝 |

### 检查日志页

**搜索筛选栏**：

| 控件 | 功能 |
|------|------|
| NSCODE 搜索 | 实时前端过滤 |
| 结果筛选 | 全部 / 有更新 / 无更新 / 无版本 |
| 每页显示 | 30 / 50 / 100 条 |
| 重置按钮 | 清空筛选 |

**日志表格**：时间、NSCODE、当前版本、命中版本、结果。

- 结果着色：有更新（绿色）、无更新（灰色）、无版本（红色）
- 前端分页：上一页 / 下一页
- 底部统计：共 X 条记录 · 显示 M-N

## API 设计

### 现有接口（不变）

| 接口 | 方法 | 说明 |
|------|------|------|
| `GET /admin` | GET | 返回完整 admin 数据 |
| `POST /admin/releases` | POST | 发布新 APK |
| `POST /admin/default-release` | POST | 设置默认版本 |
| `POST /admin/device-rules` | POST | 新增/修改单条规则 |
| `POST /admin/device-rules/{id}/delete` | POST | 删除单条规则 |
| `GET /api/v1/updates/check` | GET | 检查更新 |

### 新增接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `PUT /admin/releases/{id}` | PUT | 编辑版本信息（versionName, releaseNotes, mandatory） |
| `POST /admin/releases/{id}/delete` | POST | 删除版本（软删除 + 清理 APK 文件） |
| `PUT /admin/device-rules/{id}` | PUT | 编辑单条规则（nscode, release_id, note, enabled） |
| `POST /admin/device-rules/batch` | POST | 批量操作：`{ids: [], action: "update_version"\|"enable"\|"disable"\|"delete", release_id?}` |

### 批量操作请求体

```json
{
  "ids": [1, 2, 3],
  "action": "update_version",
  "release_id": 5
}
```

`action` 取值：
- `update_version`：需要 `release_id`，将选中规则改到目标版本
- `enable`：批量启用（允许访问）
- `disable`：批量禁用（拒绝访问）
- `delete`：批量删除规则

## 数据模型调整

### releases 表

已有 `status` 字段（默认 `'active'`）。删除时更新为 `'deleted'`。

### device_rules 表

已有 `enabled` 字段（默认 `1`）。状态列对应：`1` = 允许，`0` = 拒绝。

`resolve_update` 服务已有 `enabled = 1` 的过滤条件，无需修改即可支持拒绝功能。

## 前端 JS 功能清单

| 功能 | 实现方式 |
|------|----------|
| 标签切换 | hashchange 监听，切换 section display |
| 版本复制 | select 变更时 JS 填充表单字段 |
| 设备列表过滤 | input/select 事件触发前端过滤函数 |
| 日志列表过滤 | 同上 |
| 日志分页 | JS 切片 + 翻页按钮 |
| 全选/反选 | 表头 checkbox 控制当前页所有行 |
| 批量操作 | 收集选中 id，fetch POST，成功后刷新列表数据 |
| 弹窗控制 | CSS display 切换，ESC/点击遮罩关闭 |
| 表单提交 | fetch POST/PUT，成功后 toast 提示并刷新数据 |

## 错误处理

- 表单验证失败：弹窗内显示错误信息，不关闭弹窗
- 批量操作部分失败：返回成功和失败的 id 列表，前端显示详细结果
- 删除默认版本：禁止删除当前默认版本，需先切换默认版本
- 删除有规则绑定的版本：允许删除，规则保留但指向已删除版本（设备检查时会 fallback 到默认版本）

## 文件变更清单

| 文件 | 变更 |
|------|------|
| `app/templates/admin.html` | 重写为三标签结构，新增弹窗模板 |
| `app/static/admin.css` | 新增标签导航、弹窗、批量操作栏、状态标签等样式 |
| `app/static/admin.js` | **新增**：标签切换、列表过滤、分页、全选、批量操作、弹窗控制、表单提交 |
| `app/main.py` | 新增 `PUT /admin/releases/{id}`、`POST /admin/releases/{id}/delete`、`PUT /admin/device-rules/{id}`、`POST /admin/device-rules/batch` |
| `app/services.py` | 新增 `update_release`、`delete_release`、`update_device_rule`、`batch_device_rules` |
| `app/db.py` | 无需变更，现有 schema 已满足需求 |
