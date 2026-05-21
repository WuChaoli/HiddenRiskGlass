from __future__ import annotations

import smtplib
from email.mime.text import MIMEText

from app.config import Settings


VERIFICATION_EMAIL_SUBJECT = "{server_name} - 您的验证码"
VERIFICATION_EMAIL_TEMPLATE = """\
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
</head>
<body style="font-family:sans-serif;max-width:400px;margin:0 auto;padding:20px">
  <h2 style="color:#0f766e">{server_name}</h2>
  <p>您的验证码是：</p>
  <p style="font-size:32px;font-weight:bold;letter-spacing:8px;color:#1f2937">{code}</p>
  <p>此验证码将在 15 分钟后失效。</p>
  <p style="color:#667085">如非本人操作，请忽略此邮件。</p>
</body>
</html>
"""


def _create_smtp_connection(settings: Settings) -> smtplib.SMTP | smtplib.SMTP_SSL:
    # 465/994 端口使用 SSL 直连，其他端口使用 STARTTLS
    if settings.smtp_port in (465, 994):
        smtp = smtplib.SMTP_SSL(settings.smtp_host, settings.smtp_port)
    else:
        smtp = smtplib.SMTP(settings.smtp_host, settings.smtp_port)
        if settings.smtp_tls:
            smtp.starttls()
    if settings.smtp_user:
        smtp.login(settings.smtp_user, settings.smtp_pass)
    return smtp


def send_verification_email(settings: Settings, email: str, code: str) -> None:
    if not settings.smtp_host:
        # 开发模式：SMTP 未配置时将验证码打印到控制台
        import logging
        logging.basicConfig(level=logging.INFO)
        logging.info("=" * 50)
        logging.info("[开发模式] SMTP 未配置，验证码直接输出：")
        logging.info(f"  邮箱: {email}")
        logging.info(f"  验证码: {code}")
        logging.info("=" * 50)
        return

    server_name = settings.server_name
    msg = MIMEText(VERIFICATION_EMAIL_TEMPLATE.format(code=code, server_name=server_name), "html", "utf-8")
    msg["Subject"] = f"{VERIFICATION_EMAIL_SUBJECT.format(server_name=server_name)}是 {code}"
    msg["From"] = settings.smtp_from
    msg["To"] = email

    with _create_smtp_connection(settings) as smtp:
        smtp.sendmail(settings.smtp_from, [email], msg.as_string())
