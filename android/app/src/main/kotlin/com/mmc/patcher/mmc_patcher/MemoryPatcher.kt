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
    // Scratch floats in .bss (writable): flag=1 -> return locked angle, flag=0 -> native getter.
    private const val AIM_FLAG_ADDR = 0x13f4700L
    private const val AIM_ANGLE_ADDR = 0x13f4704L
    private const val LOCAL_CONTROLLER_GLOBAL = 0x13bec80L
    private const val REMOTE_PLAYERS_GLOBAL = 0x13bf260L
    private const val ALL_DRONES_GLOBAL = 0x13be0a0L
    private const val DIRECTOR_GLOBAL = 0x13ec228L

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
        readBytes(pid, address, 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long }

    private fun readInt(pid: Int, address: Long): Int? =
        readBytes(pid, address, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int }

    private fun readFloat(pid: Int, address: Long): Float? =
        readBytes(pid, address, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float }

    private fun readDouble(pid: Int, address: Long): Double? =
        readBytes(pid, address, 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).double }

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

    private fun writeInt(pid: Int, address: Long, value: Int) {
        writeBytes(pid, address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
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

    // ---- dictionary walking (both globals need TWO pointer reads) ----

    private fun dictionaryObjects(pid: Int, base: Long, holderGlobal: Long): List<Long> {
        val holder = readLong(pid, base + holderGlobal) ?: return emptyList()
        if (holder == 0L) return emptyList()
        val dict = readLong(pid, holder) ?: return emptyList()
        if (dict == 0L) return emptyList()
        var elem = readLong(pid, dict + 0x18) ?: return emptyList()
        val out = mutableListOf<Long>()
        var guard = 0
        while (elem != 0L && guard++ < 256) {
            readLong(pid, elem + 0x108)?.let { if (it != 0L) out.add(it) }
            elem = readLong(pid, elem + 0x120) ?: 0L
        }
        return out
    }

    /** SoldierController / SoldierLocalController: body at +0x1a8, x/y doubles at +0x30/+0x38. */
    private fun soldierPosition(pid: Int, ctrl: Long): Pair<Double, Double>? {
        val body = readLong(pid, ctrl + 0x1a8) ?: return null
        if (body == 0L) return null
        val x = readDouble(pid, body + 0x30) ?: return null
        val y = readDouble(pid, body + 0x38) ?: return null
        return x to y
    }

    /** Drones have a per-type indirection before the shared soldier body. */
    private fun dronePosition(pid: Int, base: Long, obj: Long): Pair<Double, Double>? {
        val vptr = readLong(pid, obj) ?: 0L
        val offset = when (vptr - base) {
            0x1334610L + 0x10, 0x132d1c0L + 0x10 -> 0x200L // HumanoidDrone / Enemy
            0x13337f0L + 0x10 -> 0x230L                       // HawkDrone
            0x135a960L + 0x10 -> 0x228L                       // WormDrone
            else -> null
        }
        val candidates = if (offset != null) listOf(offset) else listOf(0x200L, 0x230L, 0x228L)
        for (off in candidates) {
            val holder = readLong(pid, obj + off) ?: continue
            if (holder == 0L) continue
            val pos = soldierPosition(pid, holder) ?: continue
            if (kotlin.math.abs(pos.first) < 20000 && kotlin.math.abs(pos.second) < 20000) return pos
        }
        return null
    }

    fun localController(pid: Int, base: Long): Long? =
        readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)?.let { readLong(pid, it) }

    fun localPosition(pid: Int, base: Long): Pair<Double, Double>? {
        val lc = localController(pid, base) ?: return null
        return soldierPosition(pid, lc)
    }

    fun collectTargets(pid: Int, base: Long): List<EspTarget> {
        val out = mutableListOf<EspTarget>()
        val seen = HashSet<Long>()

        // Campaign robots: all-drones dictionary at *(*(base+0x13be0a0))
        for (obj in dictionaryObjects(pid, base, ALL_DRONES_GLOBAL)) {
            if (!seen.add(obj)) continue
            val pos = dronePosition(pid, base, obj) ?: continue
            val kind = when (readLong(pid, obj)?.minus(base)) {
                0x13337f0L + 0x10 -> KIND_HAWK
                0x135a960L + 0x10 -> KIND_WORM
                else -> KIND_DRONE
            }
            out.add(EspTarget(pos.first, pos.second, kind, 0f))
        }

        // Multiplayer / LAN players: *(*(base+0x13bf260))
        for (ctrl in dictionaryObjects(pid, base, REMOTE_PLAYERS_GLOBAL)) {
            if (!seen.add(ctrl)) continue
            val pos = soldierPosition(pid, ctrl) ?: continue
            out.add(EspTarget(pos.first, pos.second, KIND_PLAYER, 0f))
        }
        return out
    }

    // ---- ESP camera ----

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

    /** Multi-line dump of every pointer in the ESP/aim chain, for the debug overlay. */
    fun debugInfo(pid: Int, base: Long): String {
        fun hex(v: Long?) = v?.let { "0x${it.toString(16)}" } ?: "null"
        val sb = StringBuilder()
        sb.appendLine("pid=$pid base=0x${base.toString(16)}")

        val director = readLong(pid, base + DIRECTOR_GLOBAL)
        val scene = director?.let { readLong(pid, it + 0xa0) }
        sb.appendLine("director=${hex(director)} scene=${hex(scene)}")
        if (director != null) {
            val w = readFloat(pid, director + 0xd0)
            val h = readFloat(pid, director + 0xd4)
            sb.appendLine("winSize=${w?.toInt()}x${h?.toInt()}")
        }

        val lHolder = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)
        val lc = lHolder?.let { readLong(pid, it) }
        sb.appendLine("localHolder=${hex(lHolder)} localCtrl=${hex(lc)}")
        val lp = localPosition(pid, base)
        sb.appendLine("localPos=${lp?.first?.toInt()},${lp?.second?.toInt()}")

        fun dumpDict(label: String, global: Long, isDrone: Boolean) {
            val holder = readLong(pid, base + global)
            val dict = holder?.let { readLong(pid, it) }
            sb.appendLine("$label holder=${hex(holder)} dict=${hex(dict)}")
            if (dict == null || dict == 0L) return
            val objs = dictionaryObjects(pid, base, global)
            sb.appendLine("$label objects=${objs.size}")
            for (o in objs.take(3)) {
                val p = if (isDrone) dronePosition(pid, base, o) else soldierPosition(pid, o)
                sb.appendLine("  ${hex(o)} -> ${p?.first?.toInt()},${p?.second?.toInt()}")
            }
        }
        dumpDict("remote", REMOTE_PLAYERS_GLOBAL, false)
        dumpDict("drones", ALL_DRONES_GLOBAL, true)

        val cam = camera(pid, base)
        val targets = collectTargets(pid, base)
        sb.appendLine("cam=${cam?.x?.toInt()},${cam?.y?.toInt()} targets=${targets.size}")
        return sb.toString()
    }

    // ---- aimlock ----

    /** View sway must be zeroed or bullets fly off-angle even when the reticle is right. */
    private fun zeroSway(pid: Int, lc: Long) {
        val view = readLong(pid, lc + 0x150) ?: return
        if (view == 0L) return
        val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0f).array()
        for (off in longArrayOf(0x298, 0x29c, 0x2a4)) writeBytes(pid, view + off, zero)
    }

    /** Returns the angle to the nearest enemy, or null when there is no target. */
    private fun targetAngle(pid: Int, base: Long): Float? {
        val lc = localController(pid, base) ?: return null
        val origin = soldierPosition(pid, lc) ?: return null
        var best = 4000.0
        var found: Float? = null
        for (t in collectTargets(pid, base)) {
            val dx = t.x - origin.first
            val dy = t.y - origin.second
            val dist = hypot(dx, dy)
            if (dist < 10.0 || dist >= best) continue
            best = dist
            var deg = (atan2(-dy, dx) * 180.0 / Math.PI).toFloat()
            if (deg < 0f) deg += 360f
            found = deg
        }
        if (found != null) zeroSway(pid, lc)
        return found
    }

    /**
     * Conditional getter: when the flag is set, return the locked angle;
     * otherwise execute the original getter payload `ldr s0,[x0,#0x180]; ret`.
     * This preserves normal joystick control whenever no target is locked.
     */
    private val conditionalFireAngleStub = byteArrayOf(
        0x68.toByte(), 0x6B.toByte(), 0x00.toByte(), 0x90.toByte(), // adrp x8, page(0x13f4700)
        0x09.toByte(), 0x01.toByte(), 0x5C.toByte(), 0x39.toByte(), // ldrb w9, [x8, #0x700]  -> flag
        0x69.toByte(), 0x00.toByte(), 0x00.toByte(), 0x34.toByte(), // cbz w9, +3 insns -> native
        0x00.toByte(), 0x05.toByte(), 0x47.toByte(), 0xBD.toByte(), // ldr s0, [x8, #0x704]  -> locked angle
        0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte(), // ret
        0x00.toByte(), 0x80.toByte(), 0x41.toByte(), 0xBD.toByte(), // ldr s0, [x0, #0x180]  -> original getter
        0xC0.toByte(), 0x03.toByte(), 0x5F.toByte(), 0xD6.toByte(), // ret
    )

    fun toggleAimlock(enable: Boolean): Boolean {
        if (enable) {
            val pid = findPid() ?: return false
            val base = findModuleBase(pid) ?: return false
            val address = base + FIRE_ANGLE_OFFSET
            val original = readBytes(pid, address, 24) ?: return false
            originals[address] = original

            // Start disabled so the first tick can't return a stale locked angle.
            writeInt(pid, base + AIM_FLAG_ADDR, 0)
            writeFloat(pid, base + AIM_ANGLE_ADDR, 0f)
            if (!writeBytes(pid, address, conditionalFireAngleStub)) return false

            aimRunning = true
            aimThread = Thread {
                while (aimRunning) {
                    val p = findPid()
                    val b = p?.let { findModuleBase(it) }
                    if (p != null && b != null) {
                        val angle = targetAngle(p, b)
                        if (angle != null) {
                            writeFloat(p, b + AIM_ANGLE_ADDR, angle)
                            writeInt(p, b + AIM_FLAG_ADDR, 1)
                        } else {
                            // No target: let the stub fall through to the real getter.
                            writeInt(p, b + AIM_FLAG_ADDR, 0)
                        }
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
            writeInt(pid, base + AIM_FLAG_ADDR, 0)
            val original = originals.remove(base + FIRE_ANGLE_OFFSET) ?: return false
            return writeBytes(pid, base + FIRE_ANGLE_OFFSET, original)
        }
    }
}
