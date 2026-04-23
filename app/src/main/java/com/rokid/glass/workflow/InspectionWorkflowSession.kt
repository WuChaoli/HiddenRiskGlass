package com.rokid.glass.workflow

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
    var inspectionSessionId: String = ""
    var latestAnalysisSessionId: String = ""
    var latestHazardRecordSessionId: String = ""
    var latestSyncedSessionId: String = ""
    var latestDetectionTitle: String? = null
    var latestDetectionMessage: String? = null
    var latestAnalysisText: String = ""
    var latestCapturedJpeg: ByteArray? = null
    var summary: InspectionSummary = InspectionSummary()

    fun updateMode(connected: Boolean) {
        workflowMode = if (connected) WorkflowMode.ONLINE else WorkflowMode.OFFLINE
    }

    fun beginInspection(sessionId: String) {
        inspectionSessionId = sessionId
        latestAnalysisSessionId = ""
        latestHazardRecordSessionId = ""
        latestSyncedSessionId = ""
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

    fun recordAnalysis(text: String, sessionId: String = latestAnalysisSessionId) {
        latestAnalysisText = text
        latestAnalysisSessionId = sessionId
    }

    fun recordHazardRecordUpload(sessionId: String) {
        latestHazardRecordSessionId = sessionId
    }

    fun recordPhoneSync(sessionId: String) {
        latestSyncedSessionId = sessionId
    }

    fun resolveFinishSessionId(): String? {
        return when {
            latestAnalysisSessionId.isNotBlank() -> latestAnalysisSessionId
            latestHazardRecordSessionId.isNotBlank() -> latestHazardRecordSessionId
            inspectionSessionId.isNotBlank() -> inspectionSessionId
            else -> null
        }
    }

    fun recordCapture(jpegBytes: ByteArray?) {
        latestCapturedJpeg = jpegBytes?.copyOf()
    }

    fun updateSummary(transform: (InspectionSummary) -> InspectionSummary) {
        summary = transform(summary)
    }

    fun clearForNewInspection() {
        latestDetectionTitle = null
        latestDetectionMessage = null
        latestAnalysisText = ""
        latestAnalysisSessionId = ""
        latestHazardRecordSessionId = ""
        latestSyncedSessionId = ""
        latestCapturedJpeg = null
        summary = InspectionSummary()
    }

    fun resetAll() {
        clearForNewInspection()
        enterpriseInfo = null
        inspectionSessionId = ""
        workflowMode = WorkflowMode.OFFLINE
    }
}
