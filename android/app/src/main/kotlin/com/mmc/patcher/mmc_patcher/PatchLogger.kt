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
    private const val MAX_BLOCKS = 4
    private val events = mutableListOf<String>()
    /** Verbatim multi-line blocks (e.g. a capture dump) that must not be collapsed. */
    private val blocks = mutableListOf<Pair<String, String>>()
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

    /** Store a full dump verbatim so it lands in [collect] without repeat collapsing. */
    fun appendBlock(title: String, body: String) {
        synchronized(events) {
            blocks.removeAll { it.first == title }
            blocks.add(title to body)
            while (blocks.size > MAX_BLOCKS) blocks.removeAt(0)
        }
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

    /** Collapses consecutive repeats (timestamps/numbers ignored) into "xN". */
    private fun collapse(lines: List<String>): List<String> {
        fun key(line: String): String {
            var s = Regex("""^\[\d\d-\d\d \d\d:\d\d:\d\d\.\d+\]\s+""").replace(line, "")
            s = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+""").replace(s, "")
            s = Regex("0x[0-9a-fA-F]+").replace(s, "0xADDR")
            return Regex("""\b\d+\b""").replace(s, "N").trim()
        }
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val k = key(lines[i])
            var j = i + 1
            while (j < lines.size && key(lines[j]) == k) j++
            val count = j - i
            out += lines[i] + if (count > 1) "  [x$count]" else ""
            i = j
        }
        return out
    }

    fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== MMC Patcher logs ===")
        sb.appendLine("time: ${now()}")
        sb.appendLine("device: ${Build.BRAND} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("app: ${context.packageName}")
        sb.appendLine()
        sb.appendLine("--- app events ---")
        synchronized(events) {
            collapse(events.toList()).forEach { sb.appendLine(it) }
            for ((title, body) in blocks) {
                sb.appendLine()
                sb.appendLine("--- $title ---")
                sb.appendLine(body)
            }
        }
        sb.appendLine()
        val logcat = readLogcat().lines().filter {
            !it.contains("avc: denied") && !it.contains("type=1400 audit")
        }
        val crashBuffer = runCommand(arrayOf("su", "-c", "logcat -b crash -d -t 200"))
        val tombstone = runCommand(
            arrayOf("su", "-c", "f=\$(ls -t /data/tombstones/tombstone_* 2>/dev/null | head -1); [ -n \"\$f\" ] && head -120 \"\$f\""),
        )
        val evidence = (logcat + crashBuffer.lines() + tombstone.lines())
        val crashPattern = Regex(
            """FATAL EXCEPTION|signal \d+|SIGSEGV|SIGABRT|SIGBUS|crash_dump|debuggerd|tombstone|backtrace|#\d\d pc|F libc|F DEBUG|ANR|Killed process|lowmemorykiller|MMCPatcher|pid: \d+, tid:""",
            RegexOption.IGNORE_CASE,
        )
        val highlights = evidence.filter { crashPattern.containsMatchIn(it) }.takeLast(80)
        sb.appendLine("--- crash highlights ---")
        if (highlights.isEmpty()) sb.appendLine("(none found)") else highlights.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("--- crash buffer ---")
        sb.appendLine(crashBuffer.ifBlank { "(unavailable)" })
        sb.appendLine()
        sb.appendLine("--- tombstone ---")
        sb.appendLine(tombstone.ifBlank { "(unavailable)" })
        sb.appendLine()
        sb.appendLine("--- dmesg tail ---")
        sb.appendLine(readDmesg())
        sb.appendLine()
        sb.appendLine("--- logcat tail (last 120 lines) ---")
        collapse(logcat.takeLast(120)).forEach { sb.appendLine(it) }
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

    private fun runCommand(command: Array<String>): String = try {
        val process = Runtime.getRuntime().exec(command)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        output.ifBlank { process.errorStream.bufferedReader().use { it.readText() } }
    } catch (_: Exception) {
        ""
    }

    private fun readLogcat(): String {
        val commands = listOf(
            arrayOf("su", "-c", "logcat -b all -d -t 400"),
            arrayOf("logcat", "-b", "main,system,crash", "-d", "-t", "400"),
            arrayOf("logcat", "-d", "-t", "400"),
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

    private fun readDmesg(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "dmesg | tail -80"))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (output.isNotBlank()) output else "(dmesg empty)"
    } catch (_: Exception) {
        "(dmesg unavailable)"
    }
}
