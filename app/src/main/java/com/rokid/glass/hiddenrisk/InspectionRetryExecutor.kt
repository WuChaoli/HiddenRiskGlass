package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import okhttp3.Call

internal object InspectionRequestRetryPolicy {
    const val MAX_ATTEMPTS = 4
    private val RETRY_DELAYS_MS = longArrayOf(1000L, 2000L, 3000L)

    fun delayBeforeNextAttempt(attemptCount: Int): Long? {
        if (attemptCount < 1 || attemptCount >= MAX_ATTEMPTS) {
            return null
        }
        return RETRY_DELAYS_MS[attemptCount - 1]
    }
}

internal data class RetryOutcome(
    val success: Boolean,
    val message: String? = null,
    val attemptCount: Int,
)

internal data class RetryAttemptResult(
    val success: Boolean,
    val message: String? = null,
)

class RetryRequestHandle {
    @Volatile
    private var canceled = false
    private val calls = mutableListOf<Call>()
    private val scheduledTasks = mutableListOf<Pair<Handler, Runnable>>()

    fun bind(call: Call) {
        synchronized(calls) {
            if (canceled) {
                call.cancel()
            } else {
                calls += call
            }
        }
    }

    fun schedule(
        handler: Handler,
        runnable: Runnable,
        delayMillis: Long,
    ): Boolean {
        synchronized(scheduledTasks) {
            if (canceled) {
                return false
            }
            scheduledTasks += handler to runnable
        }
        handler.postDelayed(runnable, delayMillis)
        return true
    }

    fun markTaskFinished(
        handler: Handler,
        runnable: Runnable,
    ) {
        synchronized(scheduledTasks) {
            scheduledTasks.remove(handler to runnable)
        }
    }

    fun cancel() {
        val callSnapshot = synchronized(calls) {
            canceled = true
            calls.toList()
        }
        val taskSnapshot = synchronized(scheduledTasks) {
            scheduledTasks.toList().also { scheduledTasks.clear() }
        }
        callSnapshot.forEach(Call::cancel)
        taskSnapshot.forEach { (handler, runnable) ->
            handler.removeCallbacks(runnable)
        }
    }

    fun isCanceled(): Boolean = canceled
}

internal object InspectionRetryExecutor {
    private val retryHandler = Handler(Looper.getMainLooper())

    fun execute(
        label: String,
        handle: RetryRequestHandle = RetryRequestHandle(),
        attemptBlock: (attempt: Int, completion: (RetryAttemptResult) -> Unit) -> Call?,
        onComplete: (RetryOutcome) -> Unit,
    ): RetryRequestHandle {
        fun executeAttempt(attempt: Int) {
            if (handle.isCanceled()) {
                return
            }
            fun handleAttemptResult(result: RetryAttemptResult) {
                if (handle.isCanceled()) {
                    return
                }
                if (result.success) {
                    onComplete(
                        RetryOutcome(
                            success = true,
                            message = result.message,
                            attemptCount = attempt,
                        ),
                    )
                    return
                }
                val delayMillis = InspectionRequestRetryPolicy.delayBeforeNextAttempt(attempt)
                if (delayMillis == null) {
                    onComplete(
                        RetryOutcome(
                            success = false,
                            message = result.message,
                            attemptCount = attempt,
                        ),
                    )
                    return
                }
                val retryRunnable = object : Runnable {
                    override fun run() {
                        handle.markTaskFinished(retryHandler, this)
                        executeAttempt(attempt + 1)
                    }
                }
                if (!handle.schedule(retryHandler, retryRunnable, delayMillis)) {
                    return
                }
            }
            val call = attemptBlock(attempt, ::handleAttemptResult)
            call?.let(handle::bind)
        }

        executeAttempt(attempt = 1)
        return handle
    }
}
