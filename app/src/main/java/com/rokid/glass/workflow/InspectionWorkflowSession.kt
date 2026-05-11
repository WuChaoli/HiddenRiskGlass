package com.rokid.glass.workflow

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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

    data class EnterpriseQrPayload(
        val rightCode: String,
        val objectId: String,
        val userId: String,
        val regionCode: String,
        val apiBaseUrl: String,
        val authCode: String,
        val extraField: String,
        val rawContent: String,
    )

    data class InspectionSummary(
        val hasHazardCount: Int = 0,
        val noHazardCount: Int = 0,
        val mayHazardCount: Int = 0,
        val analyzedCount: Int = 0,
    )

    data class DualSubmitProgress(
        val primaryDone: Boolean = false,
        val backupDone: Boolean = false,
    )

    enum class SaveOutcome {
        PENDING,
        SUCCESS,
        FAILED,
        SKIPPED_EXPLICIT,
    }

    data class SavedHazardItem(
        val hidNum: String,
        val hidLevel: String,
        val description: String,
        val advice: String,
    )

    data class SavedHazardRecord(
        val recordKey: String,
        val jpegBytes: ByteArray?,
        val hazardItems: List<SavedHazardItem>,
        val saveIntent: Boolean,
        val saveOutcome: SaveOutcome,
    ) {
        fun normalizedHidNums(): List<String> {
            return hazardItems
                .map { it.hidNum.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }
    }

    var workflowMode: WorkflowMode = WorkflowMode.OFFLINE
    var enterpriseInfo: EnterpriseInfo? = null
    var enterpriseQrPayload: EnterpriseQrPayload? = null
        private set
    var inspectionSessionId: String = ""
    var latestAnalysisSessionId: String = ""
    var latestHazardRecordSessionId: String = ""
    var latestSyncedSessionId: String = ""
    var latestDetectionTitle: String? = null
    var latestDetectionMessage: String? = null
    var latestAnalysisText: String = ""
    var latestCapturedJpeg: ByteArray? = null
    var deviceNsCode: String = ""
        private set
    var phoneSyncProgress: DualSubmitProgress = DualSubmitProgress()
        private set
    var finishSubmitProgress: DualSubmitProgress = DualSubmitProgress()
        private set
    private val savedHazardRecordsByKey = linkedMapOf<String, SavedHazardRecord>()
    var summary: InspectionSummary = InspectionSummary()

    fun updateMode(connected: Boolean) {
        workflowMode = if (connected) WorkflowMode.ONLINE else WorkflowMode.OFFLINE
    }

    fun updateDeviceNsCode(nsCode: String) {
        val normalizedNsCode = nsCode.trim()
        if (normalizedNsCode.isBlank()) {
            Log.w(TAG, "device nsCode is blank")
        } else {
            deviceNsCode = normalizedNsCode
            Log.i(TAG, "device nsCode cached")
        }
    }

    fun beginInspection(sessionId: String) {
        inspectionSessionId = sessionId
        latestAnalysisSessionId = ""
        latestHazardRecordSessionId = ""
        latestSyncedSessionId = ""
        clearPhoneSyncProgress()
        clearFinishSubmitProgress()
    }

    fun updateEnterpriseFromQr(qrContent: String): Boolean {
        val payload = parseEnterpriseQrPayload(qrContent) ?: return false
        val previousPayload = enterpriseQrPayload
        val identityChanged = previousPayload != null && hasEnterpriseIdentityChanged(previousPayload, payload)
        if (identityChanged) {
            // 企业身份切换后，上一轮巡检累计结果不应继续复用。
            clearInspectionAccumulatedResults()
            enterpriseInfo = null
        } else if (previousPayload == null) {
            enterpriseInfo = null
        }
        enterpriseQrPayload = payload
        Log.i(
            TAG,
            "enterprise qr parsed objectId=${payload.objectId} regionCode=${payload.regionCode} baseUrl=${payload.apiBaseUrl} extraField=${sanitizeQrForLog(payload.extraField)} identityChanged=$identityChanged",
        )
        return true
    }

    fun updateEnterpriseObjectInfo(
        companyName: String?,
        region: String?,
        category: String?,
        riskTags: String?,
        riskLevel: String?,
        hazardHistory: List<String>,
    ) {
        val payload = enterpriseQrPayload ?: return
        enterpriseInfo = EnterpriseInfo(
            companyName = companyName?.trim().takeUnless { it.isNullOrEmpty() } ?: "-",
            siteName = DEFAULT_ENTERPRISE_SITE_NAME,
            inspectorName = DEFAULT_ENTERPRISE_INSPECTOR_NAME,
            qrContent = payload.rawContent,
            region = region.orEmpty(),
            category = category.orEmpty(),
            riskTags = riskTags.orEmpty(),
            riskLevel = riskLevel.orEmpty(),
            hazardHistory = hazardHistory,
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

    fun markPhoneSyncPrimaryDone() {
        phoneSyncProgress = phoneSyncProgress.copy(primaryDone = true)
    }

    fun markPhoneSyncBackupDone() {
        phoneSyncProgress = phoneSyncProgress.copy(backupDone = true)
    }

    fun clearPhoneSyncProgress() {
        phoneSyncProgress = DualSubmitProgress()
    }

    fun markFinishSubmitPrimaryDone() {
        finishSubmitProgress = finishSubmitProgress.copy(primaryDone = true)
    }

    fun markFinishSubmitBackupDone() {
        finishSubmitProgress = finishSubmitProgress.copy(backupDone = true)
    }

    fun clearFinishSubmitProgress() {
        finishSubmitProgress = DualSubmitProgress()
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

    fun recordSavedHazardAttempt(
        recordKey: String,
        jpegBytes: ByteArray?,
        hazardItems: List<SavedHazardItem>,
        saveIntent: Boolean = true,
        saveOutcome: SaveOutcome = SaveOutcome.PENDING,
    ): Boolean {
        if (recordKey.isBlank() || hazardItems.isEmpty()) {
            return false
        }
        val normalizedItems = hazardItems
            .map { item ->
                item.copy(
                    hidNum = item.hidNum.trim(),
                    hidLevel = item.hidLevel.trim(),
                    description = item.description.trim(),
                    advice = item.advice.trim(),
                )
            }
            .filter { it.hidNum.isNotBlank() }
        if (normalizedItems.isEmpty()) {
            return false
        }
        val existing = savedHazardRecordsByKey[recordKey]
        savedHazardRecordsByKey[recordKey] = SavedHazardRecord(
            recordKey = recordKey,
            jpegBytes = jpegBytes
                ?.takeIf { it.isNotEmpty() }
                ?.copyOf()
                ?: existing?.jpegBytes?.copyOf(),
            hazardItems = normalizedItems,
            saveIntent = saveIntent,
            saveOutcome = saveOutcome,
        )
        return true
    }

    fun updateSavedHazardAttemptOutcome(
        recordKey: String,
        saveOutcome: SaveOutcome,
    ): Boolean {
        val existing = savedHazardRecordsByKey[recordKey] ?: return false
        savedHazardRecordsByKey[recordKey] = existing.copy(saveOutcome = saveOutcome)
        return true
    }

    fun buildEndReportRecords(): List<SavedHazardRecord> {
        return savedHazardRecordsByKey.values.map { record ->
            record.copy(
                jpegBytes = record.jpegBytes?.copyOf(),
                hazardItems = record.hazardItems.toList(),
            )
        }
    }

    fun buildEndReportHazardCount(): Int {
        return savedHazardRecordsByKey.values
            .asSequence()
            .filter { it.saveIntent && it.saveOutcome != SaveOutcome.SKIPPED_EXPLICIT }
            .flatMap { it.normalizedHidNums().asSequence() }
            .toSet()
            .size
    }

    fun buildEndReportThumbnails(): List<ByteArray> {
        return savedHazardRecordsByKey.values
            .asSequence()
            .filter { it.saveIntent && it.saveOutcome != SaveOutcome.SKIPPED_EXPLICIT }
            .mapNotNull { it.jpegBytes?.takeIf { bytes -> bytes.isNotEmpty() }?.copyOf() }
            .toList()
    }

    fun updateSummary(transform: (InspectionSummary) -> InspectionSummary) {
        summary = transform(summary)
    }

    /**
     * 清空当前巡检累计结果，但保留企业扫码上下文。
     * 用于确认结束巡检、应用完全退出、或重新扫码切换企业时。
     */
    fun clearInspectionAccumulatedResults() {
        latestDetectionTitle = null
        latestDetectionMessage = null
        latestAnalysisText = ""
        latestAnalysisSessionId = ""
        latestHazardRecordSessionId = ""
        latestSyncedSessionId = ""
        inspectionSessionId = ""
        latestCapturedJpeg = null
        deviceNsCode = ""
        clearPhoneSyncProgress()
        clearFinishSubmitProgress()
        savedHazardRecordsByKey.clear()
        summary = InspectionSummary()
    }

    /**
     * 兼容旧测试与旧调用入口。
     * 语义等同于“清空当前巡检累计结果，但保留企业扫码上下文”。
     */
    fun clearForNewInspection() {
        clearInspectionAccumulatedResults()
    }

    fun resetAll() {
        clearInspectionAccumulatedResults()
        workflowMode = WorkflowMode.OFFLINE
    }

    /**
     * 清除企业相关信息（扫码结果 + 后端拉取的企业详情）。
     * 在应用退出时调用，确保下次打开需要重新扫码。
     */
    fun clearEnterpriseData() {
        enterpriseQrPayload = null
        enterpriseInfo = null
        Log.i(TAG, "enterprise data cleared")
    }

    private fun hasEnterpriseIdentityChanged(
        previous: EnterpriseQrPayload,
        current: EnterpriseQrPayload,
    ): Boolean {
        return previous.rightCode != current.rightCode ||
            previous.objectId != current.objectId ||
            previous.userId != current.userId ||
            previous.regionCode != current.regionCode ||
            previous.apiBaseUrl != current.apiBaseUrl ||
            previous.authCode != current.authCode ||
            previous.extraField != current.extraField
    }

    private fun parseEnterpriseQrPayload(qrContent: String): EnterpriseQrPayload? {
        val rawContent = qrContent.trim()
        if (rawContent.isBlank()) {
            Log.w(TAG, "enterprise qr parse failed: blank content")
            return null
        }

        parseLegacyEnterpriseQr(rawContent)?.let { return it }
        parseQueryEnterpriseQr(rawContent)?.let { return it }
        parseJsonEnterpriseQr(rawContent)?.let { return it }

        val decodedContent = decodeQrContent(rawContent)
        if (decodedContent != rawContent) {
            parseLegacyEnterpriseQr(decodedContent)?.let { return it.copy(rawContent = rawContent) }
            parseQueryEnterpriseQr(decodedContent)?.let { return it.copy(rawContent = rawContent) }
            parseJsonEnterpriseQr(decodedContent)?.let { return it.copy(rawContent = rawContent) }
        }

        Log.w(TAG, "enterprise qr parse failed raw=${sanitizeQrForLog(rawContent)}")
        return null
    }

    private fun parseLegacyEnterpriseQr(rawContent: String): EnterpriseQrPayload? {
        val commaParts = rawContent.split(',', limit = 2)
        if (commaParts.size == 2) {
            val rightCode = commaParts[0].trim()
            val tailParts = commaParts[1].split(';').map { it.trim() }
            if (tailParts.size < LEGACY_ENTERPRISE_QR_MIN_TAIL_FIELD_COUNT) {
                return null
            }
            val objectId = tailParts.getOrNull(0).orEmpty()
            val userId = tailParts.getOrNull(1).orEmpty()
            val regionCode = tailParts.getOrNull(2).orEmpty()
            val apiBaseUrl = tailParts.getOrNull(3).orEmpty()
            val authCode = tailParts.getOrNull(4).orEmpty()
            val extraField = tailParts.drop(5).joinToString(";")
            return buildEnterpriseQrPayload(
                rightCode = rightCode,
                objectId = objectId,
                userId = userId,
                regionCode = regionCode,
                apiBaseUrl = apiBaseUrl,
                authCode = authCode,
                extraField = extraField,
                rawContent = rawContent,
            )
        }

        val semicolonParts = rawContent.split(';').map { it.trim() }
        if (semicolonParts.size < ENTERPRISE_QR_MIN_FIELD_COUNT) {
            return null
        }
        val rightCode = semicolonParts.getOrNull(0).orEmpty()
        val objectId = semicolonParts.getOrNull(1).orEmpty()
        val userId = semicolonParts.getOrNull(2).orEmpty()
        val regionCode = semicolonParts.getOrNull(3).orEmpty()
        val apiBaseUrl = semicolonParts.getOrNull(4).orEmpty()
        val authCode = semicolonParts.getOrNull(5).orEmpty()
        val extraField = semicolonParts.drop(6).joinToString(";")
        return buildEnterpriseQrPayload(
            rightCode = rightCode,
            objectId = objectId,
            userId = userId,
            regionCode = regionCode,
            apiBaseUrl = apiBaseUrl,
            authCode = authCode,
            extraField = extraField,
            rawContent = rawContent,
        )
    }

    private fun parseQueryEnterpriseQr(rawContent: String): EnterpriseQrPayload? {
        val uri = runCatching { Uri.parse(rawContent) }.getOrNull() ?: return null
        val objectId = firstNonBlank(
            uri.getQueryParameter("objectId"),
            uri.getQueryParameter("objectid"),
            uri.getQueryParameter("id"),
        )
        val authCode = firstNonBlank(
            uri.getQueryParameter("authCode"),
            uri.getQueryParameter("authcode"),
        )
        val apiBaseUrl = firstNonBlank(
            uri.getQueryParameter("apiBaseUrl"),
            uri.getQueryParameter("apiBaseURL"),
            uri.getQueryParameter("baseUrl"),
            uri.getQueryParameter("baseURL"),
        ) ?: "${uri.scheme ?: "http"}://${uri.authority.orEmpty()}${uri.path.orEmpty()}"
        val rightCode = firstNonBlank(
            uri.getQueryParameter("rightCode"),
            uri.getQueryParameter("objectName"),
            uri.getQueryParameter("name"),
        ).orEmpty()
        return buildEnterpriseQrPayload(
            rightCode = rightCode,
            objectId = objectId.orEmpty(),
            userId = firstNonBlank(uri.getQueryParameter("userId"), uri.getQueryParameter("userid")).orEmpty(),
            regionCode = firstNonBlank(uri.getQueryParameter("regionCode"), uri.getQueryParameter("regioncode")).orEmpty(),
            apiBaseUrl = apiBaseUrl.orEmpty(),
            authCode = authCode.orEmpty(),
            extraField = firstNonBlank(
                uri.getQueryParameter("extraField"),
                uri.getQueryParameter("extra"),
                uri.getQueryParameter("ext"),
            ).orEmpty(),
            rawContent = rawContent,
        )
    }

    private fun parseJsonEnterpriseQr(rawContent: String): EnterpriseQrPayload? {
        val jsonObject = runCatching {
            gson.fromJson(rawContent, JsonObject::class.java)
        }.getOrNull() ?: return null
        return buildEnterpriseQrPayload(
            rightCode = firstNonBlank(
                jsonObject.getStringOrNull("rightCode"),
                jsonObject.getStringOrNull("objectName"),
                jsonObject.getStringOrNull("name"),
            ).orEmpty(),
            objectId = firstNonBlank(
                jsonObject.getStringOrNull("objectId"),
                jsonObject.getStringOrNull("objectid"),
                jsonObject.getStringOrNull("id"),
            ).orEmpty(),
            userId = firstNonBlank(
                jsonObject.getStringOrNull("userId"),
                jsonObject.getStringOrNull("userid"),
            ).orEmpty(),
            regionCode = firstNonBlank(
                jsonObject.getStringOrNull("regionCode"),
                jsonObject.getStringOrNull("regioncode"),
            ).orEmpty(),
            apiBaseUrl = firstNonBlank(
                jsonObject.getStringOrNull("apiBaseUrl"),
                jsonObject.getStringOrNull("apiBaseURL"),
                jsonObject.getStringOrNull("baseUrl"),
                jsonObject.getStringOrNull("baseURL"),
            ).orEmpty(),
            authCode = firstNonBlank(
                jsonObject.getStringOrNull("authCode"),
                jsonObject.getStringOrNull("authcode"),
            ).orEmpty(),
            extraField = firstNonBlank(
                jsonObject.getStringOrNull("extraField"),
                jsonObject.getStringOrNull("extra"),
                jsonObject.getStringOrNull("ext"),
            ).orEmpty(),
            rawContent = rawContent,
        )
    }

    private fun buildEnterpriseQrPayload(
        rightCode: String,
        objectId: String,
        userId: String,
        regionCode: String,
        apiBaseUrl: String,
        authCode: String,
        extraField: String,
        rawContent: String,
    ): EnterpriseQrPayload? {
        if (objectId.isBlank() || authCode.isBlank() || apiBaseUrl.isBlank()) {
            return null
        }
        return EnterpriseQrPayload(
            rightCode = rightCode,
            objectId = objectId,
            userId = userId,
            regionCode = regionCode,
            apiBaseUrl = apiBaseUrl,
            authCode = authCode,
            extraField = extraField,
            rawContent = rawContent,
        )
    }

    private fun decodeQrContent(rawContent: String): String {
        return runCatching {
            URLDecoder.decode(rawContent, StandardCharsets.UTF_8.name()).trim()
        }.getOrDefault(rawContent)
    }

    private fun sanitizeQrForLog(rawContent: String): String {
        if (rawContent.length <= QR_LOG_VISIBLE_PREFIX_LENGTH) {
            return rawContent
        }
        return rawContent.take(QR_LOG_VISIBLE_PREFIX_LENGTH) + "..."
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        if (!has(key) || get(key).isJsonNull) return null
        return runCatching { get(key).asString }.getOrNull()?.trim()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private const val DEFAULT_ENTERPRISE_SITE_NAME = "滨江智造园区 3 号车间"
    private const val DEFAULT_ENTERPRISE_INSPECTOR_NAME = "眼镜端巡检员"
    private const val ENTERPRISE_QR_MIN_FIELD_COUNT = 6
    private const val LEGACY_ENTERPRISE_QR_MIN_TAIL_FIELD_COUNT = 5
    private const val QR_LOG_VISIBLE_PREFIX_LENGTH = 120
    private const val TAG = "InspectionWorkflow"
    private val gson = Gson()
}
