package com.rokid.glass.camera

import android.graphics.Rect
import kotlin.math.min

/**
 * 共享相机视野策略。
 *
 * 已修复版本中 Surface 预览和 NV21 处理链统一使用同一个中心方形 ROI。
 */
object SharedCameraViewportPolicy {

    /** 返回正式 Surface 与 NV21 链路唯一允许使用的中心方形 ROI。 */
    fun calculateValidatedNv21SquareCropRect(width: Int, height: Int): Rect {
        if (width <= 0 || height <= 0) {
            return Rect(0, 0, 0, 0)
        }
        val side = min(width, height) and -2
        if (side <= 0) {
            return Rect(0, 0, width, height)
        }
        val left = ((width - side) / 2) and -2
        val top = ((height - side) / 2) and -2
        return Rect(left, top, left + side, top + side)
    }
}
