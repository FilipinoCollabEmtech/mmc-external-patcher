package com.mmc.patcher.mmc_patcher

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot

internal data class Patch(val name: String, val offset: Long, val hex: String)
internal data class PatchGroup(val name: String, val patches: List<Patch>)

/** ARM64 byte patches from lgl_project/jni/Main.cpp, targeting libcocos2dcpp.so. */
internal val PATCH_GROUPS = listOf(
    // Four damage functions cover humanoid, hawk, worm and soldier controllers.
    // They are one user-facing toggle because disabling any single one leaves other
    // entity types able to deal damage.
    PatchGroup("God Mode", listOf(
        Patch("Humanoid damage", 0x67cffcL, "C0 03 5F D6"),
        Patch("Hawk damage", 0x677130L, "C0 03 5F D6"),
        Patch("Worm damage", 0x95e4c0L, "C0 03 5F D6"),
        Patch("Soldier damage", 0x8e2864L, "C0 03 5F D6"),
    )),
    PatchGroup("Infinite Ammo", listOf(
        Patch("Clip", 0x947954L, "00 A6 8E 52 C0 03 5F D6"),
        Patch("Reserve", 0x947a20L, "00 A6 8E 52 C0 03 5F D6"),
    )),
    PatchGroup("Security Bypass", listOf(
        Patch("Hide H4X Alert", 0x83aea8L, "C0 03 5F D6"),
        Patch("Ballistics", 0x85e0b8L, "20 00 80 D2 C0 03 5F D6"),
    )),
)

internal object MemoryPatcher {
    private const val TARGET_PACKAGE = "com.appsomniacs.mmc"
    private const val TARGET_LIB = "libcocos2dcpp.so"

    // Joypad::getFireAngle() returns [joypad + 0x180]. We replace the 24-byte
    // function with ADR x8,#12 / LDR s0,[x8] / RET / <float> / NOPs, so the game
    // reads the angle we keep writing at +12.
    private const val FIRE_ANGLE_OFFSET = 0x6888f0L

    // SoldierManager::getLocalController() -> **(base + 0x13bec80)
    private const val LOCAL_CONTROLLER_GLOBAL = 0x13bec80L
    // Remote players live in a CCDictionary at *(base + 0x13bf260)
    private const val REMOTE_DICT_GLOBAL = 0x13bf260L

    private val originals = HashMap<Long, ByteArray>()
    private var aimThread: Thread? = null
    @Volatile private var aimRunning = false

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

    fun attach(): Pair<Int, Long>? {
        val pid = findPid() ?: return null
        val base = findModuleBase(pid) ?: return null
        return pid to base
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

    private fun openMem(pid: Int, mode: String): RandomAccessFile? = try {
        RandomAccessFile("/proc/$pid/mem", mode)
    } catch (_: Exception) {
        null
    }

    private fun readBytes(pid: Int, address: Long, length: Int): ByteArray? {
        val mem = openMem(pid, "r") ?: return null
        return try {
            mem.seek(address)
            val buf = ByteArray(length)
            if (mem.read(buf) == length) buf else null
        } catch (_: Exception) {
            null
        } finally {
            mem.close()
        }
    }

    private fun readLong(pid: Int, address: Long): Long? =
        readBytes(pid, address, 8)?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long
        }

