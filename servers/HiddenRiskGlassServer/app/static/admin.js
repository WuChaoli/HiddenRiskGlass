// ===== Utility: format bytes =====
function formatBytes(bytes, decimals = 2) {
  if (bytes === 0 || bytes === undefined || bytes === null) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.max(0, Math.min(sizes.length - 1, Math.floor(Math.log(bytes) / Math.log(k))));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(decimals)) + ' ' + sizes[i];
}

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

// ===== Tab switching =====
const tabBtns = document.querySelectorAll('.tab-btn');
const tabSections = document.querySelectorAll('.tab-section');

function switchTab(tabId) {
  tabBtns.forEach(btn => {
    btn.classList.toggle('active', btn.dataset.tab === tabId);
  });
  tabSections.forEach(section => {
    section.classList.toggle('active', section.id === 'tab-' + tabId);
  });
  location.hash = tabId;
}

tabBtns.forEach(btn => {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
});

// 页面加载时根据 hash 激活对应 tab
function initTabFromHash() {
  const hash = location.hash.replace('#', '');
  const validTabs = ['apk', 'device', 'logs', 'endpoints'];
  if (validTabs.includes(hash)) {
    switchTab(hash);
  }
}
window.addEventListener('hashchange', initTabFromHash);
initTabFromHash();

// ===== Toast notifications =====
function showToast(message, type) {
  const toast = document.createElement('div');
  toast.className = 'toast ' + (type || 'success');
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.remove();
  }, 3000);
}

// ===== Version copy (基于已有版本) =====
const baseReleaseSelect = document.getElementById('base-release');
const releasesDataEl = document.getElementById('releases-data');
let releasesData = [];
try {
  releasesData = JSON.parse(releasesDataEl.textContent);
} catch (e) {
  releasesData = [];
}

if (baseReleaseSelect) {
  baseReleaseSelect.addEventListener('change', function() {
    const releaseId = this.value;
    if (!releaseId) return;
    const release = releasesData.find(r => String(r.id) === releaseId);
    if (!release) return;
    document.getElementById('versionCode').value = (release.version_code + 1);
    document.getElementById('versionName').value = release.version_name;
    document.getElementById('releaseNotes').value = release.release_notes || '';
    document.getElementById('mandatory').checked = !!release.mandatory;
  });
}

// ===== Modal control =====
function openModal(id) {
  const overlay = document.getElementById(id);
  if (overlay) overlay.classList.add('active');
}

function closeModal(id) {
  const overlay = document.getElementById(id);
  if (overlay) overlay.classList.remove('active');
}

// 点击 overlay 背景关闭 modal
document.querySelectorAll('.modal-overlay').forEach(overlay => {
  overlay.addEventListener('click', function(e) {
    if (e.target === this) {
      this.classList.remove('active');
    }
  });
});

// ESC 键关闭 modal
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') {
    document.querySelectorAll('.modal-overlay.active').forEach(o => o.classList.remove('active'));
  }
});

function openPublishModal() {
  const form = document.getElementById('form-publish-release');
  form.reset();
  document.getElementById('versionCode').value = '';
  document.getElementById('versionName').value = '';
  document.getElementById('releaseNotes').value = '';
  document.getElementById('mandatory').checked = false;
  document.getElementById('makeDefault').checked = false;

  const progressArea = document.getElementById('publish-progress-area');
  progressArea.classList.remove('active', 'error');
  document.getElementById('publish-progress-fill').style.width = '0%';
  document.getElementById('publish-progress-info').textContent = '准备上传...';
  document.getElementById('publish-submit-btn').disabled = false;

  openModal('modal-publish-release');
}

// ===== 编辑版本信息 Modal =====
function openEditReleaseModal(releaseId) {
  const row = document.querySelector('tr[data-release-id="' + releaseId + '"]');
  if (!row) return;
  const versionName = row.dataset.versionName;
  const releaseNotes = row.dataset.releaseNotes;
  const mandatory = row.dataset.mandatory === '1';

  document.getElementById('edit-release-versionName').value = versionName;
  document.getElementById('edit-release-releaseNotes').value = releaseNotes;
  document.getElementById('edit-release-mandatory').checked = mandatory;

  const form = document.getElementById('form-edit-release');
  form.action = '/admin/releases/' + releaseId;

  openModal('modal-edit-release');
}

