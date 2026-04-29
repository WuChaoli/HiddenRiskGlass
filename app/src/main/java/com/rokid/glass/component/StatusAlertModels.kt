package com.rokid.glass.component

/**
 * 状态提醒组件的展示模型。
 */
data class StatusAlertModel(
    val status: AlertStatus,
    val titleText: String,
    val messageText: String,
    val action: AlertActionConfig = AlertActionConfig(),
    val behavior: AlertBehavior = AlertBehavior(),
    val style: AlertStyle,
)

enum class AlertStatus {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class AlertActionConfig(
    val visible: Boolean = false,
    val text: String = "",
)

data class AlertBehavior(
    val autoDismissMs: Long? = 2000L,
    val showCountdownBar: Boolean = true,
)

data class AlertStyle(
    val iconResId: Int,
    val iconWidthPx: Int? = null,
    val iconHeightPx: Int? = null,
    val cardWidthPx: Int? = null,
    val cardMinHeightPx: Int? = null,
    val cardBackgroundResId: Int? = null,
    val contentPaddingStartPx: Int? = null,
    val contentPaddingTopPx: Int? = null,
    val contentPaddingEndPx: Int? = null,
    val contentPaddingBottomPx: Int? = null,
    val countdownBarHeightPx: Int? = null,
    val countdownBarDrawableResId: Int? = null,
)
