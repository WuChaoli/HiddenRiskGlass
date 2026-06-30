# APK Update Server UI 优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 APK Update Server 后台从单页重构为三标签页（APK管理/设备管理/检查日志），新增版本编辑删除、设备批量操作、搜索筛选等能力。

**Architecture:** 单页 JS 驱动标签切换（无刷新），后端新增 4 个 API，前端约 200 行原生 JS 实现列表过滤、分页、全选批量、弹窗控制。数据层复用现有 SQLite schema（`status` 软删除、`enabled` 控制访问）。

**Tech Stack:** FastAPI, Jinja2, SQLite, Vanilla JS

---

## 文件结构

| 文件 | 变更 | 说明 |
|------|------|------|
| `app/services.py` | 修改 | 新增 `update_release`, `delete_release`, `update_device_rule`, `batch_device_rules`；修改 `list_admin_state` 过滤 deleted |
| `app/main.py` | 修改 | 新增 4 个 admin API 路由 |
| `app/static/admin.css` | 修改 | 新增标签导航、弹窗、批量操作栏、状态标签样式 |
| `app/templates/admin.html` | 重写 | 三标签页结构 + 弹窗模板 |
| `app/static/admin.js` | **新增** | 标签切换、过滤分页、全选批量、弹窗、fetch 提交 |
| `tests/test_services.py` | 修改 | 新增服务函数测试 |
| `tests/test_api.py` | 修改 | 新增 API 路由测试 |

---

## Task 1: 后端服务层增强

**Files:**
- Modify: `app/services.py`
- Test: `tests/test_services.py`

### Step 1: 写 list_admin_state 过滤测试

在 `tests/test_services.py` 中添加：

```python
def test_list_admin_state_excludes_deleted_releases(settings, db_conn):
    """已删除的 release 不应出现在 admin 状态列表中"""
    from app.services import publish_release, list_admin_state
    import io

    # 发布两个版本
    manifest1 = publish_release(
        settings, "test1.apk", io.BytesIO(b"apk1"),
        version_code=1, version_name="1.0.0",
        release_notes="", mandatory=False, base_url="http://test"
    )
    manifest2 = publish_release(
        settings, "test2.apk", io.BytesIO(b"apk2"),
        version_code=2, version_name="2.0.0",
        release_notes="", mandatory=False, base_url="http://test"
    )

    # 删除其中一个
    from app.services import delete_release
    release_id_1 = int(manifest1["apkUrl"].split("/")[-2])
    delete_release(settings, release_id_1)

    state = list_admin_state(settings)
    release_ids = [r["id"] for r in state["releases"]]
    assert release_id_1 not in release_ids
```

Run: `pytest tests/test_services.py::test_list_admin_state_excludes_deleted_releases -v`
Expected: FAIL（`delete_release` not defined）

### Step 2: 修改 list_admin_state 过滤 deleted releases

在 `app/services.py` 中修改 `list_admin_state` 函数：

```python
# 原代码：
# releases = conn.execute("SELECT * FROM releases ORDER BY created_at DESC, id DESC").fetchall()
# 改为：
releases = conn.execute(
    "SELECT * FROM releases WHERE status = ? ORDER BY created_at DESC, id DESC",
    (STATUS_ACTIVE,)
).fetchall()
```

同时确认文件顶部已导入 `STATUS_ACTIVE`（来自 `app.schemas`）。

Run: `pytest tests/test_services.py::test_list_admin_state_excludes_deleted_releases -v`
Expected: 仍 FAIL（delete_release 未实现）

### Step 3: 实现 delete_release（软删除 + 清理文件）

在 `app/services.py` 的 `delete_device_rule` 函数之后添加：

```python
def delete_release(settings: Settings, release_id: int) -> None:
    release = get_release_by_id(settings, release_id)
    if release is None:
        raise ValueError("release not found")

    with db_session(settings) as conn:
        conn.execute(
            "UPDATE releases SET status = ? WHERE id = ?",
            ("deleted", int(release_id)),
        )

    # 清理本地 APK 文件和目录
    apk_path = Path(release["apk_path"])
    release_dir = apk_path.parent
    if release_dir.exists():
        import shutil
        shutil.rmtree(release_dir, ignore_errors=True)
```

Run: `pytest tests/test_services.py::test_list_admin_state_excludes_deleted_releases -v`
Expected: PASS

### Step 4: 写 update_release 测试

在 `tests/test_services.py` 中添加：

```python
def test_update_release(settings):
    from app.services import publish_release, update_release, get_release_by_id
    import io

    manifest = publish_release(
        settings, "test.apk", io.BytesIO(b"apk"),
        version_code=1, version_name="1.0.0",
        release_notes="old notes", mandatory=False, base_url="http://test"
    )
    release_id = int(manifest["apkUrl"].split("/")[-2])

    update_release(settings, release_id, version_name="1.0.1", release_notes="new notes", mandatory=True)

    release = get_release_by_id(settings, release_id)
    assert release["version_name"] == "1.0.1"
    assert release["release_notes"] == "new notes"
    assert release["mandatory"] == 1
```

