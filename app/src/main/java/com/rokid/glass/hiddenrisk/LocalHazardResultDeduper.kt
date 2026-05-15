package com.rokid.glass.hiddenrisk

/**
 * 本地隐患识别结果去重规则。
 *
 * 非空隐患编号相同视为同一隐患，保留置信度最高的结果；空编号无法可靠标识，全部保留。
 */
internal object LocalHazardResultDeduper {

    fun <T> dedupeByHidNumKeepingHighestScore(
        matches: List<T>,
        hidNumOf: (T) -> String,
        scoreOf: (T) -> Float,
    ): List<T> {
        if (matches.isEmpty()) {
            return emptyList()
        }
        val deduped = mutableListOf<T>()
        val indexByHidNum = linkedMapOf<String, Int>()
        matches.forEach { match ->
            val hidNum = hidNumOf(match).trim()
            if (hidNum.isBlank()) {
                deduped += match
                return@forEach
            }
            val previousIndex = indexByHidNum[hidNum]
            if (previousIndex == null) {
                indexByHidNum[hidNum] = deduped.size
                deduped += match
                return@forEach
            }
            if (scoreOf(match) > scoreOf(deduped[previousIndex])) {
                deduped[previousIndex] = match
            }
        }
        return deduped
    }
}
