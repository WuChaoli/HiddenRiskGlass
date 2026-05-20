from __future__ import annotations

from fastapi.testclient import TestClient


def make_client(isolated_env):
    from app.config import load_settings
    from app.main import create_app

    settings = load_settings()
    return TestClient(create_app(settings))


def login(client: TestClient) -> None:
    response = client.post("/login", data={"password": "test-password"})
    assert response.status_code == 200


def publish_release(
    client: TestClient,
    version_code: int,
    version_name: str,
    payload: bytes = b"apk-bytes",
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
        files={"apk": ("app-standard-debug.apk", payload, "application/vnd.android.package-archive")},
    )


def create_rule(client: TestClient, nscode: str, release_id: int):
    return client.post(
        "/admin/device-rules",
        data={"nscode": nscode, "releaseId": str(release_id), "note": "canary"},
    )


def check_update(client: TestClient, nscode: str, current_version_code: int):
    return client.post(
        "/api/v1/updates/check",
        json={"nscode": nscode, "currentVersionCode": current_version_code},
    )


def test_admin_redirects_to_login_when_unauthenticated(isolated_env):
    client = make_client(isolated_env)

    response = client.get("/admin", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "/login"


def test_login_allows_admin_access_and_page_contains_title(isolated_env):
    client = make_client(isolated_env)

    login(client)
    response = client.get("/admin")

    assert response.status_code == 200
    assert "APK 更新后台" in response.text


def test_publishing_with_make_default_returns_update_from_check_api(isolated_env):
    client = make_client(isolated_env)
    login(client)

    response = publish_release(client, 3, "2.0.6", make_default=True)
    assert response.status_code == 200

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
    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 200
    assert publish_release(client, 5, "2.1.0").status_code == 200
    assert create_rule(client, "NSCODE-OVERRIDE", 2).status_code == 200

    update = check_update(client, "NSCODE-OVERRIDE", 2)

    assert update.status_code == 200
    body = update.json()
    assert body["updateAvailable"] is True
    assert body["versionCode"] == 5
    assert body["apkUrl"] == "http://testserver/releases/2/app.apk"


def test_latest_update_json_returns_default_manifest(isolated_env):
    client = make_client(isolated_env)
    login(client)
    assert publish_release(client, 3, "2.0.6", make_default=True).status_code == 200

    response = client.get("/releases/latest/update.json")

    assert response.status_code == 200
    body = response.json()
    assert body["versionCode"] == 3
    assert body["apkUrl"] == "http://testserver/releases/1/app.apk"


def test_release_app_apk_downloads_uploaded_bytes(isolated_env):
    client = make_client(isolated_env)
    login(client)
    apk_payload = b"uploaded-apk-content"
    assert publish_release(client, 3, "2.0.6", payload=apk_payload, make_default=True).status_code == 200

    response = client.get("/releases/1/app.apk")

    assert response.status_code == 200
    assert response.content == apk_payload