Run: `pytest tests/test_services.py::test_update_release -v`
Expected: FAIL（`update_release` not defined）

### Step 5: 实现 update_release

在 `app/services.py` 的 `delete_release` 之后添加：

```python
def update_release(
    settings: Settings,
    release_id: int,
    version_name: str,
    release_notes: str,
    mandatory: bool,
) -> None:
    release = get_release_by_id(settings, release_id)
    if release is None or release["status"] != STATUS_ACTIVE:
        raise ValueError("release not found or not active")

    version_name = version_name.strip()
    if not version_name:
        raise ValueError("version_name is required")

    with db_session(settings) as conn:
        conn.execute(
            """
            UPDATE releases
            SET version_name = ?, release_notes = ?, mandatory = ?
            WHERE id = ?
            """,
            (version_name, release_notes.strip(), 1 if mandatory else 0, int(release_id)),
        )
```

Run: `pytest tests/test_services.py::test_update_release -v`
Expected: PASS

### Step 6: 写 update_device_rule 测试

在 `tests/test_services.py` 中添加：

```python
def test_update_device_rule(settings):
    from app.services import publish_release, create_device_rule, update_device_rule, get_release_by_id
    import io

    manifest = publish_release(
        settings, "test.apk", io.BytesIO(b"apk"),
        version_code=1, version_name="1.0.0",
        release_notes="", mandatory=False, base_url="http://test"
    )
    release_id = int(manifest["apkUrl"].split("/")[-2])

    rule_id = create_device_rule(settings, "NS-001", release_id, "old note")
    update_device_rule(settings, rule_id, nscode="NS-002", release_id=release_id, note="new note", enabled=False)

    from app.db import db_session
    with db_session(settings) as conn:
        row = conn.execute("SELECT * FROM device_rules WHERE id = ?", (rule_id,)).fetchone()
    assert row["nscode"] == "NS-002"
    assert row["note"] == "new note"
    assert row["enabled"] == 0
```

Run: `pytest tests/test_services.py::test_update_device_rule -v`
Expected: FAIL（`update_device_rule` not defined）

### Step 7: 实现 update_device_rule

在 `app/services.py` 的 `update_release` 之后添加：

```python
def update_device_rule(
    settings: Settings,
    rule_id: int,
    nscode: str,
    release_id: int,
    note: str,
    enabled: bool,
) -> None:
    if not nscode.strip():
        raise ValueError("nscode is required")

    release = get_release_by_id(settings, release_id)
    if release is None or release["status"] != STATUS_ACTIVE:
        raise ValueError("rule release must exist and be active")

    with db_session(settings) as conn:
        conn.execute(
            """
            UPDATE device_rules
            SET nscode = ?, release_id = ?, note = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            (nscode.strip(), int(release_id), note.strip(), 1 if enabled else 0, int(rule_id)),
        )
```

Run: `pytest tests/test_services.py::test_update_device_rule -v`
Expected: PASS

### Step 8: 写 batch_device_rules 测试

在 `tests/test_services.py` 中添加：

```python
def test_batch_device_rules_update_version(settings):
    from app.services import publish_release, create_device_rule, batch_device_rules
    from app.db import db_session
    import io

    manifest1 = publish_release(settings, "t1.apk", io.BytesIO(b"a"), 1, "1.0", "", False, "http://t")
    manifest2 = publish_release(settings, "t2.apk", io.BytesIO(b"b"), 2, "2.0", "", False, "http://t")
    rid1 = int(manifest1["apkUrl"].split("/")[-2])
    rid2 = int(manifest2["apkUrl"].split("/")[-2])

    id1 = create_device_rule(settings, "NS-A", rid1)
    id2 = create_device_rule(settings, "NS-B", rid1)

    batch_device_rules(settings, [id1, id2], "update_version", release_id=rid2)

    with db_session(settings) as conn:
        rows = conn.execute("SELECT release_id FROM device_rules WHERE id IN (?, ?)", (id1, id2)).fetchall()
    assert rows[0]["release_id"] == rid2
    assert rows[1]["release_id"] == rid2


def test_batch_device_rules_delete(settings):
    from app.services import publish_release, create_device_rule, batch_device_rules
    from app.db import db_session
    import io

    manifest = publish_release(settings, "t.apk", io.BytesIO(b"x"), 1, "1.0", "", False, "http://t")
    rid = int(manifest["apkUrl"].split("/")[-2])
    id1 = create_device_rule(settings, "NS-A", rid)

    batch_device_rules(settings, [id1], "delete")

    with db_session(settings) as conn:
        row = conn.execute("SELECT * FROM device_rules WHERE id = ?", (id1,)).fetchone()
    assert row is None
```

Run: `pytest tests/test_services.py::test_batch_device_rules_update_version tests/test_services.py::test_batch_device_rules_delete -v`
Expected: FAIL（`batch_device_rules` not defined）

### Step 9: 实现 batch_device_rules

在 `app/services.py` 的 `update_device_rule` 之后添加：

