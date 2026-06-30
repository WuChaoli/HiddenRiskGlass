package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalHazardInfoAssetSchemaTest {

    private data class HazardRecord(
        val item: List<String> = emptyList(),
        val descrip: String = "",
        val hidLevel: String = "",
        val lawBasis: String = "",
        val hidNum: String = "",
        val advice: String = "",
        val modify: String = "",
    )

    @Test
    fun infoJson_parsesRecordsAndIncludesModifyField() {
        val records = loadRecords()

        assertEquals(6, records.size)
        records.forEach { record ->
            val normalizedItems = record.item
                .map { it.trim() }
                .filter { it.isNotBlank() }
            assertFalse("item should not be empty for ${record.hidNum}", normalizedItems.isEmpty())
            assertFalse("modify should not be blank for ${record.hidNum}", record.modify.isBlank())
            assertNotEquals("modify should differ from advice for ${record.hidNum}", record.advice.trim(), record.modify.trim())
        }

        assertRecord(
            records,
            hidNum = "ZJYJ_HZ_JX_XCY_009",
            items = listOf("燃气灶"),
            hidLevel = "1",
            lawBasis = "《城镇燃气管理条例》第27条和《浙江省燃气管理条例》第36条",
            modify = "更换为安装有熄火保护装置的燃气灶具，以确保意外熄火时能及时切断燃气。",
        )
        assertRecord(
            records,
            hidNum = "ZJYJ_HZ_JX_XCY_007",
            items = listOf("煤炉"),
            hidLevel = "1",
            lawBasis = "《燃气用户设施安全检查标准》第3.0.9条",
            modify = "建议在同一用气场所只使用一种燃料，避免因燃料混用引发的安全事故。",
        )
    }

    private fun loadRecords(): List<HazardRecord> {
        val jsonFile = listOf(
            File("app/src/main/assets/info.json"),
            File("src/main/assets/info.json"),
        ).firstOrNull { it.exists() } ?: error("info.json not found from test working directory")
        val type = object : TypeToken<List<HazardRecord>>() {}.type
        return Gson().fromJson<List<HazardRecord>>(jsonFile.readText(Charsets.UTF_8), type).orEmpty()
    }

    private fun assertRecord(
        records: List<HazardRecord>,
        hidNum: String,
        items: List<String>,
        hidLevel: String,
        lawBasis: String,
        modify: String,
    ) {
        val record = records.firstOrNull { it.hidNum == hidNum }
        assertNotNull("missing record for $hidNum", record)
        assertEquals(items, record?.item)
        assertEquals(hidNum, record?.hidNum)
        assertEquals(hidLevel, record?.hidLevel)
        assertEquals(lawBasis, record?.lawBasis)
        assertEquals(modify, record?.modify)
    }
}
