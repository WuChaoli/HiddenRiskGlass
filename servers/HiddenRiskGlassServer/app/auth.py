from __future__ import annotations

import secrets
from fastapi import Request
from starlette.responses import RedirectResponse

from app.config import Settings

SESSION_KEY = "admin_authenticated"


def is_logged_in(request: Request) -> bool:
    return bool(request.session.get(SESSION_KEY))


def mark_logged_in(request: Request) -> None:
    request.session[SESSION_KEY] = True


def mark_logged_out(request: Request) -> None:
    request.session.pop(SESSION_KEY, None)


def require_admin(request: Request) -> RedirectResponse | None:
    if is_logged_in(request):
        return None
    return RedirectResponse("/login", status_code=303)


def verify_password(settings: Settings, password: str) -> bool:
    """验证管理员密码（基于环境变量）。"""
    admin_password = getattr(settings, "admin_password", None)
    if not admin_password:
        return False
    return secrets.compare_digest(password, admin_password)
