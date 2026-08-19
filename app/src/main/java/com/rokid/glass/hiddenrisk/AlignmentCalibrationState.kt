package com.rokid.glass.hiddenrisk

import java.util.Locale

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

    const val CALIBRATED_SCALE = 0.79049903f
    private const val OFFSET_X_MAGNITUDE = 72f
    private const val CALIBRATED_OFFSET_Y = -234f
}

data class DetectionOverlayAlignmentState(
    val distanceMeters: Float = DEFAULT_DISTANCE_METERS,
) {
    val offsetX: Float get() = InverseDistanceAlignmentState.DEFAULT_B -
        InverseDistanceAlignmentState.DEFAULT_K / distanceMeters

    fun adjustDistance(direction: AdjustmentDirection): DetectionOverlayAlignmentState = copy(
        distanceMeters = (distanceMeters + DISTANCE_STEP_METERS * direction.sign)
            .coerceAtLeast(MIN_DISTANCE_METERS),
    )

    fun calibrationState(): AlignmentCalibrationState = AlignmentCalibrationState(
        scale = AlignmentCalibrationPreset.CALIBRATED_SCALE,
        offsetX = offsetX,
        offsetY = -234f,
        alpha = 0f,
    )

    companion object {
        const val DEFAULT_DISTANCE_METERS = 1f
        const val MIN_DISTANCE_METERS = 0.5f
        const val DISTANCE_STEP_METERS = 0.5f
    }
}

enum class DistanceAlignmentControl {
    OFFSET_X,
    OFFSET_Y,
}

enum class DistanceAlignmentInputAction {
    SELECT_CONTROL,
    DECREASE,
    INCREASE,
    NEXT_DISTANCE,
    NONE,
}

fun distanceAlignmentActionForKey(keyEvent: Int): DistanceAlignmentInputAction {
    return when (keyEvent) {
        GlassKeyEvent.KEYCODE_CLICK -> DistanceAlignmentInputAction.SELECT_CONTROL
        GlassKeyEvent.KEYCODE_FRONT -> DistanceAlignmentInputAction.DECREASE
        GlassKeyEvent.KEYCODE_BEHIND -> DistanceAlignmentInputAction.INCREASE
        GlassKeyEvent.KEYCODE_DOUBLE_CLICK,
        GlassKeyEvent.KEYCODE_BACK,
        -> DistanceAlignmentInputAction.NEXT_DISTANCE
        else -> DistanceAlignmentInputAction.NONE
    }
}

data class DistanceAlignmentOffset(
    val offsetX: Float,
    val offsetY: Float,
)

data class DistanceAlignmentState(
    val selectedDistanceIndex: Int = 0,
    val control: DistanceAlignmentControl = DistanceAlignmentControl.OFFSET_X,
    val offsets: List<DistanceAlignmentOffset> = DEFAULT_DISTANCES.map {
        DistanceAlignmentOffset(offsetX = -72f, offsetY = -234f)
    },
    val translationStep: Float = AlignmentCalibrationState.DEFAULT_TRANSLATION_STEP,
) {
    val distanceMeters: Float get() = DEFAULT_DISTANCES[selectedDistanceIndex]
    val offsetX: Float get() = offsets[selectedDistanceIndex].offsetX
    val offsetY: Float get() = offsets[selectedDistanceIndex].offsetY
    val scale: Float get() = AlignmentCalibrationPreset.CALIBRATED_SCALE
    val alpha: Float get() = 0.5f

    fun calibrationState(): AlignmentCalibrationState = AlignmentCalibrationState(
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
        alpha = alpha,
        translationStep = translationStep,
        control = when (control) {
            DistanceAlignmentControl.OFFSET_X -> AlignmentControl.OFFSET_X
            DistanceAlignmentControl.OFFSET_Y -> AlignmentControl.OFFSET_Y
        },
    )

    fun selectNextDistance(): DistanceAlignmentState = copy(
        selectedDistanceIndex = (selectedDistanceIndex + 1) % DEFAULT_DISTANCES.size,
    )

    fun selectDistance(index: Int): DistanceAlignmentState = copy(
        selectedDistanceIndex = index.coerceIn(DEFAULT_DISTANCES.indices),
    )

    fun selectNextControl(): DistanceAlignmentState = copy(
        control = when (control) {
            DistanceAlignmentControl.OFFSET_X -> DistanceAlignmentControl.OFFSET_Y
            DistanceAlignmentControl.OFFSET_Y -> DistanceAlignmentControl.OFFSET_X
        },
    )

    fun adjust(direction: AdjustmentDirection): DistanceAlignmentState {
        val current = offsets[selectedDistanceIndex]
        val adjusted = when (control) {
            DistanceAlignmentControl.OFFSET_X -> current.copy(
                offsetX = current.offsetX + translationStep * direction.sign,
            )
            DistanceAlignmentControl.OFFSET_Y -> current.copy(
                offsetY = current.offsetY + translationStep * direction.sign,
            )
        }
        return copy(offsets = offsets.toMutableList().also { it[selectedDistanceIndex] = adjusted })
    }

    companion object {
        val DEFAULT_DISTANCES = listOf(0.5f, 1f, 1.5f, 2f, 3f)
    }
}

