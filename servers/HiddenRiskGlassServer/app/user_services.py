from __future__ import annotations

import random
import re
from datetime import datetime, timedelta, timezone

from app.auth import hash_password
from app.config import Settings
from app.db import db_session
from app.mailer import send_verification_email


CODE_LENGTH = 6
CODE_EXPIRES_MINUTES = 15
CODE_SEND_COOLDOWN_SECONDS = 60


class VerificationError(ValueError):
    pass


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _generate_code() -> str:
    return "".join(str(random.randint(0, 9)) for _ in range(CODE_LENGTH))


def _validate_password_strength(password: str) -> None:
    if len(password) < 8:
        raise VerificationError("密码至少8位")
    if not re.search(r"[A-Za-z]", password):
        raise VerificationError("密码至少包含一个字母")
    if not re.search(r"\d", password):
        raise VerificationError("密码至少包含一个数字")


def _check_send_cooldown(conn, email: str, purpose: str) -> None:
    row = conn.execute(
        """
        SELECT created_at FROM verification_codes
        WHERE email = ? AND purpose = ?
        ORDER BY created_at DESC LIMIT 1
        """,
        (email.strip().lower(), purpose),
    ).fetchone()
    if row is not None:
        last_sent = datetime.fromisoformat(row["created_at"])
        if last_sent.tzinfo is None:
            last_sent = last_sent.replace(tzinfo=timezone.utc)
        elapsed = (_now() - last_sent).total_seconds()
        if elapsed < CODE_SEND_COOLDOWN_SECONDS:
            raise VerificationError("请稍后再试")


def send_verification_code(settings: Settings, email: str, purpose: str) -> None:
    email = email.strip().lower()
    if not email:
        raise VerificationError("邮箱不能为空")
    if "@" not in email:
        raise VerificationError("邮箱格式不正确")

    with db_session(settings) as conn:
        _check_send_cooldown(conn, email, purpose)
        code = _generate_code()
        expires_at = _now() + timedelta(minutes=CODE_EXPIRES_MINUTES)
        conn.execute(
            """
            INSERT INTO verification_codes (email, code, purpose, expires_at)
            VALUES (?, ?, ?, ?)
            """,
            (email, code, purpose, expires_at.isoformat()),
        )

    send_verification_email(settings, email, code)


def verify_code(settings: Settings, email: str, code: str, purpose: str) -> bool:
    email = email.strip().lower()
    with db_session(settings) as conn:
        row = conn.execute(
            """
            SELECT id, expires_at, used FROM verification_codes
            WHERE email = ? AND code = ? AND purpose = ?
            ORDER BY created_at DESC LIMIT 1
            """,
            (email, code, purpose),
        ).fetchone()

        if row is None:
            return False
        if row["used"]:
            return False
        expires_at = datetime.fromisoformat(row["expires_at"])
        if _now() > expires_at:
            return False

        conn.execute(
            "UPDATE verification_codes SET used = 1 WHERE id = ?",
            (row["id"],),
        )
        return True


def register_user(settings: Settings, email: str, password: str, code: str) -> int:
    email = email.strip().lower()
    _validate_password_strength(password)

    if not verify_code(settings, email, code, "register"):
        raise VerificationError("验证码错误或已过期")

    with db_session(settings) as conn:
        existing = conn.execute(
            "SELECT id FROM users WHERE email = ?", (email,)
        ).fetchone()
        if existing is not None:
            raise VerificationError("该邮箱已注册")

        cursor = conn.execute(
            """
            INSERT INTO users (email, password_hash, email_verified)
            VALUES (?, ?, 1)
            """,
            (email, hash_password(password)),
        )
        return int(cursor.lastrowid)


def reset_password(settings: Settings, email: str, code: str, new_password: str) -> None:
    email = email.strip().lower()
    _validate_password_strength(new_password)

    if not verify_code(settings, email, code, "reset_password"):
        raise VerificationError("验证码错误或已过期")

    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT id FROM users WHERE email = ?", (email,)
        ).fetchone()
        if row is None:
            raise VerificationError("该邮箱未注册")

        conn.execute(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            (hash_password(new_password), row["id"]),
        )


def change_password(settings: Settings, user_id: int, old_password: str, new_password: str) -> None:
    from app.auth import verify_user_password

    _validate_password_strength(new_password)

    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT email FROM users WHERE id = ?", (user_id,)
        ).fetchone()
        if row is None:
            raise VerificationError("用户不存在")

    verified_id = verify_user_password(settings, row["email"], old_password)
    if verified_id is None:
        raise VerificationError("旧密码不正确")

    with db_session(settings) as conn:
        conn.execute(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            (hash_password(new_password), user_id),
        )


def get_user_by_id(settings: Settings, user_id: int) -> dict | None:
    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT id, email, created_at FROM users WHERE id = ?", (user_id,)
        ).fetchone()
    if row is None:
        return None
    return dict(row)
