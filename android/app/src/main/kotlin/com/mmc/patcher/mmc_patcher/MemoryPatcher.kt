package com.mmc.patcher.mmc_patcher

import java.io.File
import java.io.RandomAccessFile

internal data class Patch(
    val name: String,
    val offset: Long,
    val hex: String,
)

/** ARM64 byte patches from lgl_project/jni/Main.cpp, targeting libcocos2dcpp.so. */
internal val PATCHES = listOf(
    Patch("God Mode 1", 0x67cffc, "C0 03 5F D6"),
    Patch("God Mode 2", 0x677130, "C0 03 5F D6"),
    Patch("God Mode 3", 0x95e4c0, "C0 03 5F D6"),
    Patch("God Mode 4", 0x8e2864, "C0 03 5F D6"),
    Patch("Infinite Ammo", 0x947a20, "00 A6 8E 52 C0 03 5F D6"),
    Patch("Infinite Clip", 0x947954, "00 A6 8E 52 C0 03 5F D6"),
)

internal object MemoryPatcher {
    private const val TARGET_PACKAGE = "com.appsomniacs.mmc"
    private const val TARGET_LIB = "libcocos2dcpp.so"

    private val originals = HashMap<Long, ByteArray>()

    /** Mirrors the injector's getProcessId(): scan /proc for a cmdline match. */
    fun findPid(): Int? {
        val procs = File("/proc").listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d+")) }
            ?: return null
        for (dir in procs) {
            try {
                val cmd = File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                if (cmd.contains(TARGET_PACKAGE)) return dir.name.toInt()
            } catch (_: Exception) {
                // Process vanished between listing and read.
            }
        }
        return null
    }

    /** Mirrors the maps scan: first mapping line containing the library name. */
    fun findModuleBase(pid: Int, lib: String = TARGET_LIB): Long? {
        var result: Long? = null
        try {
            File("/proc/$pid/maps").forEachLine { line ->
                if (result == null && line.contains(lib)) {
                    val dash = line.indexOf('-')
                    if (dash > 0) result = line.substring(0, dash).toLong(16)
                }
            }
        } catch (_: Exception) {
            // Unreadable maps.
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
        val out = mutableListOf<Byte>()
        var i = 0
        while (i + 1 < clean.length) {
            out.add(clean.substring(i, i + 2).toInt(16).toByte())
            i += 2
        }
        return out.toByteArray()
    }

    private fun writeDirect(pid: Int, address: Long, bytes: ByteArray): Boolean = try {
        RandomAccessFile("/proc/$pid/mem", "rw").use { mem ->
            mem.seek(address)
            mem.write(bytes)
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Root fallback: printf octal bytes into dd at the virtual address. */
    private fun writeViaShell(pid: Int, address: Long, bytes: ByteArray): Boolean {
        val octal = bytes.joinToString("") { "\\${(it.toInt() and 0xff).toString(8).padStart(3, '0')}" }
        val cmd = "printf '$octal' | dd of=/proc/$pid/mem bs=1 seek=$address conv=notrunc 2>/dev/null"
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun writeMemory(pid: Int, address: Long, bytes: ByteArray): Boolean =
        writeDirect(pid, address, bytes) || writeViaShell(pid, address, bytes)

    private fun readOriginal(pid: Int, address: Long, length: Int): ByteArray? = try {
        RandomAccessFile("/proc/$pid/mem", "r").use { mem ->
            mem.seek(address)
            val buf = ByteArray(length)
            if (mem.read(buf) == length) buf else null
        }
    } catch (_: Exception) {
        null
    }

    fun attach(): Pair<Int, Long>? {
        val pid = findPid() ?: return null
        val base = findModuleBase(pid) ?: return null
        return pid to base
    }

    fun applyPatch(pid: Int, base: Long, patch: Patch, enable: Boolean): Boolean {
        val address = base + patch.offset
        val bytes = hexToBytes(patch.hex)
        return if (enable) {
            readOriginal(pid, address, bytes.size)?.let { originals[address] = it }
            writeMemory(pid, address, bytes)
        } else {
            val original = originals.remove(address) ?: return false
            writeMemory(pid, address, original)
        }
    }
}
