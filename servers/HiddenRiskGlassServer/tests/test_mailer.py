from __future__ import annotations


def test_send_verification_email_logs_code_in_dev_mode(isolated_env, caplog):
    from app.config import load_settings
    from app.mailer import send_verification_email

    settings = load_settings()
    with caplog.at_level("INFO"):
        send_verification_email(settings, "test@example.com", "123456")
    assert "[开发模式] SMTP 未配置" in caplog.text
    assert "123456" in caplog.text
