package io.aatricks.easyreader.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight local crash recorder. Captures uncaught exceptions into a private
 * `crashes.log` under filesDir so a user / developer who reproduces a crash can
 * read the last few traces with `adb shell run-as <pkg> cat files/crashes.log`
 * without shipping an external SDK or DSN.
 *
 * Cap at MAX_ENTRIES so the log file cannot grow unboundedly across crashes.
 * Always re-throws to the previous handler so the OS still terminates the process
 * and ANRs are reported as usual.
 */
object CrashRecorder {

    private const val FILE_NAME = "crashes.log"
    private const val MAX_ENTRIES = 5
    private const val ENTRY_SEPARATOR = "----- crash -----"
    private const val TAG = "CrashRecorder"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, thread, throwable) }
                .onFailure { Log.w(TAG, "failed to record crash", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().use { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
            sw.toString()
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            .format(Date())
        val packageName = context.packageName
        val versionName = runCatching {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        val entry = buildString {
            appendLine(ENTRY_SEPARATOR)
            appendLine("time=$timestamp version=$versionName thread=${thread.name}")
            appendLine(stackTrace)
        }

        val file = File(context.filesDir, FILE_NAME)
        val existing = if (file.exists()) file.readText() else ""
        val combined = existing + entry
        val trimmed = trimToLastEntries(combined, MAX_ENTRIES)
        file.writeText(trimmed)
    }

    private fun trimToLastEntries(content: String, maxEntries: Int): String {
        val parts = content.split(ENTRY_SEPARATOR).filter { it.isNotBlank() }
        val tail = parts.takeLast(maxEntries)
        return tail.joinToString(separator = "") { "$ENTRY_SEPARATOR\n${it.trimStart('\n')}" }
    }
}
