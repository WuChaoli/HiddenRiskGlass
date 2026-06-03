from unittest.mock import MagicMock, patch

from android_py.qrcode import run_get_qrcode


def test_get_qrcode_requires_account():
    """Account name is required."""
    result = run_get_qrcode(account="", code="21A")
    assert result == 1


def test_get_qrcode_login_failure():
    """Login failure returns error."""
    with patch("android_py.qrcode._http_post", return_value={"code": 500, "msg": "error"}):
        result = run_get_qrcode(account="test_user", code="21A")
        assert result == 1


def test_get_qrcode_success(mock_env_vars):
    """Full flow: login -> get qrcode data -> open browser."""
    login_resp = {"code": 200, "data": "mock_token_123"}
    qrcode_resp = {"code": 200, "data": "mock_qrcode_data_abc"}

    call_count = 0
    def mock_post(url, headers=None, data=None):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            assert url == "https://hyx.hzfj-tech.com/hzfj-emerstand/hzfj-emerstand-mobile/mobile/mobileAccount/login"
            assert data == {"code": "21A", "accountName": "test_user"}
            return login_resp
        else:
            assert url == "https://hyx.hzfj-tech.com/hzfj-emerstand/hzfj-emerstand-mobile/mobile/smartGlasses/getQrcode"
            assert headers == {"token": "mock_token_123"}
            assert data == {"companyId": "1930142827941588994", "taskId": "1942780848134230018"}
            return qrcode_resp

    with patch("android_py.qrcode._http_post", side_effect=mock_post):
        with patch("android_py.qrcode.webbrowser.open") as mock_browser:
            result = run_get_qrcode(account="test_user", code="21A")
            assert result == 0
            mock_browser.assert_called_once()
            called_url = mock_browser.call_args[0][0]
            assert "https://api.2dcode.biz/v1/create-qr-code" in called_url
            assert "mock_qrcode_data_abc" in called_url
