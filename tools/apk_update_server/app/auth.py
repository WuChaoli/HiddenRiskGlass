from __future__ import annotations

import secrets

from fastapi import Request
from starlette.responses import RedirectResponse

from app.config import Settings


SESSION_KEY = "admin_authenticated"


def verify_password(settings: Settings, password: str) -> bool:
    return secrets.compare_digest(password, settings.admin_password)


def mark_logged_in(request: Request) -> None:
    request.session[SESSION_KEY] = True


def mark_logged_out(request: Request) -> None:
    request.session.pop(SESSION_KEY, None)


def is_logged_in(request: Request) -> bool:
    return bool(request.session.get(SESSION_KEY))


def require_admin(request: Request) -> RedirectResponse | None:
    if is_logged_in(request):
        return None
    return RedirectResponse("/login", status_code=303)
