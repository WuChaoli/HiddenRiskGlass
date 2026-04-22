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
        // 扩展字段，用于企业信息展示
        val region: String = "",
        val category: String = "",
        val riskTags: String = "",
        val riskLevel: String = "",
        val hazardHistory: List<String> = emptyList(),
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
            companyName = "天天小吃店",
            siteName = "滨江智造园区 3 号车间",
            inspectorName = "眼镜端巡检员",
            qrContent = qrContent,
            region = "杭州市萧山区",
            category = "消防安全",
            riskTags = "九小场所（小餐饮）、消防重点场所",
            riskLevel = "一般风险",
            hazardHistory = listOf(
                "三合一住人",
                "防盗窗未设紧急逃生口",
                "电子烟靠近笔记本电脑存在火灾风险",
                "防盗窗影响逃生和灭火救援",
                "多孔插线板随意放置",
                "电气安全",
                "多设备集中连接",
            ),
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