```python
def batch_device_rules(
    settings: Settings,
    rule_ids: list[int],
    action: str,
    release_id: int | None = None,
) -> dict[str, object]:
    if not rule_ids:
        raise ValueError("no rules selected")

    valid_actions = {"update_version", "enable", "disable", "delete"}
    if action not in valid_actions:
        raise ValueError(f"invalid action: {action}")

    if action == "update_version":
        if release_id is None:
            raise ValueError("release_id is required for update_version")
        release = get_release_by_id(settings, release_id)
        if release is None or release["status"] != STATUS_ACTIVE:
            raise ValueError("target release must exist and be active")

    placeholders = ",".join("?" * len(rule_ids))

    with db_session(settings) as conn:
        if action == "delete":
            conn.execute(
                f"DELETE FROM device_rules WHERE id IN ({placeholders})",
                tuple(int(rid) for rid in rule_ids),
            )
        elif action == "update_version":
            conn.execute(
                f"""
                UPDATE device_rules
                SET release_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id IN ({placeholders})
                """,
                (int(release_id),) + tuple(int(rid) for rid in rule_ids),
            )
        elif action == "enable":
            conn.execute(
                f"UPDATE device_rules SET enabled = 1, updated_at = CURRENT_TIMESTAMP WHERE id IN ({placeholders})",
                tuple(int(rid) for rid in rule_ids),
            )
        elif action == "disable":
            conn.execute(
                f"UPDATE device_rules SET enabled = 0, updated_at = CURRENT_TIMESTAMP WHERE id IN ({placeholders})",
                tuple(int(rid) for rid in rule_ids),
            )

    return {"processed": len(rule_ids), "action": action}
```

Run: `pytest tests/test_services.py::test_batch_device_rules_update_version tests/test_services.py::test_batch_device_rules_delete -v`
Expected: PASS

### Step 10: Commit 后端服务层

```bash
cd tools/apk_update_server
git add app/services.py tests/test_services.py
git commit -m "feat: add release edit/delete and device rule batch operations

- list_admin_state now filters out deleted releases
- add update_release to edit version metadata
- add delete_release with soft-delete and file cleanup
- add update_device_rule to edit single rule
- add batch_device_rules for bulk update/enable/disable/delete

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: 后端 API 路由

**Files:**
- Modify: `app/main.py`
- Test: `tests/test_api.py`

### Step 1: 写 release 编辑/删除 API 测试

在 `tests/test_api.py` 中添加：

```python
def test_admin_update_release(client, auth_cookie, settings):
    """测试编辑版本信息"""
    import io
    # 先发布一个版本
    response = client.post(
        "/admin/releases",
        data={"versionCode": 1, "versionName": "1.0.0", "releaseNotes": "old"},
        files={"apk": ("test.apk", io.BytesIO(b"apk"), "application/vnd.android.package-archive")},
        cookies=auth_cookie,
    )
    assert response.status_code == 303

    # 获取 release_id
    from app.services import list_admin_state
    state = list_admin_state(settings)
    release_id = state["releases"][0]["id"]

    # 编辑
    response = client.put(
        f"/admin/releases/{release_id}",
        data={"versionName": "1.0.1", "releaseNotes": "new notes", "mandatory": "on"},
        cookies=auth_cookie,
    )
    assert response.status_code == 200


def test_admin_delete_release(client, auth_cookie, settings):
    """测试删除版本"""
    import io
    response = client.post(
        "/admin/releases",
        data={"versionCode": 1, "versionName": "1.0.0"},
        files={"apk": ("test.apk", io.BytesIO(b"apk"), "application/vnd.android.package-archive")},
        cookies=auth_cookie,
    )
    assert response.status_code == 303

    from app.services import list_admin_state
    state = list_admin_state(settings)
    release_id = state["releases"][0]["id"]

    response = client.post(
        f"/admin/releases/{release_id}/delete",
        cookies=auth_cookie,
    )
    assert response.status_code == 200

    state = list_admin_state(settings)
    assert len(state["releases"]) == 0