// ===== 删除版本确认 Modal =====
function openDeleteReleaseModal(releaseId) {
  const form = document.getElementById('form-delete-release');
  form.action = '/admin/releases/' + releaseId + '/delete';
  openModal('modal-delete-release');
}

// ===== 设备规则 Modal =====
let currentDeviceId = null;

function openAddDeviceModal() {
  currentDeviceId = null;
  document.getElementById('device-modal-title').textContent = '新增设备规则';
  document.getElementById('device-nscode').value = '';
  document.getElementById('device-releaseId').selectedIndex = 0;
  document.getElementById('device-note').value = '';
  document.getElementById('device-enabled').value = '1';
  document.getElementById('device-nscode').disabled = false;

  const form = document.getElementById('form-device');
  form.action = '/admin/device-rules';

  openModal('modal-device');
}

function openEditDeviceModal(id, nscode, releaseId, note, enabled) {
  currentDeviceId = id;
  document.getElementById('device-modal-title').textContent = '编辑设备规则';
  document.getElementById('device-nscode').value = nscode;
  document.getElementById('device-releaseId').value = String(releaseId);
  document.getElementById('device-note').value = note;
  document.getElementById('device-enabled').value = String(enabled);
  document.getElementById('device-nscode').disabled = true;

  const form = document.getElementById('form-device');
  form.action = '/admin/device-rules/' + id;

  // 编辑时使用 PUT 方法
  let methodInput = form.querySelector('input[name="_method"]');
  if (!methodInput) {
    methodInput = document.createElement('input');
    methodInput.type = 'hidden';
    methodInput.name = '_method';
    form.appendChild(methodInput);
  }
  methodInput.value = 'PUT';

  openModal('modal-device');
}

// ===== 删除设备规则 =====
function deleteDevice(deviceId) {
  if (!confirm('确定要删除此设备规则吗？')) return;
  fetch('/admin/device-rules/' + deviceId + '/delete', {
    method: 'POST'
  })
  .then(r => {
    if (r.ok) {
      showToast('删除成功', 'success');
      setTimeout(() => location.reload(), 500);
    } else {
      showToast('删除失败', 'error');
    }
  })
  .catch(() => showToast('删除失败', 'error'));
}

// ===== List filtering (frontend-only) =====

// 设备规则过滤
const deviceSearch = document.getElementById('device-search');
const deviceStatusFilter = document.getElementById('device-status-filter');
const deviceVersionFilter = document.getElementById('device-version-filter');

function filterDeviceRules() {
  const search = (deviceSearch ? deviceSearch.value.toLowerCase() : '');
  const status = deviceStatusFilter ? deviceStatusFilter.value : '';
  const version = deviceVersionFilter ? deviceVersionFilter.value : '';

  const rows = document.querySelectorAll('#device-rules-body tr[data-id]');
  let visibleCount = 0;

  rows.forEach(row => {
    const rowSearch = row.dataset.search || '';
    const rowStatus = row.dataset.filter0 || '';
    const rowVersion = row.dataset.filter1 || '';

    const matchSearch = !search || rowSearch.includes(search);
    const matchStatus = !status || rowStatus === status;
    const matchVersion = !version || rowVersion === version;

    const visible = matchSearch && matchStatus && matchVersion;
    row.style.display = visible ? '' : 'none';
    if (visible) visibleCount++;
  });

  // 更新统计显示
  const statsEl = document.getElementById('device-stats');
  if (statsEl) {
    const total = rows.length;
    const enabled = document.querySelectorAll('#device-rules-body tr[data-filter0="1"]').length;
    const disabled = total - enabled;
    statsEl.textContent = '共 ' + total + ' 条规则 · 允许 ' + enabled + ' 条 · 拒绝 ' + disabled + ' 条 · 显示 ' + visibleCount + ' 条（筛选后）';
  }

  // 更新全选状态
  updateDeviceSelectAllState();
  updateDeviceSelectedCount();
}

