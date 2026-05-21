from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient


def make_client(isolated_env):
    from app.config import load_settings
    from app.main import create_app

    settings = load_settings()
    return TestClient(create_app(settings))


def login(client: TestClient) -> None:
    response = client.post(
        "/login",
        data={"password": "test-password"},
        follow_redirects=False,
    )
    assert response.status_code == 303
    assert response.headers["location"] == "/admin"


def publish_release(
    client: TestClient,
    version_code: int,
    version_name: str,
    payload: bytes = b"apk-bytes",
    filename: str = "app-standard-debug.apk",
    make_default: bool = False,
):
    data = {
        "versionCode": str(version_code),
        "versionName": version_name,
        "releaseNotes": f"notes {version_name}",
        "mandatory": "on",
    }
    if make_default:
        data["makeDefault"] = "on"
    return client.post(
        "/admin/releases",
        data=data,
        files={"apk": (filename, payload, "application/vnd.android.package-archive")},
        follow_redirects=False,
    )


def create_rule(client: TestClient, nscode: str, release_id: int):
    return client.post(
        "/admin/device-rules",
        data={"nscode": nscode, "releaseId": str(release_id), "note": "canary"},
        follow_redirects=False,
    )


def check_update(client: TestClient, nscode: str, current_version_code: int):
    return client.get(
        "/api/v1/updates/check",
        params={"nscode": nscode, "currentVersionCode": current_version_code},
    )


def set_default_release(client: TestClient, release_id: int):
    return client.post(
        "/admin/default-release",
        data={"releaseId": str(release_id)},
        follow_redirects=False,
    )


