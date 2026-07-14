package com.rokid.glass.hiddenrisk

import android.util.Log
import java.util.concurrent.Executors

/**
 * 进程级本地推理协调器。
 *
 * 所有状态只在同一执行序列内读写，避免模型加载、推理和释放并发进入 JNI。
 */
internal class LocalInferenceCoordinator(
    private val executor: TaskExecutor,
    private val engineFactory: () -> NativeEngine,
) {
    enum class LoadState {
        UNLOADED,
        LOADING,
        READY,
        FAILED,
    }

    data class OperationResult(
        val success: Boolean,
        val errorMessage: String = "",
    )

    data class DetectionOutcome(
        val success: Boolean,
        val stats: NativeInferenceStats?,
        val errorMessage: String,
    )

    interface NativeEngine {
        fun load(assets: Any): Boolean

        fun detect(bitmap: Any): Boolean

        fun latestStats(): NativeInferenceStats?

        fun release()

        fun errorMessage(): String?
    }

    fun interface TaskExecutor {
        fun execute(task: () -> Unit)
    }

    private var state = LoadState.UNLOADED
    private var engine: NativeEngine? = null

    fun ensureLoaded(assets: Any, callback: (OperationResult) -> Unit) {
        executor.execute {
            callback(ensureLoadedOnExecutor(assets))
        }
    }

    fun detect(
        assets: Any,
        bitmap: Any,
        traceLabel: String = "",
        callback: (DetectionOutcome) -> Unit,
    ) {
        val queuedAtNs = System.nanoTime()
        logTiming(traceLabel, "queued")
        executor.execute {
            val workerStartedAtNs = System.nanoTime()
            val queueWaitMs = elapsedMs(queuedAtNs, workerStartedAtNs)
            logTiming(traceLabel, "worker_start queueWaitMs=$queueWaitMs")

            val ensureStartedAtNs = System.nanoTime()
            val loadResult = ensureLoadedOnExecutor(assets)
            val ensureLoadedMs = elapsedMs(ensureStartedAtNs)
            if (!loadResult.success) {
                logTiming(
                    traceLabel,
                    "finish success=false queueWaitMs=$queueWaitMs ensureLoadedMs=$ensureLoadedMs totalWorkerMs=${elapsedMs(workerStartedAtNs)} error=${loadResult.errorMessage}",
                )
                callback(DetectionOutcome(false, null, loadResult.errorMessage))
                return@execute
            }

            val currentEngine = requireNotNull(engine)
            val nativeStartedAtNs = System.nanoTime()
            val success = currentEngine.detect(bitmap)
            val nativeDetectMs = elapsedMs(nativeStartedAtNs)
            val statsStartedAtNs = System.nanoTime()
            val stats = currentEngine.latestStats()
            val latestStatsMs = elapsedMs(statsStartedAtNs)
            logTiming(
                traceLabel,
                "finish success=$success queueWaitMs=$queueWaitMs ensureLoadedMs=$ensureLoadedMs nativeDetectMs=$nativeDetectMs latestStatsMs=$latestStatsMs totalWorkerMs=${elapsedMs(workerStartedAtNs)} statsInferenceMs=${stats?.inferenceTimeMs ?: -1L} detectionCount=${stats?.detectionCount ?: -1}",
            )
            callback(
                DetectionOutcome(
                    success = success,
                    stats = stats,
                    errorMessage = if (success) "" else currentEngine.errorMessage().orEmpty(),
                ),
            )
        }
    }

    fun executeWithLoaded(
        assets: Any,
        callback: (NativeEngine?, OperationResult) -> Unit,
    ) {
        executor.execute {
            val loadResult = ensureLoadedOnExecutor(assets)
            callback(if (loadResult.success) engine else null, loadResult)
        }
    }

    fun release(callback: (OperationResult) -> Unit = {}) {
        executor.execute {
            engine?.release()
            engine = null
            state = LoadState.UNLOADED
            callback(OperationResult(true))
        }
    }

    private fun ensureLoadedOnExecutor(assets: Any): OperationResult {
        if (state == LoadState.READY) {
            return OperationResult(true)
        }

        state = LoadState.LOADING
        val currentEngine = engine ?: engineFactory().also { engine = it }
        val loaded = currentEngine.load(assets)
        state = if (loaded) LoadState.READY else LoadState.FAILED
        return OperationResult(
            success = loaded,
            errorMessage = if (loaded) "" else currentEngine.errorMessage().orEmpty(),
        )
    }

    companion object {
        private val sharedExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "local-ncnn-coordinator")
        }

        internal fun executor(): TaskExecutor = TaskExecutor { task ->
            sharedExecutor.execute(task)
        }

        private const val TAG = "LocalNcnnCoordinator"

        private fun elapsedMs(startNs: Long, endNs: Long = System.nanoTime()): Long {
            return (endNs - startNs) / 1_000_000L
        }

        private fun logTiming(traceLabel: String, message: String) {
            if (traceLabel.isNotBlank()) {
                Log.i(TAG, "timing trace=$traceLabel $message")
            }
        }
    }
}
