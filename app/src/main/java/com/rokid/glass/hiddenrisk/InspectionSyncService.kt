package com.rokid.glass.hiddenrisk

import android.util.Log
import com.rokid.glass.utils.HttpUtils
import com.rokid.glass.workflow.InspectionWorkflowSession

/**
 * 巡检结果同步到手机端服务。
 */
object InspectionSyncService {
    private const val TAG = "InspectionSyncService"
    private const val DEFAULT_FAILURE_MESSAGE = "同步失败，请重试"

    private val httpUtils = HttpUtils()

    interface Callback {
        fun onSuccess()
        fun onError(message: String)
    }

    fun syncAnalysisToPhone(
        sessionId: String,
        callback: Callback,
    ): RetryRequestHandle {
        val handle = RetryRequestHandle()
        if (sessionId.isBlank()) {
            callback.onError("缺少分析会话 ID")
            return handle
        }
        val progress = InspectionWorkflowSession.phoneSyncProgress
        if (!progress.primaryDone) {
            submitPrimary(
                sessionId = sessionId,
                handle = handle,
                callback = callback,
            )
        } else {
            submitBackup(
                sessionId = sessionId,
                handle = handle,
                callback = callback,
            )
        }
        return handle
    }

    private fun submitPrimary(
        sessionId: String,
        handle: RetryRequestHandle,
        callback: Callback,
    ) {
        submitSingleEndpoint(
            label = "primary",
            requestUrl = HttpUtils.PRIMARY_SAVE_RESULT_URL,
            sessionId = sessionId,
            handle = handle,
        ) { outcome ->
            if (!outcome.success) {
                InspectionWorkflowSession.clearPhoneSyncProgress()
                callback.onError(outcome.message ?: DEFAULT_FAILURE_MESSAGE)
                return@submitSingleEndpoint
            }
            Log.i(TAG, "primary save synced attempts=${outcome.attemptCount}")
            InspectionWorkflowSession.markPhoneSyncPrimaryDone()
            submitBackup(
                sessionId = sessionId,
                handle = handle,
                callback = callback,
            )
        }
    }

    private fun submitBackup(
        sessionId: String,
        handle: RetryRequestHandle,
        callback: Callback,
    ) {
        submitSingleEndpoint(
            label = "backup",
            requestUrl = HttpUtils.BACKUP_SAVE_RESULT_URL,
            sessionId = sessionId,
            handle = handle,
        ) { outcome ->
            if (!outcome.success) {
                InspectionWorkflowSession.markPhoneSyncPrimaryDone()
                callback.onError(outcome.message ?: DEFAULT_FAILURE_MESSAGE)
                return@submitSingleEndpoint
            }
            Log.i(TAG, "backup save synced attempts=${outcome.attemptCount}")
            InspectionWorkflowSession.markPhoneSyncBackupDone()
            InspectionWorkflowSession.clearPhoneSyncProgress()
            callback.onSuccess()
        }
    }

    private fun submitSingleEndpoint(
        label: String,
        requestUrl: String,
        sessionId: String,
        handle: RetryRequestHandle,
        onComplete: (RetryOutcome) -> Unit,
    ) {
        InspectionRetryExecutor.execute(
            label = "save-$label",
            handle = handle,
            attemptBlock = { attempt, completion ->
                Log.i(TAG, "submit save endpoint=$label attempt=$attempt url=$requestUrl sessionId=$sessionId")
                httpUtils.reportSaveResult(
                    snCode = RokidSdkManager.getSerialNumber(),
                    isSave = "1",
                    sessionId = sessionId,
                    requestUrl = requestUrl,
                    callback = object : HttpUtils.SaveResultCallback {
                        override fun onSuccess(response: HttpUtils.ApiResponse) {
                            if (response.isSuccess()) {
                                completion(RetryAttemptResult(success = true))
                            } else {
                                val message = response.msg?.trim().takeUnless { it.isNullOrEmpty() }
                                    ?: DEFAULT_FAILURE_MESSAGE
                                Log.w(
                                    TAG,
                                    "submit save endpoint=$label businessFailed attempt=$attempt code=${response.code} msg=$message",
                                )
                                completion(RetryAttemptResult(success = false, message = message))
                            }
                        }

                        override fun onFailure(e: Exception) {
                            Log.e(TAG, "submit save endpoint=$label requestFailed attempt=$attempt", e)
                            completion(
                                RetryAttemptResult(
                                    success = false,
                                    message = e.message ?: DEFAULT_FAILURE_MESSAGE,
                                ),
                            )
                        }
                    },
                )
            },
            onComplete = onComplete,
        )
    }
}