```

Run: `pytest tests/test_api.py::test_admin_update_release tests/test_api.py::test_admin_delete_release -v`
Expected: FAIL（404，路由不存在）

### Step 2: 实现 release 编辑/删除路由

在 `app/main.py` 中，在 `admin_set_default_release` 函数之后、`admin_create_device_rule` 之前，添加：

```python
    @app.put("/admin/releases/{release_id}")
    async def admin_update_release(
        request: Request,
        release_id: int,
        version_name: str = Form(..., alias="versionName"),
        release_notes: str = Form("", alias="releaseNotes"),
        mandatory: str | None = Form(None),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            update_release(
                resolved_settings,
                release_id=release_id,
                version_name=version_name,
                release_notes=release_notes,
                mandatory=mandatory is not None,
            )
        except ValueError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
        return JSONResponse({"ok": True})

    @app.post("/admin/releases/{release_id}/delete")
    async def admin_delete_release(request: Request, release_id: int):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            delete_release(resolved_settings, release_id)
        except ValueError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
        return JSONResponse({"ok": True})
```

确保 `update_release` 和 `delete_release` 已从 `app.services` 导入。

Run: `pytest tests/test_api.py::test_admin_update_release tests/test_api.py::test_admin_delete_release -v`
Expected: PASS

### Step 3: 写 device rule 编辑/批量操作 API 测试

在 `tests/test_api.py` 中添加：

```python
def test_admin_update_device_rule(client, auth_cookie, settings):
    """测试编辑设备规则"""
    import io
    # 先发布版本和创建规则
    client.post(
        "/admin/releases",
        data={"versionCode": 1, "versionName": "1.0.0"},
        files={"apk": ("test.apk", io.BytesIO(b"apk"), "application/vnd.android.package-archive")},
        cookies=auth_cookie,
    )
    from app.services import list_admin_state
    state = list_admin_state(settings)
    release_id = state["releases"][0]["id"]

    client.post(
        "/admin/device-rules",
        data={"nscode": "NS-001", "releaseId": release_id, "note": "old"},
        cookies=auth_cookie,
    )

    # 获取 rule_id
    from app.db import db_session
    with db_session(settings) as conn:
        row = conn.execute("SELECT id FROM device_rules WHERE nscode = ?", ("NS-001",)).fetchone()
    rule_id = row["id"]

    response = client.put(
        f"/admin/device-rules/{rule_id}",
        data={"nscode": "NS-002", "releaseId": release_id, "note": "new", "enabled": "0"},
        cookies=auth_cookie,
    )
    assert response.status_code == 200


def test_admin_batch_device_rules(client, auth_cookie, settings):
    """测试批量操作设备规则"""
    import io
    client.post(
        "/admin/releases",
        data={"versionCode": 1, "versionName": "1.0.0"},
        files={"apk": ("test.apk", io.BytesIO(b"apk"), "application/vnd.android.package-archive")},
        cookies=auth_cookie,
    )
    from app.services import list_admin_state
    state = list_admin_state(settings)
    release_id = state["releases"][0]["id"]

    client.post(
        "/admin/device-rules",
        data={"nscode": "NS-A", "releaseId": release_id},
        cookies=auth_cookie,
    )
    client.post(
        "/admin/device-rules",
        data={"nscode": "NS-B", "releaseId": release_id},
        cookies=auth_cookie,
    )

    from app.db import db_session
    with db_session(settings) as conn:
        rows = conn.execute("SELECT id FROM device_rules").fetchall()
    ids = [r["id"] for r in rows]

    response = client.post(
        "/admin/device-rules/batch",
        json={"ids": ids, "action": "disable"},
        cookies=auth_cookie,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["ok"] is True
```

Run: `pytest tests/test_api.py::test_admin_update_device_rule tests/test_api.py::test_admin_batch_device_rules -v`
Expected: FAIL（路由不存在）

### Step 4: 实现 device rule 编辑/批量操作路由

在 `app/main.py` 中，在现有的 `admin_delete_device_rule` 之后添加：

```python
    @app.put("/admin/device-rules/{rule_id}")
    async def admin_update_device_rule(
        request: Request,
        rule_id: int,
        nscode: str = Form(...),
        release_id: int = Form(..., alias="releaseId"),
        note: str = Form(""),
        enabled: str = Form("1"),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            update_device_rule(
                resolved_settings,
                rule_id=rule_id,
                nscode=nscode,
                release_id=release_id,
                note=note,
                enabled=enabled not in {"0", "false", ""},
            )
        except ValueError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
        return JSONResponse({"ok": True})

    @app.post("/admin/device-rules/batch")
    async def admin_batch_device_rules(request: Request):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            body = await request.json()
            result = batch_device_rules(
                resolved_settings,
                rule_ids=body.get("ids", []),
                action=body.get("action", ""),
                release_id=body.get("release_id"),
            )
        except ValueError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
        return JSONResponse({"ok": True, **result})
```

确保 `update_device_rule` 和 `batch_device_rules` 已从 `app.services` 导入，并将 `JSONResponse` 添加到已有的 imports 中。

同时需要更新 `_is_protected_admin_request` 函数，将新的 PUT 和 batch 路径加入保护列表：

```python
def _is_protected_admin_request(request: Request) -> bool:
    path = request.url.path
    method = request.method.upper()
    if method == "GET":
        return path == "/admin"
    if method != "POST" and method != "PUT":
        return False
    if path in {"/admin/releases", "/admin/default-release", "/admin/device-rules", "/admin/device-rules/batch"}:
        return True
    if path.startswith("/admin/releases/") and (path.endswith("/delete") or path.count("/") == 4):
        # /admin/releases/{id}/delete 或 /admin/releases/{id}
        return True
    if path.startswith("/admin/device-rules/") and (path.endswith("/delete") or path.count("/") == 4):
        # /admin/device-rules/{id}/delete 或 /admin/device-rules/{id}
        return True
    return False
```

注意：上面的路径判断需要更精确。让我重新写：

```python
def _is_protected_admin_request(request: Request) -> bool:
    path = request.url.path
    method = request.method.upper()
    if method == "GET":
        return path == "/admin"
    if method not in {"POST", "PUT"}:
        return False
    if path in {"/admin/releases", "/admin/default-release", "/admin/device-rules", "/admin/device-rules/batch"}:
        return True
    if path.startswith("/admin/releases/"):
        return True
    if path.startswith("/admin/device-rules/"):
        return True
    return False
```

Run: `pytest tests/test_api.py::test_admin_update_device_rule tests/test_api.py::test_admin_batch_device_rules -v`
Expected: PASS

### Step 5: Commit 后端 API

```bash
cd tools/apk_update_server
git add app/main.py tests/test_api.py
git commit -m "feat: add admin API routes for release and device rule management

- PUT /admin/releases/{id} — edit release metadata
- POST /admin/releases/{id}/delete — soft-delete release
- PUT /admin/device-rules/{id} — edit device rule
- POST /admin/device-rules/batch — bulk operations
- update auth middleware to protect new routes

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: 前端 CSS 扩展

**Files:**
- Modify: `app/static/admin.css`

### Step 1: 添加标签导航和弹窗样式

在 `app/static/admin.css` 末尾追加：

```css
/* 标签导航 */
.tab-nav {
  display: flex;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  padding: 0 max(24px, calc((100vw - 1120px) / 2));
  gap: 4px;
}

.tab-btn {
  padding: 12px 20px;
  border: none;
  background: none;
  color: var(--muted);
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
  border-bottom: 3px solid transparent;
  margin-bottom: -1px;
  min-width: auto;
}

.tab-btn:hover {
  color: var(--text);
}

.tab-btn.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

/* 标签内容区域 */
.tab-section {
  display: none;
}

.tab-section.active {
  display: block;
}

/* 弹窗 */
.modal-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 100;
  align-items: center;
  justify-content: center;
}

.modal-overlay.active {
  display: flex;
}

.modal {
  background: var(--panel);
  border-radius: 8px;
  padding: 24px;
  width: 480px;
  max-width: 90vw;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,0.1);
}

.modal h3 {
  margin-top: 0;
}

.modal-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 20px;
}

.modal .form-row {
  margin-bottom: 12px;
}

.modal .form-row label {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 4px;
  display: block;
}

.modal .hint {
  font-size: 11px;
  color: var(--muted);
  margin-top: 2px;
}

.modal .error-msg {
  color: var(--danger);
  font-size: 13px;
  margin-top: 8px;
}
```

### Step 2: 添加批量操作栏和状态标签样式

继续追加：

```css
/* 批量操作栏 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  margin-bottom: 12px;
}

.batch-bar label {
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.batch-bar .count {
  font-size: 12px;
  color: var(--muted);
}

.batch-bar .spacer {
  flex: 1;
}

.batch-bar button {
  min-width: auto;
  padding: 6px 12px;
  font-size: 12px;
}

/* 搜索筛选栏 */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.filter-bar .field {
  flex: 1;
  min-width: 140px;
}

.filter-bar .field label {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 4px;
  display: block;
}

/* 状态标签 */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}

.status-badge .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-badge.enabled .dot {
  background: #10b981;
}

.status-badge.disabled .dot {
  background: #ef4444;
}

/* 表格操作按钮 */
.row-actions {
  display: flex;
  gap: 4px;
}

.row-actions button {
  min-width: auto;
  padding: 4px 8px;
  font-size: 11px;
}

/* 分页 */
.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.pagination .info {
  font-size: 12px;
  color: var(--muted);
}

.pagination .pager {
  display: flex;
  gap: 4px;
}

.pagination .pager button {
  min-width: auto;
  padding: 4px 10px;
  font-size: 11px;
}

/* 统计信息 */
.stats-line {
  font-size: 12px;
  color: var(--muted);
  margin-top: 8px;
}

/* Toast 提示 */
.toast {
  position: fixed;
  top: 20px;
  right: 20px;
  padding: 12px 16px;
  border-radius: 6px;
  font-weight: 700;
  font-size: 13px;
  z-index: 200;
  animation: toast-in 0.3s ease;
}

.toast.success {
  background: #d1fae5;
  color: #065f46;
  border: 1px solid #a7f3d0;
}

.toast.error {
  background: #fef3f2;
  color: var(--danger);
  border: 1px solid #fecdca;
}

@keyframes toast-in {
  from { transform: translateX(100%); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

/* 响应式 */
@media (max-width: 780px) {
  .tab-nav {
    padding: 0 16px;
  }
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .filter-bar .field {
    min-width: auto;
  }
  .batch-bar {
    flex-wrap: wrap;
  }
}
```

### Step 3: Commit CSS

```bash
cd tools/apk_update_server
git add app/static/admin.css
git commit -m "feat: add admin UI styles for tabs, modals, batch ops, filters

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: 前端 HTML 重写

**Files:**
- Modify: `app/templates/admin.html`

### Step 1: 重写 admin.html 基础结构

将 `app/templates/admin.html` 整体替换为三标签页结构。核心骨架：

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>APK 更新后台</title>
  <link rel="stylesheet" href="/static/admin.css">
</head>
<body>
  <header class="topbar">
    <div>
      <h1>APK 更新后台</h1>
      <p>发布 APK、管理设备规则、查看更新日志。</p>
    </div>
    <form action="/logout" method="post">
      <button class="secondary" type="submit">退出</button>
    </form>
  </header>

  <nav class="tab-nav">
    <button class="tab-btn active" data-tab="apk">APK 管理</button>
    <button class="tab-btn" data-tab="devices">设备管理</button>
    <button class="tab-btn" data-tab="logs">检查日志</button>
  </nav>

  <main class="layout">
    <!-- APK 管理标签 -->
    <section id="tab-apk" class="tab-section active">
      <!-- ... -->
    </section>

    <!-- 设备管理标签 -->
    <section id="tab-devices" class="tab-section">
      <!-- ... -->
    </section>

    <!-- 检查日志标签 -->
    <section id="tab-logs" class="tab-section">
      <!-- ... -->
    </section>
  </main>

  <!-- 弹窗模板 -->
  <!-- ... -->

  <script src="/static/admin.js"></script>
</body>
</html>
```

### Step 2: 构建 APK 管理标签页

包含：
- 左侧面板：发布表单（含"基于已有版本"select、versionCode、versionName、releaseNotes、强制更新checkbox、设为默认checkbox、APK文件上传）
- 右侧面板：版本列表表格（版本、大小、强制、默认、操作列含编辑/删除按钮）
- 编辑版本弹窗
- 删除确认弹窗

### Step 3: 构建设备管理标签页

包含：
- 搜索筛选栏（NSCODE搜索、状态筛选、版本筛选、重置按钮、+ 新增设备按钮）
- 批量操作栏（全选、已选计数、批量改版本、批量允许、批量拒绝、批量删除）
- 设备规则列表表格（checkbox、NSCODE、目标版本、状态、备注、更新时间、操作）
- 新增/编辑设备规则弹窗

### Step 4: 构建检查日志标签页

包含：
- 搜索筛选栏（NSCODE搜索、结果筛选、每页显示、重置按钮）
- 日志表格（时间、NSCODE、当前版本、命中版本、结果）
- 分页控件

### Step 5: Commit HTML

```bash
cd tools/apk_update_server
git add app/templates/admin.html
git commit -m "feat: rewrite admin.html with three-tab layout and modals

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: 前端 JS 编写

**Files:**
- Create: `app/static/admin.js`

### Step 1: 标签切换 + Toast + 工具函数

```javascript
// 标签切换
document.querySelectorAll('.tab-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const tab = btn.dataset.tab;
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-section').forEach(s => s.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById('tab-' + tab).classList.add('active');
    location.hash = tab;
  });
});

