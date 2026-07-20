package com.example.galaxymirror

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object CrashDiagnostics {
    private const val LAST_CRASH_FILE = "galaxy_mirror_last_crash.txt"
    private const val LAST_CAUGHT_EXCEPTION_FILE = "galaxy_mirror_last_caught_exception.txt"
    private const val RECENT_EVENTS_FILE = "galaxy_mirror_recent_events.txt"
    private const val PROCESS_EXIT_FILE = "galaxy_mirror_process_exit_history.txt"
    private const val MAX_EVENT_LINES = 120
    private const val MAX_TRACE_CHARS = 48_000
    private val installed = AtomicBoolean(false)
    private val lock = Any()
    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val dir = context.filesDir
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordUnhandledException(
                    dir = dir,
                    threadName = thread.name ?: "unknown",
                    throwable = throwable
                )
            } finally {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable)
                } else {
                    exitProcess(2)
                }
            }
        }
    }

    fun recordEvent(context: Context, message: String) {
        recordEvent(context.filesDir, message)
    }

    private fun recordEventSync(dir: File, message: String) {
        val file = File(dir, RECENT_EVENTS_FILE)
        val existing = if (file.exists()) file.readLines() else emptyList()
        val nextLines = (existing + "${timestamp()} $message").takeLast(MAX_EVENT_LINES)
        file.writeText(nextLines.joinToString(separator = "\n", postfix = "\n"))
    }

    fun recordEvent(dir: File, message: String) {
        logExecutor.submit {
            synchronized(lock) {
                recordEventSync(dir, message)
            }
        }
    }

    fun recordCaughtException(dir: File, label: String, throwable: Throwable) {
        val threadName = Thread.currentThread().name ?: "unknown"
        logExecutor.submit {
            synchronized(lock) {
                val message = "$label caught ${throwable.javaClass.name}: ${throwable.message.orEmpty()}"
                recordEventSync(dir, message)
                File(dir, LAST_CAUGHT_EXCEPTION_FILE).writeText(
                    buildExceptionReport(
                        title = "LAST CAUGHT EXCEPTION",
                        threadName = threadName,
                        throwable = throwable,
                        label = label
                    )
                )
            }
        }
    }

    fun recordUnhandledException(dir: File, threadName: String, throwable: Throwable) {
        synchronized(lock) {
            recordEventSync(dir, "Unhandled exception on $threadName: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            File(dir, LAST_CRASH_FILE).writeText(
                buildExceptionReport(
                    title = "LAST UNHANDLED EXCEPTION",
                    threadName = threadName,
                    throwable = throwable,
                    label = "process crash"
                )
            )
        }
    }

    fun recordProcessExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exitReasons = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        if (exitReasons.isEmpty()) return

        val dir = context.filesDir
        val packageName = context.packageName

        logExecutor.submit {
            synchronized(lock) {
                val report = buildString {
                    appendLine("=== PROCESS EXIT HISTORY ===")
                    appendLine("Captured at: ${timestamp()}")
                    appendLine("Package: $packageName")
                    appendLine()
                    exitReasons.forEachIndexed { index, info ->
                        appendLine("#${index + 1}")
                        appendLine("timestamp=${timestamp(info.timestamp)}")
                        appendLine("processName=${info.processName}")
                        appendLine("reason=${reasonName(info.reason)} (${info.reason})")
                        appendLine("status=${info.status}")
                        appendLine("importance=${info.importance}")
                        appendLine("description=${info.description.orEmpty()}")
                        readTrace(info)?.let { trace ->
                            appendLine("trace:")
                            appendLine(trace.take(MAX_TRACE_CHARS))
                        }
                        appendLine()
                    }
                }
                File(dir, PROCESS_EXIT_FILE).writeText(report)
            }
        }
    }

    fun readDebugReport(dir: File): String {
        return synchronized(lock) {
            buildString {
                appendSection(File(dir, LAST_CRASH_FILE), "No saved crash.")
                appendLine()
                appendSection(File(dir, LAST_CAUGHT_EXCEPTION_FILE), "No saved caught exception.")
                appendLine()
                appendSection(File(dir, RECENT_EVENTS_FILE), "No recent events.")
                appendLine()
                appendSection(File(dir, PROCESS_EXIT_FILE), "No process exit history.")
            }
        }
    }

    fun clearCrash(dir: File) {
        synchronized(lock) {
            File(dir, LAST_CRASH_FILE).delete()
            File(dir, LAST_CAUGHT_EXCEPTION_FILE).delete()
        }
    }

    fun flushExecutorForTesting() {
        logExecutor.submit { }.get()
    }

    private fun StringBuilder.appendSection(file: File, emptyMessage: String) {
        if (file.exists()) {
            appendLine(file.readText())
        } else {
            appendLine(emptyMessage)
        }
    }

    private fun buildExceptionReport(
        title: String,
        threadName: String,
        throwable: Throwable,
        label: String
    ): String {
        return buildString {
            appendLine("=== $title ===")
            appendLine("Captured at: ${timestamp()}")
            appendLine("Label: $label")
            appendLine("Thread: $threadName")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message.orEmpty()}")
            appendLine()
            appendLine(stackTraceString(throwable).take(MAX_TRACE_CHARS))
        }
    }

    private fun stackTraceString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun readTrace(info: ApplicationExitInfo): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.traceInputStream?.bufferedReader()?.use { it.readText() }
        } else {
            null
        }
    }

    private fun reasonName(reason: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (reason) {
                ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
                ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
                ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
                ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
                ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
                else -> "UNKNOWN"
            }
        } else {
            "UNKNOWN"
        }
    }

    // SimpleDateFormat is not thread-safe and is expensive to construct; reuse one per thread
    // instead of allocating a new formatter on every recordEvent/recordCaughtException call.
    private val timestampFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US) }

    private fun timestamp(timeMillis: Long = System.currentTimeMillis()): String {
        return timestampFormat.get().format(Date(timeMillis))
    }
}
