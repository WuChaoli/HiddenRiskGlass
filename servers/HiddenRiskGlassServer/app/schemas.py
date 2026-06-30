from __future__ import annotations


RESULT_UPDATE = "update"
RESULT_NO_UPDATE = "no_update"
RESULT_NO_RELEASE = "no_release"

STATUS_ACTIVE = "active"
STATUS_DISABLED = "disabled"


def no_update_response() -> dict[str, bool]:
    return {"updateAvailable": False}