// 根据 hash 初始化标签
const initTab = () => {
  const tab = location.hash.replace('#', '') || 'apk';
  const btn = document.querySelector(`.tab-btn[data-tab="${tab}"]`);
  if (btn) btn.click();
};
window.addEventListener('hashchange', initTab);
initTab();

// Toast 提示
function showToast(message, type = 'success') {
  const toast = document.createElement('div');
  toast.className = 'toast ' + type;
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 3000);
}

// fetch 提交工具
async function postJson(url, body) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body),
  });
  return resp.json();
}
```

### Step 2: APK 管理 - 版本复制自动填充

```javascript
// 基于历史版本复制
const baseSelect = document.getElementById('baseRelease');
if (baseSelect) {
  const releasesData = JSON.parse(document.getElementById('releases-data').textContent);
  baseSelect.addEventListener('change', () => {
    const id = baseSelect.value;
    if (!id) return;
    const rel = releasesData.find(r => String(r.id) === id);
    if (!rel) return;
    document.querySelector('[name="versionName"]').value = rel.version_name;
    document.querySelector('[name="releaseNotes"]').value = rel.release_notes || '';
    document.querySelector('[name="versionCode"]').value = rel.version_code + 1;
    document.querySelector('[name="mandatory"]').checked = !!rel.mandatory;
  });
}
```

### Step 3: 列表过滤和日志分页

```javascript
// 通用前端过滤
defineFilter('device', '#device-search', '#device-status-filter', '#device-version-filter');
defineFilter('log', '#log-search', '#log-result-filter');