if (deviceSearch) deviceSearch.addEventListener('input', filterDeviceRules);
if (deviceStatusFilter) deviceStatusFilter.addEventListener('change', filterDeviceRules);
if (deviceVersionFilter) deviceVersionFilter.addEventListener('change', filterDeviceRules);

function resetDeviceFilters() {
  if (deviceSearch) deviceSearch.value = '';
  if (deviceStatusFilter) deviceStatusFilter.value = '';
  if (deviceVersionFilter) deviceVersionFilter.value = '';
  filterDeviceRules();
}

// 日志过滤
const logSearch = document.getElementById('log-search');
const logResultFilter = document.getElementById('log-result-filter');
const logPageSize = document.getElementById('log-page-size');

let logCurrentPage = 1;
let logPageSizeValue = 30;

function getVisibleLogRows() {
  const search = (logSearch ? logSearch.value.toLowerCase() : '');
  const result = logResultFilter ? logResultFilter.value : '';

  const allRows = document.querySelectorAll('#log-body tr[data-search]');
  const visible = [];
  allRows.forEach(row => {
    const rowSearch = row.dataset.search || '';
    const rowResult = row.dataset.filter0 || '';
    const matchSearch = !search || rowSearch.includes(search);
    const matchResult = !result || rowResult === result;
    if (matchSearch && matchResult) {
      visible.push(row);
    }
  });
  return visible;
}

function applyLogPagination() {
  const visibleRows = getVisibleLogRows();
  const total = visibleRows.length;
  logPageSizeValue = parseInt(logPageSize ? logPageSize.value : '30', 10);

  const maxPage = Math.max(1, Math.ceil(total / logPageSizeValue));
  if (logCurrentPage > maxPage) logCurrentPage = maxPage;
  if (logCurrentPage < 1) logCurrentPage = 1;

  const start = (logCurrentPage - 1) * logPageSizeValue;
  const end = Math.min(start + logPageSizeValue, total);

  // 先隐藏所有行
  document.querySelectorAll('#log-body tr').forEach(row => row.style.display = 'none');

  // 再显示当前页的行
  for (let i = start; i < end; i++) {
    if (visibleRows[i]) visibleRows[i].style.display = '';
  }

  // 更新分页信息
  const infoEl = document.getElementById('log-pagination-info');
  if (infoEl) {
    if (total === 0) {
      infoEl.textContent = '共 0 条记录';
    } else {
      infoEl.textContent = '共 ' + total + ' 条记录 · 显示 ' + (start + 1) + '-' + end;
    }
  }

  // 更新按钮状态
  const prevBtn = document.getElementById('log-prev');
  const nextBtn = document.getElementById('log-next');
  if (prevBtn) prevBtn.disabled = logCurrentPage <= 1;
  if (nextBtn) nextBtn.disabled = logCurrentPage >= maxPage || total === 0;
}

function filterLogs() {
  logCurrentPage = 1;
  applyLogPagination();
}

if (logSearch) logSearch.addEventListener('input', filterLogs);
if (logResultFilter) logResultFilter.addEventListener('change', filterLogs);
if (logPageSize) logPageSize.addEventListener('change', filterLogs);

function changeLogPage(delta) {
  logCurrentPage += delta;
  applyLogPagination();
}

function resetLogFilters() {
  if (logSearch) logSearch.value = '';
  if (logResultFilter) logResultFilter.value = '';
  if (logPageSize) logPageSize.value = '30';
  logCurrentPage = 1;
  filterLogs();
}

// 初始化日志分页
applyLogPagination();

// ===== Select all / batch operations =====
const deviceSelectAll = document.getElementById('device-select-all');
const deviceSelectAllHeader = document.getElementById('device-select-all-header');

function getVisibleDeviceCheckboxes() {
  return document.querySelectorAll('#device-rules-body tr[data-id]:not([style*="display: none"]) .device-row-checkbox');
}

function getSelectedDeviceIds() {
  const ids = [];
  document.querySelectorAll('.device-row-checkbox:checked').forEach(cb => {
    ids.push(cb.dataset.id);
  });
  return ids;
}

function updateDeviceSelectedCount() {
  const count = getSelectedDeviceIds().length;
  const el = document.getElementById('device-selected-count');
  if (el) el.textContent = '已选 ' + count + ' 项';
}

