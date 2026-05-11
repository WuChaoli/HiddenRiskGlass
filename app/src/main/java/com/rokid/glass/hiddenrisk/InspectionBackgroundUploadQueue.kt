package com.rokid.glass.hiddenrisk

import android.util.Log
import java.util.UUID

/**
 * 后台上传任务队列。
 * 用内存单例承接大对象，避免通过 Intent 直接传图片字节。
 */
object InspectionBackgroundUploadQueue {

    sealed class Task(
        open val taskId: String,
        open val taskKey: String?,
    )

    data class LocalHazardSaveTask(
        override val taskId: String,
        override val taskKey: String,
        val baseUrl: String,
        val authCode: String,
        val objectId: String,
        val userId: String,
        val customParam: String,
        val jpegBytes: ByteArray,
        val hidDanger: List<LocalHazardPushService.HidDangerItem>,
        val backupOnly: Boolean,
        val nsCode: String,
    ) : Task(taskId = taskId, taskKey = taskKey)

    data class FinishInspectionTask(
        override val taskId: String,
        override val taskKey: String,
        val baseUrl: String,
        val authCode: String,
        val objectId: String,
        val userId: String,
        val customParam: String,
        val backupOnly: Boolean,
        val nsCode: String,
    ) : Task(taskId = taskId, taskKey = taskKey)

    private val pendingTasks = linkedMapOf<String, Task>()
    private val activeTaskKeys = mutableSetOf<String>()

    fun enqueueLocalHazardSave(
        taskKey: String,
        baseUrl: String,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        jpegBytes: ByteArray,
        hidDanger: List<LocalHazardPushService.HidDangerItem>,
        backupOnly: Boolean = false,
        nsCode: String = "",
    ): String? {
        if (taskKey.isBlank() || jpegBytes.isEmpty() || hidDanger.isEmpty()) {
            return null
        }
        synchronized(this) {
            if (!activeTaskKeys.add(taskKey)) {
                Log.i(TAG, "skip duplicate local hazard upload taskKey=$taskKey")
                return null
            }
            val taskId = UUID.randomUUID().toString()
            pendingTasks[taskId] = LocalHazardSaveTask(
                taskId = taskId,
                taskKey = taskKey,
                baseUrl = baseUrl,
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
                jpegBytes = jpegBytes.copyOf(),
                hidDanger = hidDanger.toList(),
                backupOnly = backupOnly,
                nsCode = nsCode,
            )
            return taskId
        }
    }

    fun enqueueFinishInspection(
        taskKey: String,
        baseUrl: String,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        backupOnly: Boolean = false,
        nsCode: String = "",
    ): String? {
        if (taskKey.isBlank()) {
            return null
        }
        synchronized(this) {
            if (!activeTaskKeys.add(taskKey)) {
                Log.i(TAG, "skip duplicate finish upload taskKey=$taskKey")
                return null
            }
            val taskId = UUID.randomUUID().toString()
            pendingTasks[taskId] = FinishInspectionTask(
                taskId = taskId,
                taskKey = taskKey,
                baseUrl = baseUrl,
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
                backupOnly = backupOnly,
                nsCode = nsCode,
            )
            return taskId
        }
    }

    fun take(taskId: String): Task? {
        synchronized(this) {
            return pendingTasks.remove(taskId)
        }
    }

    fun complete(task: Task) {
        val taskKey = task.taskKey ?: return
        synchronized(this) {
            activeTaskKeys.remove(taskKey)
        }
    }

    private const val TAG = "InspectionBgUploadQueue"
}
