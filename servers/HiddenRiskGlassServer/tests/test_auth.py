from __future__ import annotations


def test_database_initializes_with_users_table(isolated_env):
    from app.config import load_settings
    from app.db import init_db, connect_db

    settings = load_settings()
    init_db(settings)
    with connect_db(settings) as conn:
        tables = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users', 'verification_codes')"
        ).fetchall()
        table_names = {row["name"] for row in tables}
        assert "users" in table_names
        assert "verification_codes" in table_names


import pytest


def test_register_and_login_user(isolated_env):
    from app.config import load_settings
    from app.user_services import register_user
    from app.auth import verify_user_password

    settings = load_settings()
    # 先初始化数据库并插入验证码
    from app.db import db_session, init_db
    from datetime import datetime, timedelta, timezone
    init_db(settings)
    now = datetime.now(timezone.utc)
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO verification_codes (email, code, purpose, expires_at) VALUES (?, ?, ?, ?)",
            ("admin@test.com", "123456", "register", (now + timedelta(minutes=15)).isoformat()),
        )

    user_id = register_user(settings, "admin@test.com", "Test1234", "123456")
    assert user_id == 1

    verified_id = verify_user_password(settings, "admin@test.com", "Test1234")
    assert verified_id == 1

    wrong = verify_user_password(settings, "admin@test.com", "wrong")
    assert wrong is None


def test_register_duplicate_email_fails(isolated_env):
    from app.config import load_settings
    from app.user_services import register_user, VerificationError
    from app.db import db_session, init_db
    from app.auth import hash_password
    from datetime import datetime, timedelta, timezone

    settings = load_settings()
    init_db(settings)
    now = datetime.now(timezone.utc)
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO users (email, password_hash, email_verified) VALUES (?, ?, 1)",
            ("admin@test.com", hash_password("Test1234")),
        )
        conn.execute(
            "INSERT INTO verification_codes (email, code, purpose, expires_at) VALUES (?, ?, ?, ?)",
            ("admin@test.com", "123456", "register", (now + timedelta(minutes=15)).isoformat()),
        )

    with pytest.raises(VerificationError, match="该邮箱已注册"):
        register_user(settings, "admin@test.com", "Test1234", "123456")


def test_password_strength_validation(isolated_env):
    from app.user_services import _validate_password_strength, VerificationError

    with pytest.raises(VerificationError):
        _validate_password_strength("short", 8)
    with pytest.raises(VerificationError):
        _validate_password_strength("12345678", 8)
    with pytest.raises(VerificationError):
        _validate_password_strength("abcdefgh", 8)

    # 不抛出异常即通过
    _validate_password_strength("Test1234", 8)


def test_change_password(isolated_env):
    from app.config import load_settings
    from app.user_services import change_password, register_user
    from app.auth import verify_user_password
    from app.db import db_session, init_db
    from datetime import datetime, timedelta, timezone

    settings = load_settings()
    init_db(settings)
    now = datetime.now(timezone.utc)
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO verification_codes (email, code, purpose, expires_at) VALUES (?, ?, ?, ?)",
            ("admin@test.com", "123456", "register", (now + timedelta(minutes=15)).isoformat()),
        )

    user_id = register_user(settings, "admin@test.com", "OldPass1", "123456")

    # 修改密码
    change_password(settings, user_id, "OldPass1", "NewPass2")

    # 旧密码失效
    assert verify_user_password(settings, "admin@test.com", "OldPass1") is None
    # 新密码有效
    assert verify_user_password(settings, "admin@test.com", "NewPass2") == user_id
