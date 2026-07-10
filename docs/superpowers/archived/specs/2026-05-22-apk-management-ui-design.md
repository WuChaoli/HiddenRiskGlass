# APK 管理界面优化设计文档

## 背景

HiddenRiskGlassServer 的 APK 管理页面当前存在三个用户体验问题：
1. APK 文件上传无进度提示，大文件上传时用户无法感知进度
2. 版本列表中 APK 大小仅显示原始字节数，不直观
3. 发布表单以内联形式展示，占用页面空间且不够聚焦

## 目标

1. **上传进度可视化**：上传 APK 时显示进度条、已传/总大小、速度、预计剩余时间
2. **大小自动格式化**：版本列表中的 `size_bytes` 自动转换为人类可读单位（KB/MB/GB）
3. **发布弹窗化**：将发布表单从页面内联改为弹窗（Modal），参照设备管理的"新增"交互

## 技术约束

- 项目技术栈：Python FastAPI + Jinja2 + 原生 JS/CSS（无前端框架）
- 现有弹窗系统：CSS `.modal-overlay` + `.modal` + JS `openModal()`/`closeModal()`
- 现有表单提交：`data-fetch="true"` 表单通过 `fetch()` 提交
- 上传接口：`POST /admin/releases`，Content-Type 为 `multipart/form-data`

## 方案设计

### 1. 发布弹窗（Modal）

**HTML 变更（admin.html）**：
- 移除原有的"发布新 APK"内联 section
- APK 管理 Tab 保留一个"发布新版本"按钮
- 新增 `modal-publish-release` 弹窗，内含完整发布表单
- 弹窗中增加一个隐藏的进度展示区域（默认隐藏，上传时显示）

**JS 变更（admin.js）**：
- 添加 `openPublishModal()` / `closePublishModal()` 函数
- 发布表单脱离 `data-fetch="true"` 通用处理，绑定独立 submit 事件

**弹窗表单字段**：
- 基于已有版本（下拉选择，带 data 属性回填）
- versionCode、versionName、releaseNotes
- 强制更新（checkbox）、设为默认版本（checkbox）
- APK 文件选择

### 2. 上传进度追踪

**技术选型**：使用 `XMLHttpRequest` 替代 `fetch()`，利用 `xhr.upload.onprogress` 事件获取上传进度。

**进度信息计算**：
- `loaded` / `total` → 百分比
- `loaded` 和 `total` → 已传/总大小（通过 `formatBytes`）
- 速度：`(loaded - lastLoaded) / (now - lastTime) * 1000` → bytes/s
- 预计剩余时间：`(total - loaded) / speed` → 秒

**UI 展示**：
- 进度条：一个 `.progress-bar` 外框 + `.progress-fill` 内填充，宽度随百分比变化
- 文字信息：一行显示 `45% · 12.5 MB / 27.8 MB · 2.3 MB/s · 约 6 秒剩余`
- 完成时：文字变为"处理中..."，进度条 100%
- 成功后：关闭弹窗，刷新页面，显示 Toast "发布成功"
- 失败时：进度条变红，显示错误信息，保留弹窗允许重试

### 3. APK 大小格式化

**JS 函数**：
```js
function formatBytes(bytes, decimals = 2) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(decimals)) + ' ' + sizes[i];
}
```

**应用方式**：页面加载后扫描版本列表 `tbody` 中所有 size 单元格，将其内容从原始数字替换为格式化后的字符串。

### 4. CSS 新增

新增样式（admin.css）：
- `.progress-area`：进度区域容器，默认隐藏，上传时显示
- `.progress-bar`：进度条外框（灰色背景、圆角）
- `.progress-fill`：进度条填充（绿色、宽度动态变化）
- `.progress-info`：进度文字信息行（小号灰色字体）
- `.progress-area.error` 状态：填充变红色，文字变红色

## 影响范围

仅修改前端文件：
- `app/templates/admin.html`
- `app/static/admin.js`
- `app/static/admin.css`

后端 API `/admin/releases` 无需改动。

## 测试要点

1. 弹窗打开/关闭正常（点击按钮、点击背景、ESC 键）
2. 基于已有版本选择后字段回填正常
3. 上传进度条随文件上传实时更新
4. 大文件（>100MB）上传时速度和剩余时间计算合理
5. 版本列表大小格式化正确（边界值：0、1023、1024、1048576 等）
6. 上传失败时错误提示正确，不刷新页面
7. 网络中断时的行为（浏览器默认会触发 error）
