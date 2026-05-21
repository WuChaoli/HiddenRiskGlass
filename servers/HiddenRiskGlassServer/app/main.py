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

from app.auth import get_current_user_id, has_any_admin, is_logged_in, mark_logged_in, mark_logged_out, require_admin, verify_user_password
from app.user_services import (
    change_password,
    get_user_by_id,
    register_user,
    reset_password,
    send_verification_code,
)
from app.config import Settings, load_settings
from app.db import init_db
from app.services import (
    batch_device_rules,
    create_device_rule,
    delete_device_rule,
    delete_release,
    get_latest_manifest,
    get_release_by_id,
    list_admin_state,
    publish_release,
    resolve_update,
    set_default_release,
    update_device_rule,
    update_release,
)


def build_base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or load_settings()
    init_db(resolved_settings)

    app = FastAPI(title=resolved_settings.server_name)
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
                "base_url": build_base_url(request),
            },
            status_code=status_code,
        )

    @app.get("/")
    async def root():
        if not has_any_admin(resolved_settings):
            return RedirectResponse("/register", status_code=303)
        return RedirectResponse("/admin", status_code=303)

    @app.get("/login")
    async def login_page(request: Request):
        return templates.TemplateResponse(request, "login.html", {"error": ""})

    @app.post("/login")
    async def login_submit(request: Request, email: str = Form(...), password: str = Form(...)):
        user_id = verify_user_password(resolved_settings, email, password)
        if user_id is None:
            return templates.TemplateResponse(
                request,
                "login.html",
                {"error": "邮箱或密码错误"},
                status_code=401,
            )
        mark_logged_in(request, user_id)
        return RedirectResponse("/admin", status_code=303)

    @app.post("/logout")
    async def logout(request: Request):
        mark_logged_out(request)
        return RedirectResponse("/login", status_code=303)

    @app.get("/forgot-password")
    async def forgot_password_page(request: Request):
        return templates.TemplateResponse(request, "forgot_password.html", {"error": ""})

    @app.post("/forgot-password")
    async def forgot_password_submit(
        request: Request,
        email: str = Form(...),
        code: str = Form(...),
        new_password: str = Form(...),
    ):
        try:
            reset_password(resolved_settings, email, code, new_password)
        except ValueError as exc:
            return templates.TemplateResponse(
                request,
                "forgot_password.html",
                {"error": str(exc)},
                status_code=400,
            )
        return RedirectResponse("/login", status_code=303)

    @app.get("/profile")
    async def profile_page(request: Request):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        user_id = get_current_user_id(request)
        user = get_user_by_id(resolved_settings, user_id) if user_id else None
        email = user["email"] if user else ""
        return templates.TemplateResponse(
            request, "profile.html", {"user_email": email, "error": ""}
        )

    @app.post("/profile/password")
    async def profile_change_password(
        request: Request,
        old_password: str = Form(..., alias="oldPassword"),
        new_password: str = Form(..., alias="newPassword"),
        confirm_password: str = Form(..., alias="confirmPassword"),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        if new_password != confirm_password:
            return templates.TemplateResponse(
                request,
                "profile.html",
                {"user_email": "", "error": "两次输入的密码不一致"},
                status_code=400,
            )
        user_id = get_current_user_id(request)
        if user_id is None:
            return RedirectResponse("/login", status_code=303)
        try:
            change_password(resolved_settings, user_id, old_password, new_password)
        except ValueError as exc:
            user = get_user_by_id(resolved_settings, user_id)
            return templates.TemplateResponse(
                request,
                "profile.html",
                {"user_email": user["email"] if user else "", "error": str(exc)},
                status_code=400,
            )
        return RedirectResponse("/admin", status_code=303)

    @app.get("/register")
    async def register_page(request: Request):
        if has_any_admin(resolved_settings):
            return RedirectResponse("/login", status_code=303)
        return templates.TemplateResponse(request, "register.html", {"error": ""})

    @app.post("/register")
    async def register_submit(
        request: Request,
        email: str = Form(...),
        password: str = Form(...),
        code: str = Form(...),
    ):
        if has_any_admin(resolved_settings):
            return RedirectResponse("/login", status_code=303)
        try:
            user_id = register_user(resolved_settings, email, password, code)
            mark_logged_in(request, user_id)
            return RedirectResponse("/admin", status_code=303)
        except ValueError as exc:
            return templates.TemplateResponse(
                request, "register.html", {"error": str(exc)}, status_code=400
            )

    @app.post("/verify-code")
    async def api_send_code(request: Request):
        try:
            body = await request.json()
            email = body.get("email", "")
            purpose = body.get("purpose", "")
            send_verification_code(resolved_settings, email, purpose)
            return JSONResponse({"sent": True})
        except ValueError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
        except RuntimeError as exc:
            return JSONResponse({"error": str(exc)}, status_code=500)

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

    @app.put("/admin/releases/{release_id}")
    async def admin_update_release(
        request: Request,
        release_id: int,
        version_name: str = Form(..., alias="versionName"),
        release_notes: str = Form("", alias="releaseNotes"),
        mandatory: str = Form(""),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        try:
            update_release(
                settings=resolved_settings,
                release_id=release_id,
                version_name=version_name,
                release_notes=release_notes,
                mandatory=mandatory.lower() in {"true", "1", "on", "yes"},
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

    @app.put("/admin/device-rules/{rule_id}")
    async def admin_update_device_rule(
        request: Request,
        rule_id: int,
        nscode: str = Form(...),
        release_id: int = Form(..., alias="releaseId"),
        note: str = Form(""),
        enabled: str = Form(""),
    ):
        redirect = require_admin(request)
        if redirect is not None:
            return redirect
        enabled_bool = enabled.strip().lower() not in {"0", "false", ""}
        try:
            update_device_rule(
                settings=resolved_settings,
                rule_id=rule_id,
                nscode=nscode,
                release_id=release_id,
                note=note,
                enabled=enabled_bool,
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
        except Exception:
            return JSONResponse({"error": "invalid JSON body"}, status_code=400)

        rule_ids = body.get("ids", [])
        action = body.get("action", "")
        release_id = body.get("release_id")

        try:
            result = batch_device_rules(
                settings=resolved_settings,
                rule_ids=rule_ids,
                action=action,
                release_id=release_id,
            )
        except ValueError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
        return JSONResponse({"ok": True, **result})

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
    if method not in {"POST", "PUT"}:
        return False
    if path in {"/admin/releases", "/admin/default-release", "/admin/device-rules", "/admin/device-rules/batch"}:
        return True
    if path.startswith("/admin/releases/"):
        return True
    if path.startswith("/admin/device-rules/"):
        return True
    return False


app = create_app()