def test_admin_redirects_to_login_when_unauthenticated(isolated_env):
    client = make_client(isolated_env)

    response = client.get("/admin", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "/login"


def test_root_redirects_to_admin(isolated_env):
    client = make_client(isolated_env)

    response = client.get("/", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "/admin"


def test_login_allows_admin_access_and_page_contains_title(isolated_env):
    client = make_client(isolated_env)

    login(client)
    response = client.get("/admin")

    assert response.status_code == 200
    assert "APK 更新后台" in response.text


def test_unauthenticated_upload_redirects_before_creating_release(isolated_env):
    from app.config import load_settings
    from app.db import connect_db

    client = make_client(isolated_env)

    response = publish_release(client, 3, "2.0.6", payload=b"unauthorized-apk")

    assert response.status_code == 303
    assert response.headers["location"] == "/login"
    assert not list(Path(isolated_env).rglob("app.apk"))
    with connect_db(load_settings()) as conn:
        release_count = conn.execute("SELECT COUNT(*) FROM releases").fetchone()[0]
    assert release_count == 0


def test_logout_requires_post_and_clears_admin_session(isolated_env):
    client = make_client(isolated_env)
    login(client)
    assert client.get("/admin").status_code == 200

    get_response = client.get("/logout", follow_redirects=False)
    assert get_response.status_code == 405
    assert client.get("/admin").status_code == 200

    post_response = client.post("/logout", follow_redirects=False)
    assert post_response.status_code == 303
    assert post_response.headers["location"] == "/login"

    admin_response = client.get("/admin", follow_redirects=False)
    assert admin_response.status_code == 303
    assert admin_response.headers["location"] == "/login"


def test_publishing_with_make_default_returns_update_from_check_api(isolated_env):
    client = make_client(isolated_env)
    login(client)

    response = publish_release(client, 3, "2.0.6", make_default=True)
    assert response.status_code == 303
    assert response.headers["location"] == "/admin"

    update = check_update(client, "NSCODE-001", 2)

    assert update.status_code == 200
    body = update.json()
    assert body["updateAvailable"] is True
    assert body["versionCode"] == 3
    assert body["versionName"] == "2.0.6"
    assert body["apkUrl"] == "http://testserver/releases/1/app.apk"


def test_nscode_rule_overrides_default_over_api(isolated_env):
    client = make_client(isolated_env)
    login(client)
    first_publish = publish_release(client, 3, "2.0.6", make_default=True)
    second_publish = publish_release(client, 5, "2.1.0")
    rule_response = create_rule(client, "NSCODE-OVERRIDE", 2)
    assert first_publish.status_code == 303
    assert first_publish.headers["location"] == "/admin"
    assert second_publish.status_code == 303
    assert second_publish.headers["location"] == "/admin"
    assert rule_response.status_code == 303
    assert rule_response.headers["location"] == "/admin"

    update = check_update(client, "NSCODE-OVERRIDE", 2)

    assert update.status_code == 200
    body = update.json()
    assert body["updateAvailable"] is True
    assert body["versionCode"] == 5
    assert body["apkUrl"] == "http://testserver/releases/2/app.apk"


def test_default_release_endpoint_sets_default(isolated_env):
    client = make_client(isolated_env)
    login(client)
    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 303
    assert publish_release(client, 5, "2.1.0").status_code == 303

    response = set_default_release(client, 2)
    assert response.status_code == 303
    assert response.headers["location"] == "/admin"

    update = check_update(client, "NSCODE-001", 2)

    assert update.status_code == 200
    body = update.json()
    assert body["updateAvailable"] is True
    assert body["versionCode"] == 5
    assert body["apkUrl"] == "http://testserver/releases/2/app.apk"


def test_latest_update_json_returns_default_manifest(isolated_env):
    client = make_client(isolated_env)
    login(client)
    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 303

    response = client.get("/releases/latest/update.json")

    assert response.status_code == 200
    body = response.json()
    assert body["versionCode"] == 3
    assert body["apkUrl"] == "http://testserver/releases/1/app.apk"


def test_release_app_apk_downloads_uploaded_bytes(isolated_env):
    client = make_client(isolated_env)
    login(client)
    apk_payload = b"uploaded-apk-content"
    assert publish_release(client, 3, "2.0.6", payload=apk_payload, make_default=True).status_code == 303

    response = client.get("/releases/1/app.apk")

    assert response.status_code == 200
    assert response.content == apk_payload


def test_publish_rejects_non_apk_upload_with_admin_error(isolated_env):
    client = make_client(isolated_env)
    login(client)

    response = publish_release(client, 3, "2.0.6", filename="not-an-apk.txt")

    assert response.status_code == 400
    assert "filename must end with .apk" in response.text


def test_publish_rejects_empty_apk_upload_with_admin_error(isolated_env):
    client = make_client(isolated_env)
    login(client)

    response = publish_release(client, 3, "2.0.6", payload=b"")

    assert response.status_code == 400
    assert "uploaded APK is empty" in response.text


def test_default_release_rejects_invalid_release_id_with_admin_error(isolated_env):
    client = make_client(isolated_env)
    login(client)

    response = set_default_release(client, 999)

    assert response.status_code == 400
    assert "default release must exist and be active" in response.text


def test_device_rule_rejects_empty_nscode_with_admin_error(isolated_env):
    client = make_client(isolated_env)
    login(client)
    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 303

    response = create_rule(client, "", 1)

    assert response.status_code == 400
    assert "nscode is required" in response.text


def test_device_rule_rejects_invalid_release_id_with_admin_error(isolated_env):
    client = make_client(isolated_env)
    login(client)

    response = create_rule(client, "NSCODE-001", 999)

    assert response.status_code == 400
    assert "rule release must exist and be active" in response.text


def test_admin_update_release(isolated_env):
    client = make_client(isolated_env)
    login(client)

    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 303

    response = client.put(
        "/admin/releases/1",
        data={
            "versionName": "2.0.7",
            "releaseNotes": "updated notes",
            "mandatory": "false",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True


def test_admin_delete_release(isolated_env):
    client = make_client(isolated_env)
    login(client)

    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 303

    response = client.post("/admin/releases/1/delete")

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True

    state = client.get("/admin").context.get("state", {})


def test_admin_update_device_rule(isolated_env):
    client = make_client(isolated_env)
    login(client)

    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 303
    assert create_rule(client, "NSCODE-001", 1).status_code == 303

    response = client.put(
        "/admin/device-rules/1",
        data={
            "nscode": "NSCODE-002",
            "releaseId": "1",
            "note": "updated",
            "enabled": "0",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True


def test_admin_batch_device_rules(isolated_env):
    client = make_client(isolated_env)
    login(client)

    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 303
    assert create_rule(client, "NSCODE-001", 1).status_code == 303
    assert create_rule(client, "NSCODE-002", 1).status_code == 303

    response = client.post(
        "/admin/device-rules/batch",
        json={"ids": [1, 2], "action": "disable"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True
    assert body["processed"] == 2
    assert body["action"] == "disable"


def test_register_page_redirects_when_admin_exists(isolated_env):
    from app.config import load_settings
    from app.db import db_session, init_db
    from app.auth import hash_password

    settings = load_settings()
    init_db(settings)
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO users (email, password_hash, email_verified) VALUES (?, ?, 1)",
            ("admin@test.com", hash_password("Test1234")),
        )

    client = make_client(isolated_env)
    response = client.get("/register", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/login"
