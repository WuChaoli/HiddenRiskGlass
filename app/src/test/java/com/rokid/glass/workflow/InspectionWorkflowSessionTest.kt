package com.rokid.glass.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionWorkflowSessionTest {

    @Test
    fun clearForNewInspection_resetsDualSubmitProgress() {
        InspectionWorkflowSession.markPhoneSyncPrimaryDone()
        InspectionWorkflowSession.markFinishSubmitPrimaryDone()

        InspectionWorkflowSession.clearForNewInspection()

        assertFalse(InspectionWorkflowSession.phoneSyncProgress.primaryDone)
        assertFalse(InspectionWorkflowSession.phoneSyncProgress.backupDone)
        assertFalse(InspectionWorkflowSession.finishSubmitProgress.primaryDone)
        assertFalse(InspectionWorkflowSession.finishSubmitProgress.backupDone)
    }

    @Test
    fun markBackupDone_preservesPrimaryDoneState() {
        InspectionWorkflowSession.clearForNewInspection()
        InspectionWorkflowSession.markPhoneSyncPrimaryDone()
        InspectionWorkflowSession.markPhoneSyncBackupDone()
        InspectionWorkflowSession.markFinishSubmitPrimaryDone()
        InspectionWorkflowSession.markFinishSubmitBackupDone()

        assertTrue(InspectionWorkflowSession.phoneSyncProgress.primaryDone)
        assertTrue(InspectionWorkflowSession.phoneSyncProgress.backupDone)
        assertTrue(InspectionWorkflowSession.finishSubmitProgress.primaryDone)
        assertTrue(InspectionWorkflowSession.finishSubmitProgress.backupDone)

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
}
