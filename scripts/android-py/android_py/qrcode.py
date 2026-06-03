"""QR code retrieval from HZFJ platform."""

import json
import logging
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

logger = logging.getLogger(__name__)

LOGIN_URL = "https://hyx.hzfj-tech.com/hzfj-emerstand/hzfj-emerstand-mobile/mobile/mobileAccount/login"
QRCODE_DATA_URL = "https://hyx.hzfj-tech.com/hzfj-emerstand/hzfj-emerstand-mobile/mobile/smartGlasses/getQrcode"
QRCODE_IMAGE_API = "https://api.2dcode.biz/v1/create-qr-code"

COMPANY_ID = "1930142827941588994"
TASK_ID = "1942780848134230018"
QRCODE_SIZE = "512x512"


def _http_post(url: str, headers: dict[str, str] | None = None, data: dict | None = None) -> dict:
    """Send POST request and return parsed JSON response."""
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)

    body = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=body, headers=req_headers, method="POST")

    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _extract_data_field(resp: dict, field: str = "data") -> str:
    """Extract string field from JSON response."""
    value = resp.get(field, "")
    if isinstance(value, str):
        return value
    return str(value) if value is not None else ""


def run_get_qrcode(account: str = "", code: str = "21A") -> int:
    """Retrieve QR code from HZFJ platform and open in browser.

    Args:
        account: Account name for login. Required.
        code: Organization code (default: 21A).
    """
    if not account:
        logger.error("Account name is required. Use --account or set HZFJ_ACCOUNT_NAME env var.")
        return 1

    # Step 1: Login to get token
    logger.info("Step 1: Login to get token...")
    try:
        login_resp = _http_post(LOGIN_URL, data={"code": code, "accountName": account})
    except Exception as e:
        logger.error(f"Login request failed: {e}")
        return 1

    token = _extract_data_field(login_resp)
    if not token:
        logger.error(f"Login failed, cannot extract token. Response: {login_resp}")
        return 1

    logger.info("Login successful, token obtained")

    # Step 2: Get QR code data
    logger.info("Step 2: Get QR code data...")
    try:
        qrcode_resp = _http_post(
            QRCODE_DATA_URL,
            headers={"token": token},
            data={"companyId": COMPANY_ID, "taskId": TASK_ID},
        )
    except Exception as e:
        logger.error(f"Get QR code data request failed: {e}")
        return 1

    qrcode_data = _extract_data_field(qrcode_resp)
    if not qrcode_data:
        logger.error(f"Get QR code data failed. Response: {qrcode_resp}")
        return 1

    logger.info("QR code data obtained")

    # Step 3: Generate QR code image URL and open in browser
    logger.info("Step 3: Generate QR code image...")
    encoded_data = urllib.parse.quote(qrcode_data)
    qrcode_url = f"{QRCODE_IMAGE_API}?data={encoded_data}&size={QRCODE_SIZE}"

    logger.info(f"QR code image URL: {qrcode_url}")
    logger.info("Opening in browser...")

    try:
        webbrowser.open(qrcode_url)
        logger.info("Browser opened")
    except Exception as e:
        logger.warning(f"Failed to open browser: {e}")
        logger.info("Please manually open the following link in your browser:")
        logger.info(qrcode_url)

    return 0
