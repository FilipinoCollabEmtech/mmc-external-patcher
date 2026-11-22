package com.mmc.patcher.mmc_patcher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-button capture of everything observable during a multiplayer join.
 * Root shell only — no code caves, so it cannot destabilise the live game.
 */
internal object JoinCapture {
    private const val TARGET = "com.appsomniacs.mmc"
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile private var active = false
    private var dir: File? = null
    private var logcat: Process? = null
    private var sampler: Thread? = null
    private var startTime = 0L

    fun isActive(): Boolean = active

    fun start(context: Context): String {
        if (active) return "already running"
        val root = File(context.getExternalFilesDir(null), "join_captures")
        root.mkdirs()
        val d = File(root, "join_" + stamp.format(Date()))
        d.mkdirs()
        dir = d
        startTime = System.currentTimeMillis()
        active = true

        // 1. Full logcat, continuously, in a background shell process.
        runCatching {
            logcat = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "logcat -b all -v threadtime > ${d.absolutePath}/logcat.txt 2>&1"),
            )
        }

        // 2. Sample network/socket state and token-bearing files every 500 ms.
        sampler = Thread {
            var n = 0
            while (active) {
                val pid = MemoryPatcher.findPid()
                if (pid != null) {
                    val p = d.absolutePath
                    cmd("cat /proc/$pid/net/tcp > $p/net_tcp_$n.txt 2>/dev/null")
                    cmd("cat /proc/$pid/net/udp > $p/net_udp_$n.txt 2>/dev/null")
                    cmd("cat /proc/$pid/net/tcp6 > $p/net_tcp6_$n.txt 2>/dev/null")
                    cmd("cat /proc/$pid/status > $p/status_$n.txt 2>/dev/null")
                    if (n % 4 == 0) {
                        cmd("cp -r /data/data/$TARGET/shared_prefs $p/shared_prefs_$n 2>/dev/null")
                        cmd("cp -r /data/data/$TARGET/databases $p/databases_$n 2>/dev/null")
                        cmd("cp -r /data/data/$TARGET/files $p/files_$n 2>/dev/null")
                    }
                }
                n++
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }

        PatchLogger.log("CAPTURE", "started -> ${d.absolutePath}")
        return d.absolutePath
    }

    fun stop(): String {
        if (!active) return "not running"
        active = false
        logcat?.destroy()
        logcat = null
        sampler?.interrupt()
        try {
            sampler?.join(500)
        } catch (_: InterruptedException) {
            // Ignore.
        }
        sampler = null

        val d = dir ?: return "no capture dir"
        val elapsed = System.currentTimeMillis() - startTime
        val log = File(d, "logcat.txt")
        val sb = StringBuilder()
        sb.appendLine("=== MMC MULTIPLAYER JOIN CAPTURE ===")
        sb.appendLine("dir: ${d.absolutePath}")
        sb.appendLine("duration_ms: $elapsed")
        sb.appendLine("target: $TARGET")

        val lines = runCatching { log.readLines() }.getOrDefault(emptyList())
        sb.appendLine("logcat_lines: ${lines.size}")
        sb.appendLine()
        sb.appendLine("--- endpoints / urls ---")
        val urls = Regex("""https?://[^\s"'<>\\]+""")
            .findAll(lines.joinToString("\n"))
            .map { it.value }
            .toSortedSet()
        if (urls.isEmpty()) sb.appendLine("(none in logcat)") else urls.forEach { sb.appendLine(it) }

        sb.appendLine()
        sb.appendLine("--- region / join markers ---")
        val markers = Regex("""RL:\[[^\]]*\]|SC:\d+|RC:\d+|Region[^\s]*|ranked|gameserver|quickjoin|matchmaking""", RegexOption.IGNORE_CASE)
        lines.filter { markers.containsMatchIn(it) }.takeLast(150).forEach { sb.appendLine(it) }

        sb.appendLine()
        sb.appendLine("--- distinct remote ips (from /proc/net/tcp) ---")
        val ips = mutableSetOf<String>()
        d.listFiles { f -> f.name.startsWith("net_tcp") }?.forEach { f ->
            runCatching {
                f.readLines().drop(1).forEach { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size > 2) {
                        val hex = cols[2].substringAfter(":")
                        if (hex.length == 8) {
                            val b = hex.chunked(2).reversed().map { it.toInt(16) }
                            ips.add(b.joinToString("."))
                        }
                    }
                }
            }
        }
        if (ips.isEmpty()) sb.appendLine("(none)") else ips.sorted().forEach { sb.appendLine(it) }

        sb.appendLine()
        sb.appendLine("--- files captured ---")
        d.listFiles()?.sortedBy { it.name }?.forEach { sb.appendLine("${it.name}  ${it.length()}") }

        val text = sb.toString()
        runCatching { File(d, "SUMMARY.txt").writeText(text) }
        PatchLogger.log("CAPTURE", "stopped -> ${d.absolutePath}/SUMMARY.txt")
        return text
    }

    /** Copy the last summary to the clipboard via the logger path. */
    fun lastSummary(): String = dir?.let { runCatching { File(it, "SUMMARY.txt").readText() }.getOrNull() } ?: "no capture yet"

    private fun cmd(line: String) {
        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", line)) }
    }
}
