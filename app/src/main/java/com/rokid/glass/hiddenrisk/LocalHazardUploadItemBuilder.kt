package com.rokid.glass.hiddenrisk

/**
 * 组装本地隐患上传项。
 * 跳过空隐患编号，剩余项按隐患编号去重，重复时保留首条。
 */
object LocalHazardUploadItemBuilder {

    fun build(hazardContent: ResolvedHazardContent): List<LocalHazardPushService.HidDangerItem> {
        val uploadHazards = mutableListOf<ResolvedHazardItem>()
        val seenHidNums = linkedSetOf<String>()
        hazardContent.recordableHazards().forEach { hazard ->
            val normalizedHidNum = hazard.hidNum.trim()
            if (normalizedHidNum.isBlank()) {
                return@forEach
            }
            if (seenHidNums.add(normalizedHidNum)) {
                uploadHazards += hazard
            }
        }
        return uploadHazards.mapIndexed { index, hazard ->
            LocalHazardPushService.HidDangerItem(
                indexNum = (index + 1).toString(),
                descrip = hazard.description,
                advice = hazard.uploadAdvice,
                hidNum = hazard.hidNum,
                hidLevel = hazard.hidLevel,
                lawBasis = hazard.lawBasis,
            )
        }
    }
}