function defineFilter(prefix, searchSel, ...filterSels) {
  const search = document.querySelector(searchSel);
  const filters = filterSels.map(sel => document.querySelector(sel));
  const rows = document.querySelectorAll('.' + prefix + '-row');

  const apply = () => {
    const q = search ? search.value.trim().toLowerCase() : '';
    const vals = filters.map(f => f ? f.value : '');
    rows.forEach(row => {
      let ok = true;
      if (q && !row.dataset.search.includes(q)) ok = false;
      vals.forEach((v, i) => {
        if (v && row.dataset['filter' + i] !== v) ok = false;
      });
      row.style.display = ok ? '' : 'none';
    });
    updateCount(prefix);
  };

  if (search) search.addEventListener('input', apply);
  filters.forEach(f => f && f.addEventListener('change', apply));
}

// 日志分页
function paginate(prefix, pageSizeSel) {
  const sizeEl = document.querySelector(pageSizeSel);
  const rows = Array.from(document.querySelectorAll('.' + prefix + '-row'));
  let page = 0;
  let size = parseInt(sizeEl?.value || 30);

  const render = () => {
    const visible = rows.filter(r => r.style.display !== 'none');
    const total = visible.length;
    const start = page * size;
    visible.forEach((r, i) => {
      r.style.display = (i >= start && i < start + size) ? '' : 'none';
    });
    // 更新分页信息
  };

  if (sizeEl) sizeEl.addEventListener('change', () => { size = parseInt(sizeEl.value); page = 0; render(); });
  document.querySelector('.' + prefix + '-prev')?.addEventListener('click', () => { if (page > 0) { page--; render(); } });
  document.querySelector('.' + prefix + '-next')?.addEventListener('click', () => { if ((page + 1) * size < rows.length) { page++; render(); } });
}
paginate('log', '#log-page-size');
```

### Step 4: 全选和批量操作

```javascript
// 全选/批量操作
document.querySelectorAll('.batch-select-all').forEach(master => {
  const section = master.closest('.tab-section') || document;
  const slaves = section.querySelectorAll('.batch-select');
  master.addEventListener('change', () => {
    slaves.forEach(cb => cb.checked = master.checked);
    updateBatchCount(section);
  });
});

