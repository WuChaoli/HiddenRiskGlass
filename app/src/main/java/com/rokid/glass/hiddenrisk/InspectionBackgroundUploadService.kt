package com.rokid.glass.hiddenrisk

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.rokid.glass.workflow.InspectionWorkflowSession

/**
 * 后台执行静默上传，不反向驱动页面 UI。
 */
class InspectionBackgroundUploadService : Service() {

    private val localHazardPushService by lazy { LocalHazardPushService() }
    private var processing = false
    private var currentHandle: RetryRequestHandle? = null
    private val queuedCommands = ArrayDeque<QueuedCommand>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        queuedCommands.addLast(QueuedCommand(startId = startId, taskId = taskId))
        processNextIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentHandle = null
        queuedCommands.clear()
        processing = false
        super.onDestroy()
    }

    private fun processNextIfIdle() {
        if (processing) {
            return
        }
        val command = if (queuedCommands.isEmpty()) {
            null
        } else {
            queuedCommands.removeFirst()
        } ?: return
        val task = InspectionBackgroundUploadQueue.take(command.taskId)
        if (task == null) {
            stopSelf(command.startId)
            processNextIfIdle()
            return
        }
        processing = true
        when (task) {
            is InspectionBackgroundUploadQueue.LocalHazardSaveTask -> {
                handleLocalHazardSave(command.startId, task)
            }

            is InspectionBackgroundUploadQueue.FinishInspectionTask -> {
                handleFinishInspection(command.startId, task)
            }
        }
    }

    private fun handleLocalHazardSave(
        startId: Int,
        task: InspectionBackgroundUploadQueue.LocalHazardSaveTask,
    ) {
        currentHandle = localHazardPushService.pushLocalHazard(
            baseUrl = task.baseUrl,
            authCode = task.authCode,
            objectId = task.objectId,
            userId = task.userId,
            customParam = task.customParam,
            jpegBytes = task.jpegBytes,
            hidDanger = task.hidDanger,
            backupOnly = task.backupOnly,
            nsCode = task.nsCode,
            callback = object : LocalHazardPushService.Callback {
                override fun onSuccess() {
                    val recorded = InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                        recordKey = "background_save|${task.taskKey}",
                        saveOutcome = InspectionWorkflowSession.SaveOutcome.SUCCESS,
                    )
                    Log.i(
                        TAG,
                        "local hazard background upload success taskKey=${task.taskKey} recorded=$recorded",
                    )
                    finishTask(startId, task)
                }

                override fun onFailure(message: String) {
                    InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                        recordKey = "background_save|${task.taskKey}",
                        saveOutcome = InspectionWorkflowSession.SaveOutcome.FAILED,
                    )
                    Log.w(
                        TAG,
                        "local hazard background upload failed taskKey=${task.taskKey} message=$message",
                    )
                    finishTask(startId, task)
                }
            },
        )
    }

    private fun handleFinishInspection(
        startId: Int,
        task: InspectionBackgroundUploadQueue.FinishInspectionTask,
    ) {
        currentHandle = InspectionFinishService.finishInspection(
            baseUrl = task.baseUrl,
            authCode = task.authCode,
            objectId = task.objectId,
            userId = task.userId,
            customParam = task.customParam,
            backupOnly = task.backupOnly,
            nsCode = task.nsCode,
            callback = object : InspectionFinishService.Callback {
                override fun onSuccess() {
                    Log.i(TAG, "finish background upload success taskKey=${task.taskKey}")
                    finishTask(startId, task)
                }

                override fun onError(message: String) {
                    Log.w(
                        TAG,
                        "finish background upload failed taskKey=${task.taskKey} message=$message",
                    )
                    finishTask(startId, task)
                }
            },
        )
    }

    private fun finishTask(
        startId: Int,
        task: InspectionBackgroundUploadQueue.Task,
    ) {
        currentHandle = null
        processing = false
        InspectionBackgroundUploadQueue.complete(task)
        stopSelf(startId)
        processNextIfIdle()
    }

    private data class QueuedCommand(
        val startId: Int,
        val taskId: String,
    )

    companion object {
        private const val TAG = "InspectionBgUploadSvc"
        private const val EXTRA_TASK_ID = "task_id"

        fun start(
            context: Context,
            taskId: String,
        ) {
            val intent = Intent(context, InspectionBackgroundUploadService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }
}
