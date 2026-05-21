from __future__ import annotations

import pytest


def test_send_verification_email_raises_without_smtp_host(isolated_env):
    from app.config import load_settings
    from app.mailer import send_verification_email

    settings = load_settings()
    with pytest.raises(RuntimeError, match="SMTP_HOST is not configured"):
        send_verification_email(settings, "test@example.com", "123456")