document.querySelectorAll('.batch-select').forEach(cb => {
  cb.addEventListener('change', () => {
    const section = cb.closest('.tab-section') || document;
    updateBatchCount(section);
  });
});

function updateBatchCount(section) {
  const checked = section.querySelectorAll('.batch-select:checked');
  const el = section.querySelector('.batch-count');
  if (el) el.textContent = '已选 ' + checked.length + ' 项';
}

// 批量操作按钮
document.querySelectorAll('.batch-action').forEach(btn => {
  btn.addEventListener('click', async () => {
    const action = btn.dataset.action;
    const section = btn.closest('.tab-section');
    const ids = Array.from(section.querySelectorAll('.batch-select:checked')).map(cb => cb.value);
    if (!ids.length) { showToast('请先选择设备', 'error'); return; }

    if (!confirm('确定对选中的 ' + ids.length + ' 项执行「' + btn.textContent.trim() + '」？')) return;

    let body = {ids, action};
    if (action === 'update_version') {
      const releaseId = prompt('请选择目标版本 ID:');
      if (!releaseId) return;
      body.release_id = parseInt(releaseId);
    }

    const data = await postJson('/admin/device-rules/batch', body);
    if (data.ok) {
      showToast('操作成功');
      location.reload();
    } else {
      showToast(data.error || '操作失败', 'error');
    }
  });
});
```

### Step 5: 弹窗控制和表单提交

```javascript
// 弹窗控制
function openModal(id) { document.getElementById(id).classList.add('active'); }
function closeModal(id) { document.getElementById(id).classList.remove('active'); }

document.querySelectorAll('.modal-overlay').forEach(overlay => {
  overlay.addEventListener('click', e => {
    if (e.target === overlay) overlay.classList.remove('active');
  });
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') document.querySelectorAll('.modal-overlay.active').forEach(m => m.classList.remove('active'));
});

// 编辑版本
document.querySelectorAll('.btn-edit-release').forEach(btn => {
  btn.addEventListener('click', () => {
    const id = btn.dataset.id;
    document.getElementById('edit-release-id').value = id;
    document.getElementById('edit-version-name').value = btn.dataset.versionName;
    document.getElementById('edit-release-notes').value = btn.dataset.releaseNotes;
    document.getElementById('edit-mandatory').checked = btn.dataset.mandatory === 'true';
    openModal('modal-edit-release');
  });
});

document.getElementById('form-edit-release')?.addEventListener('submit', async e => {
  e.preventDefault();
  const id = document.getElementById('edit-release-id').value;
  const form = e.target;
  const data = new FormData(form);
  const resp = await fetch('/admin/releases/' + id, {method: 'PUT', body: data});
  const result = await resp.json();
  if (result.ok) { showToast('保存成功'); location.reload(); }
  else { showToast(result.error || '保存失败', 'error'); }
});

// 删除版本
document.querySelectorAll('.btn-delete-release').forEach(btn => {
  btn.addEventListener('click', () => {
    const id = btn.dataset.id;
    document.getElementById('delete-release-id').value = id;
    document.getElementById('delete-release-name').textContent = btn.dataset.versionName;
    openModal('modal-delete-release');
  });
});

document.getElementById('confirm-delete-release')?.addEventListener('click', async () => {
  const id = document.getElementById('delete-release-id').value;
  const resp = await fetch('/admin/releases/' + id + '/delete', {method: 'POST'});
  const result = await resp.json();
  if (result.ok) { showToast('删除成功'); location.reload(); }
  else { showToast(result.error || '删除失败', 'error'); }
});

