package com.mmc.patcher.mmc_patcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log plus logcat tail, copied to the clipboard for crash reports. */
internal object PatchLogger {
    private const val MAX_EVENTS = 200
    private val events = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var installed = false

    private fun now(): String = timeFormat.format(Date())

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            "\n${sw}"
        }.orEmpty()
        val line = "[${now()}] $tag: $message$detail"
        synchronized(events) {
            events.add(line)
            while (events.size > MAX_EVENTS) events.removeAt(0)
        }
        android.util.Log.d("MMCPatcher", line)
    }

    fun installCrashHandler(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            log("CRASH", "thread=${thread.name}\n$sw")
            runCatching { File(app.filesDir, "crash.txt").writeText(collect(app)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== MMC Patcher logs ===")
        sb.appendLine("time: ${now()}")
        sb.appendLine("device: ${Build.BRAND} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("app: ${context.packageName}")
        sb.appendLine()
        sb.appendLine("--- app events ---")
        synchronized(events) { events.forEach { sb.appendLine(it) } }
        sb.appendLine()
        sb.appendLine("--- logcat tail ---")
        sb.appendLine(readLogcat())
        val text = sb.toString()
        runCatching { File(context.filesDir, "mmc_patcher_log.txt").writeText(text) }
        return text
    }

    fun copyToClipboard(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("MMC Patcher logs", collect(context)))
        true
    } catch (e: Exception) {
        log("CLIPBOARD", "copy failed: ${e.message}")
        false
    }

    private fun readLogcat(): String {
        val commands = listOf(
            arrayOf("su", "-c", "logcat -d -t 250"),
            arrayOf("logcat", "-d", "-t", "250"),
        )
        for (command in commands) {
            try {
                val process = Runtime.getRuntime().exec(command)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (output.isNotBlank()) return output
            } catch (_: Exception) {
                // Try the next command.
            }
        }
        return "(logcat unavailable)"
    }
}
