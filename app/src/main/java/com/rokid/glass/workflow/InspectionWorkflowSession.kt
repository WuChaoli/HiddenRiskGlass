package com.rokid.glass.workflow

import android.graphics.Bitmap

/**
 * 巡检主链路会话。
 * 首批先以内存单例承接页面跳转之间的上下文。
 */
object InspectionWorkflowSession {

    enum class WorkflowMode {
        ONLINE,
        OFFLINE,
    }

    data class EnterpriseInfo(
        val companyName: String,
        val siteName: String,
        val inspectorName: String,
        val qrContent: String,
    )

    data class InspectionSummary(
        val hasHazardCount: Int = 0,
        val noHazardCount: Int = 0,
        val mayHazardCount: Int = 0,
        val analyzedCount: Int = 0,
    )

    var workflowMode: WorkflowMode = WorkflowMode.OFFLINE
    var enterpriseInfo: EnterpriseInfo? = null
    var latestDetectionTitle: String? = null
    var latestDetectionMessage: String? = null
    var latestAnalysisText: String = ""
    var latestCapturedBitmap: Bitmap? = null
    var summary: InspectionSummary = InspectionSummary()

    fun updateMode(connected: Boolean) {
        workflowMode = if (connected) WorkflowMode.ONLINE else WorkflowMode.OFFLINE
    }

    fun updateEnterpriseFromQr(qrContent: String) {
        enterpriseInfo = EnterpriseInfo(
            companyName = "杭州基层应消科技示范单位",
            siteName = "滨江智造园区 3 号车间",
            inspectorName = "眼镜端巡检员",
            qrContent = qrContent,
        )
    }

    fun recordDetection(title: String, message: String) {
        latestDetectionTitle = title
        latestDetectionMessage = message
    }

    fun recordAnalysis(text: String) {
        latestAnalysisText = text
    }

    fun recordCapture(bitmap: Bitmap?) {
        latestCapturedBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        latestCapturedBitmap = bitmap
    }

    fun updateSummary(transform: (InspectionSummary) -> InspectionSummary) {
        summary = transform(summary)
    }

    fun clearForNewInspection() {
        latestDetectionTitle = null
        latestDetectionMessage = null
        latestAnalysisText = ""
        latestCapturedBitmap?.takeIf { !it.isRecycled }?.recycle()
        latestCapturedBitmap = null
        summary = InspectionSummary()
    }

    fun resetAll() {
        clearForNewInspection()
        enterpriseInfo = null
        workflowMode = WorkflowMode.OFFLINE
    }
}
