package com.rokid.glass.hiddenrisk.state

/**
 * TTS 播放状态机 — 替代 3 个独立 boolean 标志。
 * 状态单向推进：IDLE → PLAYING_ALERT → ALERT_PLAYED → PLAYING_ADVICE → DONE
 */
enum class TtsState {
    /** 空闲，可以播放下一条 */
    IDLE,
    /** 正在播放隐患告警 */
    PLAYING_ALERT,
    /** 隐患告警已播放，等待/播放建议 */
    ALERT_PLAYED,
    /** 正在播放隐患建议 */
    PLAYING_ADVICE,
    /** 建议已播放，全部完成 */
    DONE
}
