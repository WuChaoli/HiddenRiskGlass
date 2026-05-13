package com.rokid.glass.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionWorkflowSessionTest {

    @Test
    fun clearForNewInspection_resetsSingleSubmitFlags() {
        InspectionWorkflowSession.markPhoneSyncPrimaryDone()
        InspectionWorkflowSession.markFinishSubmitPrimaryDone()

        InspectionWorkflowSession.clearForNewInspection()

        assertFalse(InspectionWorkflowSession.phoneSyncDone)
        assertFalse(InspectionWorkflowSession.finishSubmitDone)
    }

    @Test
    fun markSingleSubmitDone_setsFlagsCorrectly() {
        InspectionWorkflowSession.clearForNewInspection()

        InspectionWorkflowSession.markPhoneSyncPrimaryDone()
        InspectionWorkflowSession.markFinishSubmitPrimaryDone()

        assertTrue(InspectionWorkflowSession.phoneSyncDone)
        assertTrue(InspectionWorkflowSession.finishSubmitDone)

        InspectionWorkflowSession.clearForNewInspection()
    }

    @Test
    fun resolveFinishSessionId_fallsBackFromAnalysisToRecordToInspection() {
        InspectionWorkflowSession.resetAll()

        assertNull(InspectionWorkflowSession.resolveFinishSessionId())

        InspectionWorkflowSession.beginInspection("inspection-1")
        assertEquals("inspection-1", InspectionWorkflowSession.resolveFinishSessionId())

        InspectionWorkflowSession.recordHazardRecordUpload("record-1")
        assertEquals("record-1", InspectionWorkflowSession.resolveFinishSessionId())

        InspectionWorkflowSession.recordAnalysis("manual result", "")
        assertEquals("record-1", InspectionWorkflowSession.resolveFinishSessionId())

        InspectionWorkflowSession.recordAnalysis("stream result", "analysis-1")
        assertEquals("analysis-1", InspectionWorkflowSession.resolveFinishSessionId())

        InspectionWorkflowSession.resetAll()
    }

    @Test
    fun endReportHazardCount_dedupesAcrossRecordsAndSkipsBlankHidNum() {
        InspectionWorkflowSession.resetAll()

        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = "record-1",
            jpegBytes = byteArrayOf(1),
            hazardItems = listOf(
                savedHazardItem(hidNum = "HZ-001"),
                savedHazardItem(hidNum = "HZ-002"),
                savedHazardItem(hidNum = "   "),
            ),
        )
        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = "record-2",
            jpegBytes = byteArrayOf(2),
            hazardItems = listOf(
                savedHazardItem(hidNum = "HZ-002"),
                savedHazardItem(hidNum = "HZ-003"),
            ),
            saveOutcome = InspectionWorkflowSession.SaveOutcome.FAILED,
        )

        assertEquals(3, InspectionWorkflowSession.buildEndReportHazardCount())
        assertEquals(2, InspectionWorkflowSession.buildEndReportThumbnails().size)

        InspectionWorkflowSession.resetAll()
    }

    @Test
    fun recordSavedHazardAttempt_sameRecordKeyKeepsSingleRecord() {
        InspectionWorkflowSession.resetAll()

        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = "record-1",
            jpegBytes = byteArrayOf(1),
            hazardItems = listOf(savedHazardItem(hidNum = "HZ-001")),
        )
        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = "record-1",
            jpegBytes = byteArrayOf(9),
            hazardItems = listOf(savedHazardItem(hidNum = "HZ-009")),
            saveOutcome = InspectionWorkflowSession.SaveOutcome.FAILED,
        )

        val records = InspectionWorkflowSession.buildEndReportRecords()
        assertEquals(1, records.size)
        assertEquals(listOf("HZ-009"), records.first().normalizedHidNums())
        assertEquals(InspectionWorkflowSession.SaveOutcome.FAILED, records.first().saveOutcome)
        assertEquals(1, InspectionWorkflowSession.buildEndReportHazardCount())

        InspectionWorkflowSession.resetAll()
    }

    @Test
    fun endReportHazardCount_excludesSkippedExplicitRecords() {
        InspectionWorkflowSession.resetAll()

        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = "record-1",
            jpegBytes = byteArrayOf(1),
            hazardItems = listOf(savedHazardItem(hidNum = "HZ-001")),
            saveOutcome = InspectionWorkflowSession.SaveOutcome.SKIPPED_EXPLICIT,
        )
        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = "record-2",
            jpegBytes = byteArrayOf(2),
            hazardItems = listOf(savedHazardItem(hidNum = "HZ-002")),
            saveOutcome = InspectionWorkflowSession.SaveOutcome.SUCCESS,
        )

        assertEquals(1, InspectionWorkflowSession.buildEndReportHazardCount())
        assertEquals(1, InspectionWorkflowSession.buildEndReportThumbnails().size)

        InspectionWorkflowSession.resetAll()
    }

    @Test
    fun clearForNewInspection_clearsSavedHazardRecords() {
        InspectionWorkflowSession.resetAll()

        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = "record-1",
            jpegBytes = byteArrayOf(1),
            hazardItems = listOf(savedHazardItem(hidNum = "HZ-001")),
        )

        InspectionWorkflowSession.clearForNewInspection()

        assertTrue(InspectionWorkflowSession.buildEndReportRecords().isEmpty())
        assertEquals(0, InspectionWorkflowSession.buildEndReportHazardCount())
        assertTrue(InspectionWorkflowSession.buildEndReportThumbnails().isEmpty())
    }

    private fun savedHazardItem(
        hidNum: String,
        hidLevel: String = "1",
        description: String = "隐患描述",
        advice: String = "整改建议",
    ): InspectionWorkflowSession.SavedHazardItem {
        return InspectionWorkflowSession.SavedHazardItem(
            hidNum = hidNum,
            hidLevel = hidLevel,
            description = description,
            advice = advice,
        )
    }
}