function updateDeviceSelectAllState() {
  const visible = getVisibleDeviceCheckboxes();
  const allChecked = visible.length > 0 && Array.from(visible).every(cb => cb.checked);
  if (deviceSelectAll) deviceSelectAll.checked = allChecked;
  if (deviceSelectAllHeader) deviceSelectAllHeader.checked = allChecked;
}

function toggleSelectAll(checked) {
  getVisibleDeviceCheckboxes().forEach(cb => {
    cb.checked = checked;
  });
  updateDeviceSelectedCount();
  if (deviceSelectAll) deviceSelectAll.checked = checked;
  if (deviceSelectAllHeader) deviceSelectAllHeader.checked = checked;
}

if (deviceSelectAll) {
  deviceSelectAll.addEventListener('change', function() {
    toggleSelectAll(this.checked);
  });
}
if (deviceSelectAllHeader) {
  deviceSelectAllHeader.addEventListener('change', function() {
    toggleSelectAll(this.checked);
  });
}

// 监听每个 checkbox 的变化
document.querySelectorAll('.device-row-checkbox').forEach(cb => {
  cb.addEventListener('change', function() {
    updateDeviceSelectedCount();
    updateDeviceSelectAllState();
  });
});

// 批量操作
function batchAction(action) {
  const ids = getSelectedDeviceIds();
  if (ids.length === 0) {
    showToast('请先选择至少一条规则', 'error');
    return;
  }

  let payload = { ids: ids, action: action };

  if (action === 'delete') {
    if (!confirm('确定要批量删除选中的 ' + ids.length + ' 条规则吗？')) return;
  }

  fetch('/admin/device-rules/batch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  .then(r => {
    if (r.ok) {
      showToast('批量操作成功', 'success');
      setTimeout(() => location.reload(), 500);
    } else {
      showToast('批量操作失败', 'error');
    }
  })
  .catch(() => showToast('批量操作失败', 'error'));
}

function batchChangeVersion() {
  const ids = getSelectedDeviceIds();
  if (ids.length === 0) {
    showToast('请先选择至少一条规则', 'error');
    return;
  }

  const releaseId = prompt('请输入目标版本 ID：');
  if (!releaseId || isNaN(parseInt(releaseId, 10))) {
    showToast('请输入有效的版本 ID', 'error');
    return;
  }

  fetch('/admin/device-rules/batch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids: ids, action: 'update_version', release_id: parseInt(releaseId, 10) })
  })
  .then(r => {
    if (r.ok) {
      showToast('批量改版本成功', 'success');
      setTimeout(() => location.reload(), 500);
    } else {
      showToast('批量改版本失败', 'error');
    }
  })
  .catch(() => showToast('批量改版本失败', 'error'));
}

// ===== Endpoint address copy =====
function copyEndpoint() {
  const el = document.getElementById('endpoint-url');
  if (!el) return;
  el.select();
  document.execCommand('copy');
  showToast('已复制到剪贴板', 'success');
}

function applyCustomEndpoint() {
  const custom = document.getElementById('custom-endpoint');
  const display = document.getElementById('endpoint-url');
  if (!custom || !display) return;
  let url = custom.value.trim();
  if (!url) {
    showToast('请输入地址', 'error');
    return;
  }
  // 自动补全协议
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'http://' + url;
  }
  display.value = url;
  showToast('地址已更新', 'success');
}

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

// ===== Form submissions via fetch =====

// 处理所有带 data-fetch="true" 的表单
document.querySelectorAll('form[data-fetch="true"]').forEach(form => {
  form.addEventListener('submit', function(e) {
    e.preventDefault();
    const formData = new FormData(form);
    const url = form.action;

    // 确定请求方法
    let method = 'POST';
    const methodInput = form.querySelector('input[name="_method"]');
    if (methodInput) {
      method = methodInput.value;
    }

    fetch(url, {
      method: method,
      body: formData
    })
    .then(r => {
      if (r.ok) {
        showToast('操作成功', 'success');
        setTimeout(() => location.reload(), 500);
      } else {
        showToast('操作失败', 'error');
      }
    })
    .catch(() => showToast('操作失败', 'error'));
  });
});
