package com.rokid.glass.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 应用内诊断日志落盘工具。
 *
 * 默认只在调试构建中启用，写入 app 私有外部目录，便于通过 adb 拉取现场日志。
 */
object AppFileLogger {
    private const val MAX_LOG_FILE_BYTES = 2 * 1024 * 1024L
    private const val MAX_APP_LOG_FILES = 7
    private const val LOG_DIR_NAME = "logs"
    private const val APP_LOG_PREFIX = "app-"
    private const val APP_LOG_SUFFIX = ".log"
    private const val CRASH_LOG_PREFIX = "crash-"

    private val lineTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val fileDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val crashFileDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
    }

    @Volatile
    private var enabled = false

    @Volatile
    private var logDir: File? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppFileLogger").apply { isDaemon = true }
    }

    fun init(context: Context, enabled: Boolean) {
        this.enabled = enabled
        logDir = context.getExternalFilesDir(LOG_DIR_NAME) ?: File(context.filesDir, LOG_DIR_NAME)
        if (enabled) {
            executor.execute {
                ensureLogDir()
                trimOldAppLogs()
            }
        }
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        Log.d(tag, message, throwable)
        writeAsync("D", tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        Log.i(tag, message, throwable)
        writeAsync("I", tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeAsync("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeAsync("E", tag, message, throwable)
    }

    fun writeCrash(throwable: Throwable) {
        if (!enabled) return
        val dir = logDir ?: return
        runCatching {
            ensureLogDir()
            val now = Date()
            val file = File(dir, "$CRASH_LOG_PREFIX${crashFileDateFormat.get()!!.format(now)}$APP_LOG_SUFFIX")
            FileWriter(file, true).use { writer ->
                writer.append(lineTimeFormat.get()!!.format(now))
                    .append(" E/Crash uncaught exception")
                    .append('\n')
                    .append(stackTraceToString(throwable))
                    .append('\n')
            }
        }.onFailure { error ->
            Log.e("AppFileLogger", "writeCrash failed", error)
        }
    }

    fun flush(timeoutMs: Long = 1000L) {
        if (!enabled) return
        runCatching {
            executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.onFailure { error ->
            Log.w("AppFileLogger", "flush failed: ${error.message}")
        }
    }

    private fun writeAsync(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!enabled) return
        executor.execute {
            writeLine(level, tag, message, throwable)
        }
    }

    private fun writeLine(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return
        runCatching {
            ensureLogDir()
            val file = resolveAppLogFile(Date())
            FileWriter(file, true).use { writer ->
                writer.append(lineTimeFormat.get()!!.format(Date()))
                    .append(' ')
                    .append(level)
                    .append('/')
                    .append(tag)
                    .append(' ')
                    .append(message)
                    .append('\n')
                if (throwable != null) {
                    writer.append(stackTraceToString(throwable)).append('\n')
                }
            }
            trimOldAppLogs()
        }.onFailure { error ->
            Log.e("AppFileLogger", "writeLine failed dir=${dir.absolutePath}", error)
        }
    }

    private fun resolveAppLogFile(now: Date): File {
        val dir = logDir ?: error("logDir not initialized")
        val todayFile = File(dir, "$APP_LOG_PREFIX${fileDateFormat.get()!!.format(now)}$APP_LOG_SUFFIX")
        if (!todayFile.exists() || todayFile.length() < MAX_LOG_FILE_BYTES) {
            return todayFile
        }
        var index = 1
        while (true) {
            val rotatedFile = File(dir, "$APP_LOG_PREFIX${fileDateFormat.get()!!.format(now)}-$index$APP_LOG_SUFFIX")
            if (!rotatedFile.exists() || rotatedFile.length() < MAX_LOG_FILE_BYTES) {
                return rotatedFile
            }
            index++
        }
    }

    private fun ensureLogDir() {
        logDir?.takeIf { !it.exists() }?.mkdirs()
    }

    private fun trimOldAppLogs() {
        val dir = logDir ?: return
        val appLogs = dir.listFiles { file ->
            file.isFile && file.name.startsWith(APP_LOG_PREFIX) && file.name.endsWith(APP_LOG_SUFFIX)
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        appLogs.drop(MAX_APP_LOG_FILES).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