enum class InverseDistanceAlignmentControl {
    DISTANCE,
    B,
    K,
}

data class InverseDistanceFitRecord(
    val distanceMeters: Int,
    val b: Float,
    val k: Float,
) {
    val offsetX: Float get() = b - k / distanceMeters.toFloat()
}

data class InverseDistanceAlignmentState(
    val distanceMeters: Float = 1f,
    val b: Float = DEFAULT_B,
    val k: Float = DEFAULT_K,
    val control: InverseDistanceAlignmentControl = InverseDistanceAlignmentControl.DISTANCE,
    val records: Map<Int, InverseDistanceFitRecord> = emptyMap(),
) {
    val offsetX: Float get() = b - k / distanceMeters
    val offsetY: Float get() = -234f
    val scale: Float get() = AlignmentCalibrationPreset.CALIBRATED_SCALE
    val alpha: Float get() = 0.5f

    fun selectNextControl(): InverseDistanceAlignmentState = copy(
        control = when (control) {
            InverseDistanceAlignmentControl.DISTANCE -> InverseDistanceAlignmentControl.B
            InverseDistanceAlignmentControl.B -> InverseDistanceAlignmentControl.K
            InverseDistanceAlignmentControl.K -> InverseDistanceAlignmentControl.DISTANCE
        },
    )

    fun adjust(direction: AdjustmentDirection): InverseDistanceAlignmentState {
        return when (control) {
            InverseDistanceAlignmentControl.DISTANCE -> {
                val savedRecords = records + currentRecord()
                val nextDistance = (distanceMeters + direction.sign).coerceAtLeast(MIN_DISTANCE_METERS)
                val nextRecord = savedRecords[nextDistance.toInt()]
                copy(
                    distanceMeters = nextDistance,
                    b = nextRecord?.b ?: DEFAULT_B,
                    k = nextRecord?.k ?: DEFAULT_K,
                    records = savedRecords,
                )
            }
            InverseDistanceAlignmentControl.B -> copy(b = b + direction.sign).withCurrentRecord()
            InverseDistanceAlignmentControl.K -> copy(k = k + direction.sign).withCurrentRecord()
        }
    }

    fun withCurrentRecord(): InverseDistanceAlignmentState {
        return copy(records = records + currentRecord())
    }

    fun toCsv(): String = buildString {
        append("distance_m,b,k,x,y\n")
        records.toSortedMap().values.forEach { record ->
            append(record.distanceMeters)
            append(',')
            append("%.2f".format(Locale.US, record.b))
            append(',')
            append("%.2f".format(Locale.US, record.k))
            append(',')
            append("%.2f".format(Locale.US, record.offsetX))
            append(',')
            append("%.2f".format(Locale.US, offsetY))
            append('\n')
        }
    }

    private fun currentRecord(): Pair<Int, InverseDistanceFitRecord> {
        val distance = distanceMeters.toInt()
        return distance to InverseDistanceFitRecord(distance, b, k)
    }

    fun calibrationState(): AlignmentCalibrationState = AlignmentCalibrationState(
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
        alpha = alpha,
    )

    companion object {
        const val MIN_DISTANCE_METERS = 1f
        const val DEFAULT_B = 108f
        const val DEFAULT_K = 115.94f
    }
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
