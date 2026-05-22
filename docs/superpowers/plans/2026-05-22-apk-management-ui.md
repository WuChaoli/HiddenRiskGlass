# APK 管理界面优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 HiddenRiskGlassServer APK 管理页面添加上传进度条、APK 大小格式化、发布弹窗化三个功能。

**Architecture:** 纯前端改动，利用现有 Modal 和 Toast 系统，发布表单改用 XMLHttpRequest 以获取上传进度事件，大小格式化在页面加载时批量处理。

**Tech Stack:** Jinja2 模板 + 原生 JavaScript + CSS（无框架）

---

### 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/static/admin.css` | 修改 | 新增进度条相关样式 |
| `app/templates/admin.html` | 修改 | 发布表单改为弹窗，版本列表 size 加 data 属性 |
| `app/static/admin.js` | 修改 | 进度追踪、大小格式化、弹窗控制 |

---

### Task 1: 添加进度条 CSS 样式

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/static/admin.css`

- [ ] **Step 1: 在 admin.css 末尾追加进度条样式**

在文件末尾（`@media` 查询之后或 `endpoint-custom` 之后）追加：

```css
/* ---------- Upload progress ---------- */
.progress-area {
  display: none;
  margin-top: 16px;
}

.progress-area.active {
  display: block;
}

.progress-bar {
  width: 100%;
  height: 10px;
  background: #e5e7eb;
  border-radius: 5px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  width: 0%;
  background: var(--accent);
  border-radius: 5px;
  transition: width 0.2s ease;
}

.progress-area.error .progress-fill {
  background: var(--danger);
}

.progress-info {
  margin-top: 8px;
  font-size: 13px;
  color: var(--muted);
}

.progress-area.error .progress-info {
  color: var(--danger);
}
```

- [ ] **Step 2: Commit**

```bash
cd servers/HiddenRiskGlassServer
git add app/static/admin.css
git commit -m "feat: add upload progress bar styles"
```

---

### Task 2: 发布表单改为弹窗 + 版本列表加 data 属性

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/templates/admin.html`

- [ ] **Step 1: 替换 APK 管理 Tab 的发布表单为"发布新版本"按钮**

找到 APK 管理 Tab 的第一个 `section.panel`（包含 "发布新 APK" 表单），将其替换为：

```html
      <section class="panel">
        <div style="display:flex;align-items:center;justify-content:space-between">
          <h2>版本列表</h2>
          <button type="button" onclick="openPublishModal()">发布新版本</button>
        </div>
```

注意：保留第二个 `section.panel`（版本列表），但把标题和按钮放在同一行。

- [ ] **Step 2: 移除原有内联发布表单 section**

删除原来 `<section class="panel"><h2>发布新 APK</h2><form action="/admin/releases"...>...</form></section>` 这一整块代码。

- [ ] **Step 3: 给版本列表 size 单元格添加 data 属性**

在版本列表的 `tbody` 中，找到大小列：

```html
<td>{{ release.size_bytes }}</td>
```

替换为：

```html
<td data-size-bytes="{{ release.size_bytes }}">{{ release.size_bytes }}</td>
```

- [ ] **Step 4: 在 Modals 区域添加发布弹窗**

在 `<!-- ===== Modals ===== -->` 区域（编辑版本 Modal 之前或之后），添加新的发布弹窗：

```html
  <!-- 发布新版本 Modal -->
  <div class="modal-overlay" id="modal-publish-release">
    <div class="modal">
      <h3>发布新版本</h3>
      <form id="form-publish-release" action="/admin/releases" method="post" enctype="multipart/form-data">
        <div class="form-row">
          <label>
            基于已有版本
            <select id="base-release">
              <option value="">-- 不基于已有版本 --</option>
              {% for release in releases %}
              <option value="{{ release.id }}"
                data-version-code="{{ release.version_code }}"
                data-version-name="{{ release.version_name }}"
                data-release-notes="{{ release.release_notes or '' }}"
                data-mandatory="{{ '1' if release.mandatory else '0' }}">
                {{ release.version_name }} ({{ release.version_code }})
              </option>
              {% endfor %}
            </select>
          </label>
        </div>
        <div class="form-row" style="display:grid;grid-template-columns:1fr 1fr;gap:14px">
          <label>
            versionCode
            <input type="number" name="versionCode" id="versionCode" min="1" step="1" required>
          </label>
          <label>
            versionName
            <input type="text" name="versionName" id="versionName" required>
          </label>
        </div>
        <div class="form-row">
          <label>
            releaseNotes
            <textarea name="releaseNotes" id="releaseNotes"></textarea>
          </label>
        </div>
        <div class="form-row" style="display:flex;gap:16px">
          <label class="check-row">
            <input type="checkbox" name="mandatory" id="mandatory">
            强制更新
          </label>
          <label class="check-row">
            <input type="checkbox" name="makeDefault" id="makeDefault">
            设为默认版本
          </label>
        </div>
        <div class="form-row">
          <label>
            APK 文件
            <input type="file" name="apk" id="publish-apk-file" accept=".apk" required>
          </label>
        </div>
        <div class="progress-area" id="publish-progress-area">
          <div class="progress-bar">
            <div class="progress-fill" id="publish-progress-fill"></div>
          </div>
          <div class="progress-info" id="publish-progress-info">准备上传...</div>
        </div>
        <div class="modal-actions">
          <button class="secondary" type="button" onclick="closeModal('modal-publish-release')">取消</button>
          <button type="submit" id="publish-submit-btn">发布</button>
        </div>
      </form>
    </div>
  </div>
```

