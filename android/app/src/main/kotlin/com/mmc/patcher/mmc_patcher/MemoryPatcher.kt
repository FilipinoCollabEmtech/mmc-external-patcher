package com.mmc.patcher.mmc_patcher

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot

internal data class Patch(val name: String, val offset: Long, val hex: String)
internal data class PatchGroup(val name: String, val patches: List<Patch>)

internal const val KIND_DRONE = 1
internal const val KIND_HAWK = 2
internal const val KIND_WORM = 3
internal const val KIND_AI = 4
internal const val KIND_PLAYER = 5
internal const val KIND_LOCAL = 6

internal data class EspTarget(val x: Double, val y: Double, val kind: Int, val hp: Float)
internal data class Camera(val x: Float, val y: Float, val width: Float, val height: Float)

/** ARM64 byte patches from lgl_project/jni/Main.cpp, targeting libcocos2dcpp.so. */
internal val PATCH_GROUPS = listOf(
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

    private const val FIRE_ANGLE_OFFSET = 0x6888f0L
    private const val LOCAL_CONTROLLER_GLOBAL = 0x13bec80L
    private const val DIRECTOR_GLOBAL = 0x13ec228L

    // VTable addresses from dynsyms; object vptr = vtable + 0x10.
    private val ENTITY_VTABLES = mapOf(
        0x1334610L + 0x10 to KIND_DRONE,      // HumanoidDrone
        0x13337f0L + 0x10 to KIND_HAWK,       // HawkDrone
        0x135a960L + 0x10 to KIND_WORM,       // WormDrone
        0x1352120L + 0x10 to KIND_AI,         // SoldierAIController
        0x1353580L + 0x10 to KIND_PLAYER,     // SoldierRemoteController
        0x1352df0L + 0x10 to KIND_LOCAL,      // SoldierLocalController
        0x132d1c0L + 0x10 to KIND_DRONE,      // Enemy
    )

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
            runCatching { mem.close() }
        }
    }

    private fun readLong(pid: Int, address: Long): Long? =
        readBytes(pid, address, 8)?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long
        }

    private fun readInt(pid: Int, address: Long): Int? =
        readBytes(pid, address, 4)?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int
        }

    private fun readFloat(pid: Int, address: Long): Float? =
        readBytes(pid, address, 4)?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float
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
            writeViaShell(pid, address, bytes)
        } finally {
            runCatching { mem.close() }
        }
    }

    private fun writeFloat(pid: Int, address: Long, value: Float) {
        writeBytes(pid, address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
    }

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

    // ---- scene graph traversal (cocos2d CCNode/CCArray layouts from disassembly) ----

    private fun children(pid: Int, node: Long): List<Long> {
        val arr = readLong(pid, node + 0xb8) ?: return emptyList()
        if (arr == 0L) return emptyList()
        val data = readLong(pid, arr + 0x18) ?: return emptyList()
        if (data == 0L) return emptyList()
        val num = readInt(pid, data) ?: return emptyList()
        if (num <= 0 || num > 512) return emptyList()
        val objects = readLong(pid, data + 0x8) ?: return emptyList()
        if (objects == 0L) return emptyList()
        val buf = readBytes(pid, objects, num * 8) ?: return emptyList()
        val out = mutableListOf<Long>()
        for (i in 0 until num) {
            val v = ByteBuffer.wrap(buf, i * 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (v != 0L) out.add(v)
        }
        return out
    }

    private fun readPoint(pid: Int, node: Long): Pair<Float, Float>? {
        val x = readFloat(pid, node + 0x2c) ?: return null
        val y = readFloat(pid, node + 0x30) ?: return null
        return x to y
    }

    /** Walk the scene graph and return entity objects with a known vtable. */
    private fun findEntities(pid: Int, base: Long): List<Pair<Long, Int>> {
        val director = readLong(pid, base + DIRECTOR_GLOBAL) ?: return emptyList()
        val scene = readLong(pid, director + 0xa0) ?: return emptyList()
        val found = mutableListOf<Pair<Long, Int>>()
        val visited = HashSet<Long>()
        fun walk(node: Long, depth: Int) {
            if (depth > 8 || found.size > 400 || !visited.add(node)) return
            val vptr = readLong(pid, node) ?: return
            ENTITY_VTABLES[vptr]?.let { found.add(node to it) }
            for (child in children(pid, node)) walk(child, depth + 1)
        }
        walk(scene, 0)
        return found
    }

    /** Position chain mirrors each class's getBodyPosition() disassembly. */
    private fun entityPosition(pid: Int, obj: Long, kind: Int): Pair<Double, Double>? {
        val holder = when (kind) {
            KIND_DRONE -> readLong(pid, readLong(pid, obj + 0x200) ?: return null) ?: return null
            KIND_HAWK -> readLong(pid, readLong(pid, obj + 0x230) ?: return null) ?: return null
            KIND_WORM -> readLong(pid, readLong(pid, obj + 0x228) ?: return null) ?: return null
            else -> obj
        }
        val body = readLong(pid, holder + 0x1a8) ?: return null
        if (body == 0L) return null
        val x = readDouble(pid, body + 0x30) ?: return null
        val y = readDouble(pid, body + 0x38) ?: return null
        return x to y
    }

    fun localPosition(pid: Int, base: Long): Pair<Double, Double>? {
        val local = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)?.let { readLong(pid, it) } ?: return null
        return entityPosition(pid, local, KIND_LOCAL)
    }

    fun collectTargets(pid: Int, base: Long): List<EspTarget> {
        val out = mutableListOf<EspTarget>()
        for ((obj, kind) in findEntities(pid, base)) {
            if (kind == KIND_LOCAL) continue
            val pos = entityPosition(pid, obj, kind) ?: continue
            out.add(EspTarget(pos.first, pos.second, kind, 0f))
        }
        return out
    }

    fun camera(pid: Int, base: Long): Camera? {
        val director = readLong(pid, base + DIRECTOR_GLOBAL) ?: return null
        val width = readFloat(pid, director + 0xd0) ?: return null
        val height = readFloat(pid, director + 0xd4) ?: return null
        val scene = readLong(pid, director + 0xa0) ?: return null
        var best = -1f
        var camX = 0f
        var camY = 0f
        for (c1 in children(pid, scene)) {
            val p1 = readPoint(pid, c1) ?: continue
            for (c2 in children(pid, c1)) {
                val p2 = readPoint(pid, c2) ?: continue
                val mag = kotlin.math.abs(p2.first) + kotlin.math.abs(p2.second)
                if (mag < 100f || mag > 8000f || mag <= best) continue
                best = mag
                camX = p1.first + p2.first
                camY = p1.second + p2.second
            }
        }
        return Camera(camX, camY, width, height)
    }

    private fun nearestEnemyAngle(pid: Int, base: Long): Float? {
        val director = readLong(pid, base + DIRECTOR_GLOBAL) ?: return null
        val local = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)?.let { readLong(pid, it) } ?: return null
        val origin = entityPosition(pid, local, KIND_LOCAL) ?: return null
        var best = 4000.0
        var angle: Float? = null
        for (target in collectTargets(pid, base)) {
            val dx = target.x - origin.first
            val dy = target.y - origin.second
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
            patch[0] = 0x68.toByte(); patch[1] = 0x00.toByte(); patch[2] = 0x00.toByte(); patch[3] = 0x10.toByte()
            patch[4] = 0x00.toByte(); patch[5] = 0x01.toByte(); patch[6] = 0x40.toByte(); patch[7] = 0xBD.toByte()
            patch[8] = 0xC0.toByte(); patch[9] = 0x03.toByte(); patch[10] = 0x5F.toByte(); patch[11] = 0xD6.toByte()
            for (i in 16 until 24 step 4) {
                patch[i] = 0x1F.toByte(); patch[i + 1] = 0x20.toByte()
                patch[i + 2] = 0x03.toByte(); patch[i + 3] = 0xD5.toByte()
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
