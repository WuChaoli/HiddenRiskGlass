package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalHazardResultDeduperTest {

    @Test
    fun dedupeByHidNumKeepingHighestScore_dedupesSameNonBlankHidNum() {
        val items = LocalHazardResultDeduper.dedupeByHidNumKeepingHighestScore(
            matches = listOf(
                match(title = "燃气灶", hidNum = "HZ-001", score = 0.65f),
                match(title = "液化石油气瓶", hidNum = "HZ-001", score = 0.92f),
                match(title = "电箱", hidNum = "HZ-002", score = 0.70f),
            ),
            hidNumOf = { it.hidNum },
            scoreOf = { it.score },
        )

        assertEquals(listOf("液化石油气瓶", "电箱"), items.map { it.title })
        assertEquals(listOf("HZ-001", "HZ-002"), items.map { it.hidNum })
    }

    @Test
    fun dedupeByHidNumKeepingHighestScore_keepsDifferentNonBlankHidNums() {
        val items = LocalHazardResultDeduper.dedupeByHidNumKeepingHighestScore(
            matches = listOf(
                match(title = "燃气灶", hidNum = "HZ-001", score = 0.65f),
                match(title = "电箱", hidNum = "HZ-002", score = 0.70f),
                match(title = "消防通道", hidNum = "HZ-003", score = 0.80f),
            ),
            hidNumOf = { it.hidNum },
            scoreOf = { it.score },
        )

        assertEquals(listOf("燃气灶", "电箱", "消防通道"), items.map { it.title })
    }

    @Test
    fun dedupeByHidNumKeepingHighestScore_keepsBlankHidNumItems() {
        val items = LocalHazardResultDeduper.dedupeByHidNumKeepingHighestScore(
            matches = listOf(
                match(title = "燃气灶", hidNum = "", score = 0.65f),
                match(title = "液化石油气瓶", hidNum = "   ", score = 0.92f),
                match(title = "电箱", hidNum = "HZ-002", score = 0.70f),
            ),
            hidNumOf = { it.hidNum },
            scoreOf = { it.score },
        )

        assertEquals(listOf("燃气灶", "液化石油气瓶", "电箱"), items.map { it.title })
        assertEquals(listOf("", "   ", "HZ-002"), items.map { it.hidNum })
    }

    private fun match(
        title: String,
        hidNum: String,
        score: Float,
    ): Match {
        return Match(title = title, hidNum = hidNum, score = score)
    }

    private data class Match(
        val title: String,
        val hidNum: String,
        val score: Float,
    )
}