- [ ] **Step 5: Commit**

```bash
cd servers/HiddenRiskGlassServer
git add app/templates/admin.html
git commit -m "feat: convert publish form to modal and add size data attrs"
```

---

### Task 3: 添加大小格式化与上传进度 JS

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/static/admin.js`

- [ ] **Step 1: 在 admin.js 顶部添加 formatBytes 函数**

在 `// ===== Tab switching =====` 注释之前添加：

```javascript
// ===== Utility: format bytes =====
function formatBytes(bytes, decimals = 2) {
  if (bytes === 0 || bytes === undefined || bytes === null) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.max(0, Math.min(sizes.length - 1, Math.floor(Math.log(bytes) / Math.log(k))));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(decimals)) + ' ' + sizes[i];
}
```

- [ ] **Step 2: 添加页面加载时格式化大小**

在 `// ===== Tab switching =====` 之后（或其他初始化位置），添加：

```javascript
// ===== Format size_bytes in version list =====
function formatVersionSizes() {
  document.querySelectorAll('td[data-size-bytes]').forEach(td => {
    const bytes = parseInt(td.dataset.sizeBytes, 10);
    if (!isNaN(bytes)) {
      td.textContent = formatBytes(bytes);
    }
  });
}
document.addEventListener('DOMContentLoaded', formatVersionSizes);
```

- [ ] **Step 3: 添加发布弹窗控制函数**

在 `// ===== Modal control =====` 区域（`closeModal` 函数之后），添加：

```javascript
function openPublishModal() {
  // 重置表单
  const form = document.getElementById('form-publish-release');
  form.reset();
  document.getElementById('versionCode').value = '';
  document.getElementById('versionName').value = '';
  document.getElementById('releaseNotes').value = '';
  document.getElementById('mandatory').checked = false;
  document.getElementById('makeDefault').checked = false;

  // 重置进度区域
  const progressArea = document.getElementById('publish-progress-area');
  progressArea.classList.remove('active', 'error');
  document.getElementById('publish-progress-fill').style.width = '0%';
  document.getElementById('publish-progress-info').textContent = '准备上传...';
  document.getElementById('publish-submit-btn').disabled = false;

  openModal('modal-publish-release');
}
```

- [ ] **Step 4: 将原有基于已有版本的事件监听器迁移到新表单**

找到原有的 `baseReleaseSelect` 监听器代码块（大约第 42-62 行）：

```javascript
const baseReleaseSelect = document.getElementById('base-release');
const releasesDataEl = document.getElementById('releases-data');
```

确保这段代码仍然有效（因为弹窗中也用了 `id="base-release"`）。由于弹窗中的 select 和原来内联表单的 id 相同，这段代码应该可以直接工作。

- [ ] **Step 5: 为发布表单添加 XMLHttpRequest 上传进度处理**

在 `// ===== Form submissions via fetch =====` 代码块之前，添加发布表单的独立处理：