    private fun readDouble(pid: Int, address: Long): Double? =
        readBytes(pid, address, 8)?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).double
        }

    private fun writeBytes(pid: Int, address: Long, bytes: ByteArray): Boolean {
        val mem = openMem(pid, "rw") ?: return writeViaShell(pid, address, bytes)
        return try {
            mem.seek(address)
            mem.write(bytes)
            true
        } catch (_: Exception) {
            mem.close()
            writeViaShell(pid, address, bytes)
        } finally {
            runCatching { mem.close() }
        }
    }

    private fun writeFloat(pid: Int, address: Long, value: Float) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        writeBytes(pid, address, buf)
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

    fun applyGroup(pid: Int, base: Long, group: PatchGroup, enable: Boolean): Boolean {
        var allOk = true
        for (patch in group.patches) {
            val address = base + patch.offset
            val bytes = hexToBytes(patch.hex)
            if (enable) {
                readBytes(pid, address, bytes.size)?.let { originals[address] = it }
                if (!writeBytes(pid, address, bytes)) allOk = false
            } else {
                val original = originals.remove(address)
                if (original == null || !writeBytes(pid, address, original)) allOk = false
            }
        }
        return allOk
    }

    private fun localController(pid: Int, base: Long): Long? {
        val sm = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL) ?: return null
        if (sm == 0L) return null
        return readLong(pid, sm)
    }

    private fun remoteControllers(pid: Int, base: Long): List<Long> {
        val dict = readLong(pid, base + REMOTE_DICT_GLOBAL) ?: return emptyList()
        if (dict == 0L) return emptyList()
        var elem = readLong(pid, dict + 0x18) ?: return emptyList()
        val out = mutableListOf<Long>()
        var guard = 0
        while (elem != 0L && guard++ < 128) {
            readLong(pid, elem + 0x108)?.let { if (it != 0L) out.add(it) }
            elem = readLong(pid, elem + 0x120) ?: 0L
        }
        return out
    }

    /** SoldierController::getBodyPosition(): [ctrl+0x1a8] -> body, [body+0x30/0x38] = x/y doubles. */
    private fun bodyPosition(pid: Int, controller: Long): Pair<Double, Double>? {
        val body = readLong(pid, controller + 0x1a8) ?: return null
        if (body == 0L) return null
        val x = readDouble(pid, body + 0x30) ?: return null
        val y = readDouble(pid, body + 0x38) ?: return null
        return x to y
    }

    private fun nearestEnemyAngle(pid: Int, base: Long): Float? {
        val lc = localController(pid, base) ?: return null
        val origin = bodyPosition(pid, lc) ?: return null
        var best = 4000.0
        var angle: Float? = null
        for (enemy in remoteControllers(pid, base)) {
            val pos = bodyPosition(pid, enemy) ?: continue
            val dx = pos.first - origin.first
            val dy = pos.second - origin.second
            val dist = hypot(dx, dy)
            if (dist < 10.0 || dist >= best) continue
            best = dist
            var deg = (atan2(-dy, dx) * 180.0 / Math.PI).toFloat()
            if (deg < 0f) deg += 360f
            angle = deg
        }
        return angle
    }

    fun toggleAimlock(enable: Boolean): Boolean {
        if (enable) {
            val pid = findPid() ?: return false
            val base = findModuleBase(pid) ?: return false
            val address = base + FIRE_ANGLE_OFFSET
            val original = readBytes(pid, address, 24) ?: return false
            originals[address] = original

            val patch = ByteArray(24)
            // adr x8, #12
            patch[0] = 0x68; patch[1] = 0x00; patch[2] = 0x00; patch[3] = 0x10
            // ldr s0, [x8]
            patch[4] = 0x00; patch[5] = 0x01; patch[6] = 0x40; patch[7] = 0xBD
            // ret
            patch[8] = 0xC0; patch[9] = 0x03; patch[10] = 0x5F; patch[11] = 0xD6
            // NOP padding
            for (i in 16 until 24) {
                patch[i] = when (i % 4) {
                    0 -> 0x1F.toByte(); 1 -> 0x20.toByte(); 2 -> 0x03.toByte(); else -> 0xD5.toByte()
                }
            }
            if (!writeBytes(pid, address, patch)) return false

            aimRunning = true
            aimThread = Thread {
                while (aimRunning) {
                    val p = findPid()
                    val b = p?.let { findModuleBase(it) }
                    if (p != null && b != null) {
                        nearestEnemyAngle(p, b)?.let { writeFloat(p, b + FIRE_ANGLE_OFFSET + 12, it) }
                    }
                    try {
                        Thread.sleep(16)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }.apply { isDaemon = true; start() }
            return true
        } else {
            aimRunning = false
            aimThread?.interrupt()
            try {
                aimThread?.join(200)
            } catch (_: InterruptedException) {
                // Ignore.
            }
            aimThread = null
            val pid = findPid() ?: return false
            val base = findModuleBase(pid) ?: return false
            val original = originals.remove(base + FIRE_ANGLE_OFFSET) ?: return false
            return writeBytes(pid, base + FIRE_ANGLE_OFFSET, original)
        }
    }
}
