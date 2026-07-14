package com.rokid.glass.hiddenrisk

/** 与 NCNN 类别索引对齐、且不依赖 Android UI 的业务标签映射。 */
internal object HiddenRiskLabelCatalog {
    private val labelsById = mapOf(
        0 to "T字按钮",
        4 to "可燃气体报警器",
        6 to "室内消火栓箱",
        11 to "栓口",
        14 to "水带",
        15 to "水枪",
        17 to "液化石油气瓶",
        22 to "煤炉",
        24 to "熄火保护装置",
        25 to "燃气灶",
        28 to "负荷开关",
    )

    fun labelFor(detection: DetectionResult): String {
        return labelsById[detection.labelId] ?: detection.label.orEmpty()
    }
}
