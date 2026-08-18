package com.rokid.glass.hiddenrisk

enum class AlignmentControl {
    OFFSET_X,
    OFFSET_Y,
    SCALE,
}

enum class DominantEye {
    LEFT,
    RIGHT,
}

fun parseDominantEye(value: String?): DominantEye {
    return DominantEye.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DominantEye.RIGHT
}

object AlignmentCalibrationPreset {
    fun forEye(eye: DominantEye): AlignmentCalibrationState {
        return AlignmentCalibrationState(
            scale = CALIBRATED_SCALE,
            offsetX = if (eye == DominantEye.LEFT) OFFSET_X_MAGNITUDE else -OFFSET_X_MAGNITUDE,
            offsetY = CALIBRATED_OFFSET_Y,
        )
    }

    private const val CALIBRATED_SCALE = 0.79049903f
    private const val OFFSET_X_MAGNITUDE = 72f
    private const val CALIBRATED_OFFSET_Y = -234f
}

enum class AdjustmentDirection(val sign: Float) {
    DECREASE(-1f),
    INCREASE(1f),
}

data class NormalizedCameraCrop(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

fun selectAlignmentTextureSize(
    requestedWidth: Int,
    requestedHeight: Int,
    reportedWidth: Int,
    reportedHeight: Int,
): Pair<Int, Int> {
    val callbackIsInternalAxisSwap = reportedWidth == requestedHeight && reportedHeight == requestedWidth
    return when {
        callbackIsInternalAxisSwap -> requestedWidth to requestedHeight
        reportedWidth > 0 && reportedHeight > 0 -> reportedWidth to reportedHeight
        else -> requestedWidth to requestedHeight
    }
}

data class AlignmentCalibrationState(
    val scale: Float = DEFAULT_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val alpha: Float = DEFAULT_ALPHA,
    val translationStep: Float = DEFAULT_TRANSLATION_STEP,
    val scaleStep: Float = DEFAULT_SCALE_STEP,
    val control: AlignmentControl = AlignmentControl.OFFSET_X,
) {
    fun selectNextControl(): AlignmentCalibrationState = copy(
        control = when (control) {
            AlignmentControl.OFFSET_X -> AlignmentControl.OFFSET_Y
            AlignmentControl.OFFSET_Y -> AlignmentControl.SCALE
            AlignmentControl.SCALE -> AlignmentControl.OFFSET_X
        },
    )

    fun adjust(direction: AdjustmentDirection): AlignmentCalibrationState {
        return when (control) {
            AlignmentControl.OFFSET_X -> copy(offsetX = offsetX + translationStep * direction.sign)
            AlignmentControl.OFFSET_Y -> copy(offsetY = offsetY + translationStep * direction.sign)
            AlignmentControl.SCALE -> copy(scale = (scale + scaleStep * direction.sign).coerceAtLeast(MIN_SCALE))
        }
    }

    fun normalizedCameraCrop(): NormalizedCameraCrop {
        val safeScale = scale.coerceAtLeast(MIN_SCALE)
        val cropWidth = (DISPLAY_WIDTH / (CAMERA_WIDTH * safeScale)).coerceIn(0f, 1f)
        val cropHeight = (DISPLAY_HEIGHT / (CAMERA_HEIGHT * safeScale)).coerceIn(0f, 1f)
        val centerX = 0.5f - offsetX / (CAMERA_WIDTH * safeScale)
        val centerY = 0.5f - offsetY / (CAMERA_HEIGHT * safeScale)
        return NormalizedCameraCrop(
            left = (centerX - cropWidth / 2f).coerceIn(0f, 1f - cropWidth),
            top = (centerY - cropHeight / 2f).coerceIn(0f, 1f - cropHeight),
            width = cropWidth,
            height = cropHeight,
        )
    }

    fun normalizedSurfaceCrop(
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): NormalizedCameraCrop {
        val cameraCrop = normalizedCameraCrop()
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return cameraCrop

        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val displayAspect = DISPLAY_WIDTH / DISPLAY_HEIGHT
        val baseWidth: Float
        val baseHeight: Float
        if (surfaceAspect > displayAspect) {
            baseWidth = displayAspect / surfaceAspect
            baseHeight = 1f
        } else {
            baseWidth = 1f
            baseHeight = surfaceAspect / displayAspect
        }

        val cropWidth = cameraCrop.width * baseWidth
        val cropHeight = cameraCrop.height * baseHeight
        val cameraCenterX = cameraCrop.left + cameraCrop.width / 2f
        val cameraCenterY = cameraCrop.top + cameraCrop.height / 2f
        val surfaceCenterX = 0.5f + (cameraCenterX - 0.5f) * baseWidth
        val surfaceCenterY = 0.5f + (cameraCenterY - 0.5f) * baseHeight
        return NormalizedCameraCrop(
            left = (surfaceCenterX - cropWidth / 2f).coerceIn(0f, 1f - cropWidth),
            top = (surfaceCenterY - cropHeight / 2f).coerceIn(0f, 1f - cropHeight),
            width = cropWidth,
            height = cropHeight,
        )
    }

    companion object {
        const val CAMERA_WIDTH = 3024f
        const val CAMERA_HEIGHT = 4032f
        const val DISPLAY_WIDTH = 480f
        const val DISPLAY_HEIGHT = 640f
        const val DEFAULT_SCALE = 0.830499f
        const val DEFAULT_ALPHA = 0f
        const val DEFAULT_TRANSLATION_STEP = 2f
        const val DEFAULT_SCALE_STEP = 0.005f
        const val MIN_SCALE = 0.05f
    }
}
