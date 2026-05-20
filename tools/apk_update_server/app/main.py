from __future__ import annotations

import base64
import hashlib
import hmac
import json
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Receive, Scope, Send

try:
    from starlette.middleware.sessions import SessionMiddleware
except ModuleNotFoundError:
    class SessionMiddleware:
        def __init__(
            self,
            app: ASGIApp,
            secret_key: str,
            session_cookie: str = "session",
            https_only: bool = False,
            same_site: str = "lax",
        ) -> None:
            self.app = app
            self.secret_key = secret_key.encode("utf-8")
            self.session_cookie = session_cookie
            self.security_flags = "httponly; samesite=" + same_site
            if https_only:
                self.security_flags += "; secure"

        async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
            if scope["type"] not in {"http", "websocket"}:
                await self.app(scope, receive, send)
                return

            initial_session = self._load_session(scope)
            scope["session"] = dict(initial_session)

            async def send_wrapper(message):
                if message["type"] == "http.response.start":
                    headers = MutableHeaders(scope=message)
                    session = scope.get("session", {})
                    if session:
                        data = self._dump_session(session)
                        headers.append(
                            "Set-Cookie",
                            f"{self.session_cookie}={data}; path=/; {self.security_flags}",
                        )
                    elif initial_session:
                        headers.append(
                            "Set-Cookie",
                            f"{self.session_cookie}=null; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; {self.security_flags}",
                        )
                await send(message)

            await self.app(scope, receive, send_wrapper)

        def _load_session(self, scope: Scope) -> dict[str, object]:
            cookie_header = ""
            for key, value in scope.get("headers", []):
                if key == b"cookie":
                    cookie_header = value.decode("latin-1")
                    break
            cookies = {}
            for part in cookie_header.split(";"):
                if "=" in part:
                    key, value = part.strip().split("=", 1)
                    cookies[key] = value
            raw_session = cookies.get(self.session_cookie)
            if not raw_session:
                return {}
            try:
                payload, signature = raw_session.rsplit(".", 1)
            except ValueError:
                return {}
            expected = hmac.new(self.secret_key, payload.encode("ascii"), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(signature, expected):
                return {}
            try:
                decoded = base64.urlsafe_b64decode(payload.encode("ascii"))
                return json.loads(decoded.decode("utf-8"))
            except (ValueError, json.JSONDecodeError):
                return {}

        def _dump_session(self, session: dict[str, object]) -> str:
            raw = json.dumps(session, separators=(",", ":")).encode("utf-8")
            payload = base64.urlsafe_b64encode(raw).decode("ascii")
            signature = hmac.new(self.secret_key, payload.encode("ascii"), hashlib.sha256).hexdigest()
            return f"{payload}.{signature}"

from app.auth import is_logged_in, mark_logged_in, mark_logged_out, require_admin, verify_password
from app.config import Settings, load_settings
from app.db import init_db
from app.services import (
    create_device_rule,
    delete_device_rule,
    get_latest_manifest,
    get_release_by_id,
    list_admin_state,
    publish_release,
    resolve_update,
    set_default_release,
)


def build_base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or load_settings()
    init_db(resolved_settings)

    app = FastAPI(title="APK Update Server")
    app.state.settings = resolved_settings

    @app.middleware("http")
    async def require_admin_before_body_parse(request: Request, call_next):
        if _is_protected_admin_request(request) and not is_logged_in(request):
            return RedirectResponse("/login", status_code=303)
        return await call_next(request)

    app.add_middleware(
        SessionMiddleware,
        secret_key=resolved_settings.session_secret,
        https_only=resolved_settings.session_cookie_secure,
        same_site="lax",
    )

    templates = Jinja2Templates(directory=str(resolved_settings.app_root / "app" / "templates"))
    app.mount(
        "/static",
        StaticFiles(directory=str(resolved_settings.app_root / "app" / "static")),
        name="static",
    )

    def render_admin(request: Request, status_code: int = 200, message: str = ""):
        state = list_admin_state(resolved_settings)
        return templates.TemplateResponse(
            request,
            "admin.html",
            {
                "releases": state["releases"],
                "device_rules": state["device_rules"],
                "check_events": state["check_events"],
                "default_release_id": state["default_release_id"],
                "message": message,
            },
            status_code=status_code,
        )

    @app.get("/")
    async def root():
        return RedirectResponse("/admin", status_code=303)

    @app.get("/login")
    async def login_page(request: Request):
        return templates.TemplateResponse(request, "login.html", {"error": ""})

    @app.post("/login")
    async def login_submit(request: Request, password: str = Form(...)):
        if not verify_password(resolved_settings, password):
            return templates.TemplateResponse(
                request,
                "login.html",
                {"error": "密码错误"},
                status_code=401,
            )
        mark_logged_in(request)
        return RedirectResponse("/admin", status_code=303)

    @app.post("/logout")
    async def logout(request: Request):
        mark_logged_out(request)
        return RedirectResponse("/login", status_code=303)

    @app.get("/admin")
    async def admin_page(request: Request):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        return render_admin(request)

    @app.post("/admin/releases")
    async def admin_publish_release(
        request: Request,
        apk: UploadFile = File(...),
        version_code: int = Form(..., alias="versionCode"),
        version_name: str = Form(..., alias="versionName"),
        release_notes: str = Form("", alias="releaseNotes"),
        mandatory: str | None = Form(None),
        make_default: str | None = Form(None, alias="makeDefault"),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            manifest = publish_release(
                settings=resolved_settings,
                filename=apk.filename or "",
                fileobj=apk.file,
                version_code=version_code,
                version_name=version_name,
                release_notes=release_notes,
                mandatory=mandatory is not None,
                base_url=build_base_url(request),
            )
            if make_default is not None:
                release_id = _release_id_from_manifest(manifest)
                set_default_release(resolved_settings, release_id)
        except ValueError as exc:
            return render_admin(request, status_code=400, message=str(exc))
        return RedirectResponse("/admin", status_code=303)

    @app.post("/admin/default-release")
    async def admin_set_default_release(
        request: Request,
        release_id: int = Form(..., alias="releaseId"),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            set_default_release(resolved_settings, release_id)
        except ValueError as exc:
            return render_admin(request, status_code=400, message=str(exc))
        return RedirectResponse("/admin", status_code=303)

    @app.post("/admin/device-rules")
    async def admin_create_device_rule(
        request: Request,
        nscode: str = Form(...),
        release_id: int = Form(..., alias="releaseId"),
        note: str = Form(""),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            create_device_rule(resolved_settings, nscode, release_id, note)
        except ValueError as exc:
            return render_admin(request, status_code=400, message=str(exc))
        return RedirectResponse("/admin", status_code=303)

    @app.post("/admin/device-rules/{rule_id}/delete")
    async def admin_delete_device_rule(request: Request, rule_id: int):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        delete_device_rule(resolved_settings, rule_id)
        return RedirectResponse("/admin", status_code=303)

    @app.get("/api/v1/updates/check")
    async def check_update(
        request: Request,
        nscode: str = "",
        current_version_code: int = Query(..., alias="currentVersionCode"),
    ):
        try:
            response = resolve_update(
                resolved_settings,
                nscode=nscode,
                current_version_code=current_version_code,
                base_url=build_base_url(request),
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return JSONResponse(response)

    @app.get("/releases/{release_id}/app.apk")
    async def download_release_apk(release_id: int):
        release = get_release_by_id(resolved_settings, release_id)
        if release is None:
            raise HTTPException(status_code=404, detail="release not found")
        apk_path = Path(release["apk_path"])
        if not apk_path.is_file():
            raise HTTPException(status_code=404, detail="apk not found")
        return FileResponse(apk_path, media_type="application/vnd.android.package-archive", filename="app.apk")

    @app.get("/releases/latest/update.json")
    async def latest_manifest(request: Request):
        manifest = get_latest_manifest(resolved_settings, base_url=build_base_url(request))
        if manifest is None:
            raise HTTPException(status_code=404, detail="default release not found")
        return JSONResponse(manifest)

    @app.get("/releases/latest/app.apk")
    async def latest_apk():
        state = list_admin_state(resolved_settings)
        default_release_id = state["default_release_id"]
        if default_release_id is None:
            raise HTTPException(status_code=404, detail="default release not found")
        release = get_release_by_id(resolved_settings, default_release_id)
        if release is None:
            raise HTTPException(status_code=404, detail="default release not found")
        apk_path = Path(release["apk_path"])
        if not apk_path.is_file():
            raise HTTPException(status_code=404, detail="apk not found")
        return FileResponse(apk_path, media_type="application/vnd.android.package-archive", filename="app.apk")

    return app


def _release_id_from_manifest(manifest: dict[str, object]) -> int:
    apk_url = str(manifest["apkUrl"]).rstrip("/")
    return int(apk_url.split("/")[-2])


def _is_protected_admin_request(request: Request) -> bool:
    path = request.url.path
    method = request.method.upper()
    if method == "GET":
        return path == "/admin"
    if method != "POST":
        return False
    if path in {"/admin/releases", "/admin/default-release", "/admin/device-rules"}:
        return True
    return path.startswith("/admin/device-rules/") and path.endswith("/delete")


app = create_app()
