from __future__ import annotations

from fastapi import Request
from starlette.responses import RedirectResponse

from app.config import Settings
from app.db import db_session

SESSION_KEY = "admin_authenticated"
USER_ID_KEY = "admin_user_id"


def is_logged_in(request: Request) -> bool:
    return bool(request.session.get(SESSION_KEY))


def get_current_user_id(request: Request) -> int | None:
    return request.session.get(USER_ID_KEY)


def mark_logged_in(request: Request, user_id: int) -> None:
    request.session[SESSION_KEY] = True
    request.session[USER_ID_KEY] = user_id


def mark_logged_out(request: Request) -> None:
    request.session.pop(SESSION_KEY, None)
    request.session.pop(USER_ID_KEY, None)


def require_admin(request: Request) -> RedirectResponse | None:
    if is_logged_in(request):
        return None
    return RedirectResponse("/login", status_code=303)


def verify_user_password(settings: Settings, email: str, password: str) -> int | None:
    import bcrypt

    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT id, password_hash FROM users WHERE email = ?",
            (email.strip().lower(),),
        ).fetchone()

    if row is None:
        return None
    if not bcrypt.checkpw(password.encode("utf-8"), row["password_hash"].encode("utf-8")):
        return None
    return int(row["id"])


def has_any_admin(settings: Settings) -> bool:
    with db_session(settings) as conn:
        row = conn.execute("SELECT COUNT(*) as count FROM users").fetchone()
    return bool(row and row["count"] > 0)


def hash_password(password: str) -> str:
    import bcrypt

    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")