// 编辑设备规则
document.querySelectorAll('.btn-edit-device').forEach(btn => {
  btn.addEventListener('click', () => {
    document.getElementById('device-modal-title').textContent = '编辑设备规则';
    document.getElementById('device-rule-id').value = btn.dataset.id;
    document.getElementById('device-nscode').value = btn.dataset.nscode;
    document.getElementById('device-release').value = btn.dataset.releaseId;
    document.getElementById('device-note').value = btn.dataset.note;
    document.getElementById('device-enabled').value = btn.dataset.enabled;
    openModal('modal-device');
  });
});

// 新增设备
document.getElementById('btn-add-device')?.addEventListener('click', () => {
  document.getElementById('device-modal-title').textContent = '新增设备规则';
  document.getElementById('device-rule-id').value = '';
  document.getElementById('form-device').reset();
  document.getElementById('device-enabled').value = '1';
  openModal('modal-device');
});

// 设备表单提交（新增或编辑）
document.getElementById('form-device')?.addEventListener('submit', async e => {
  e.preventDefault();
  const id = document.getElementById('device-rule-id').value;
  const form = e.target;
  const data = new FormData(form);
  const url = id ? '/admin/device-rules/' + id : '/admin/device-rules';
  const method = id ? 'PUT' : 'POST';
  const resp = await fetch(url, {method, body: data});
  const result = await resp.json();
  if (result.ok) { showToast(id ? '保存成功' : '添加成功'); location.reload(); }
  else { showToast(result.error || '操作失败', 'error'); }
});

// 删除设备
document.querySelectorAll('.btn-delete-device').forEach(btn => {
  btn.addEventListener('click', async () => {
    if (!confirm('确定删除设备 ' + btn.dataset.nscode + ' 的规则？')) return;
    const resp = await fetch('/admin/device-rules/' + btn.dataset.id + '/delete', {method: 'POST'});
    const result = await resp.json();
    if (result.ok || resp.ok) { showToast('删除成功'); location.reload(); }
    else { showToast('删除失败', 'error'); }
  });
});
```

### Step 6: Commit JS

```bash
cd tools/apk_update_server
git add app/static/admin.js
git commit -m "feat: add admin.js with tab switching, filters, batch ops, modals

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: 运行完整测试

### Step 1: 运行全部测试

```bash
cd tools/apk_update_server
pytest tests/ -v
```

Expected: 所有测试通过

### Step 2: 手动验证

```bash
cd tools/apk_update_server
$env:ADMIN_PASSWORD = "test"
python server.py --host 127.0.0.1 --port 8080
```

打开浏览器访问 `http://127.0.0.1:8080/login`，登录后验证：

- [ ] 三个标签页正常切换，刷新后保持当前标签
- [ ] APK 管理页：基于历史版本复制自动填充正确
- [ ] APK 管理页：版本列表显示正常，编辑/删除按钮可用
- [ ] 设备管理页：搜索、状态筛选、版本筛选正常工作
- [ ] 设备管理页：弹窗新增设备成功
- [ ] 设备管理页：全选 + 批量允许/拒绝/改版本/删除正常工作
- [ ] 检查日志页：搜索、结果筛选、分页正常工作
- [ ] 所有操作后 Toast 提示正常显示

### Step 3: Commit（如验证通过）

```bash
cd tools/apk_update_server
git add .
git commit -m "feat: complete admin UI redesign with tabs, batch ops, and modals

- Three-tab layout: APK management, device management, check logs
- Release edit/delete with soft-delete and file cleanup
- Device rules with search, filters, and batch operations
- Check logs with pagination and filtering
- All backend APIs fully tested

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## 自我审查

### Spec 覆盖检查

| Spec 需求 | 对应 Task/Step |
|-----------|---------------|
| 三标签页结构 | Task 4 Step 1 |
| APK 发布 + 基于历史复制 | Task 4 Step 2 + Task 5 Step 2 |
| 版本编辑/删除 | Task 1 Step 4-5 + Task 2 Step 1-2 + Task 4 Step 2 + Task 5 Step 5 |
| 设备弹窗新增 | Task 4 Step 3 + Task 5 Step 5 |
| 设备搜索筛选 | Task 4 Step 3 + Task 5 Step 3 |
| 设备批量操作 | Task 1 Step 8-9 + Task 2 Step 3-4 + Task 4 Step 3 + Task 5 Step 4 |
| 检查日志搜索筛选分页 | Task 4 Step 4 + Task 5 Step 3 |
| 状态列（允许/拒绝） | Task 4 Step 3 + CSS |
| 删除清理本地文件 | Task 1 Step 3 |
| 软删除过滤 | Task 1 Step 1-2 |

无遗漏。

### Placeholder 扫描

无 TBD/TODO/"implement later"/"add appropriate error handling" 等占位符。每步都有具体代码或命令。

### 类型一致性

- `enabled` 在数据库中存储为 `1/0` int，API 接收 `"1"`/`"0"` string form 数据，JS 中 `dataset.enabled` 传递 string
- `release_id` 在 batch API 中为 int，与数据库一致
- `mandatory` checkbox 在 form 中：存在=on，不存在=None，与后端 `mandatory is not None` 一致