```javascript
// ===== Publish release form with upload progress =====
const publishForm = document.getElementById('form-publish-release');
if (publishForm) {
  publishForm.addEventListener('submit', function(e) {
    e.preventDefault();
    const formData = new FormData(publishForm);
    const url = publishForm.action;

    const progressArea = document.getElementById('publish-progress-area');
    const progressFill = document.getElementById('publish-progress-fill');
    const progressInfo = document.getElementById('publish-progress-info');
    const submitBtn = document.getElementById('publish-submit-btn');

    progressArea.classList.add('active');
    progressArea.classList.remove('error');
    submitBtn.disabled = true;

    let lastLoaded = 0;
    let lastTime = Date.now();

    const xhr = new XMLHttpRequest();

    xhr.upload.addEventListener('progress', function(e) {
      if (e.lengthComputable) {
        const percent = Math.round((e.loaded / e.total) * 100);
        progressFill.style.width = percent + '%';

        const now = Date.now();
        const dt = (now - lastTime) / 1000;
        let speedText = '';
        let etaText = '';

        if (dt > 0.5) {
          const speed = (e.loaded - lastLoaded) / dt;
          lastLoaded = e.loaded;
          lastTime = now;

          if (speed > 0) {
            speedText = ' · ' + formatBytes(speed) + '/s';
            const remaining = e.total - e.loaded;
            const eta = remaining / speed;
            if (eta >= 1) {
              etaText = ' · 约 ' + Math.ceil(eta) + ' 秒剩余';
            } else if (eta > 0) {
              etaText = ' · 即将完成';
            }
          }
        }

        progressInfo.textContent = percent + '% · ' +
          formatBytes(e.loaded) + ' / ' + formatBytes(e.total) +
          speedText + etaText;
      }
    });

    xhr.addEventListener('load', function() {
      if (xhr.status >= 200 && xhr.status < 300) {
        progressFill.style.width = '100%';
        progressInfo.textContent = '发布成功，刷新中...';
        showToast('发布成功', 'success');
        setTimeout(function() {
          location.reload();
        }, 500);
      } else {
        progressArea.classList.add('error');
        let msg = '发布失败';
        try {
          const resp = JSON.parse(xhr.responseText);
          if (resp.error) msg = resp.error;
        } catch (_) {}
        progressInfo.textContent = msg;
        submitBtn.disabled = false;
      }
    });

    xhr.addEventListener('error', function() {
      progressArea.classList.add('error');
      progressInfo.textContent = '上传失败，请检查网络后重试';
      submitBtn.disabled = false;
    });

    xhr.addEventListener('abort', function() {
      progressArea.classList.add('error');
      progressInfo.textContent = '上传已取消';
      submitBtn.disabled = false;
    });

    xhr.open('POST', url);
    xhr.send(formData);
  });
}
```

- [ ] **Step 6: Commit**

```bash
cd servers/HiddenRiskGlassServer
git add app/static/admin.js
git commit -m "feat: add upload progress tracking and size formatting"
```

---

### Task 4: 验证测试

- [ ] **Step 1: 启动服务器**

```bash
cd servers/HiddenRiskGlassServer
python server.py
```

- [ ] **Step 2: 手动验证清单**

登录后进入 APK 管理页面，逐一验证：

| 验证项 | 期望结果 |
|--------|---------|
| 版本列表大小列 | 显示为 KB/MB/GB 格式，不再是原始数字 |
| 点击"发布新版本"按钮 | 弹窗正确打开，表单字段为空 |
| 选择"基于已有版本" | versionCode、versionName 等字段自动回填 |
| 选择 APK 文件并点击发布 | 进度条出现，百分比和大小实时更新 |
| 观察进度文字 | 包含：百分比、已传/总大小、速度、预计剩余时间 |
| 上传完成 | 弹窗关闭，页面刷新，显示 Toast "发布成功" |
| 点击弹窗外背景 / 按 ESC | 弹窗关闭 |
| 重新打开弹窗 | 表单和进度区域已重置 |

- [ ] **Step 3: 边界情况验证**

1. **上传中断**：断网或关闭服务器，观察进度条变红、显示错误信息、"发布"按钮恢复可点击
2. **小文件上传**：上传一个极小的测试文件，验证进度条能否快速完成
3. **大小格式化边界**：检查 0 B、1023 B、1024 B、1048576 B 等值的显示

---

## Self-Review

**Spec coverage:**
- [x] 上传进度条（百分比 + 大小 + 速度 + 时间）→ Task 3 Step 5
- [x] APK 大小格式化 → Task 3 Step 1-2
- [x] 发布弹窗化 → Task 2 + Task 3 Step 3
- [x] 错误处理 → Task 3 Step 5 (error/abort handlers)
- [x] 弹窗关闭方式 → 复用现有 modal 系统（点击背景 + ESC）

**Placeholder scan:**
- [x] 无 TBD/TODO
- [x] 无"适当处理"等模糊描述
- [x] 所有代码块完整

**Type consistency:**
- [x] `formatBytes` 函数名/签名在所有任务中一致
- [x] DOM id 名称在 HTML 和 JS 中一致
