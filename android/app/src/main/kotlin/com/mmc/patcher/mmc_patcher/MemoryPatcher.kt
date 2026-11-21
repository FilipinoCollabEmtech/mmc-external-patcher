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

    private const val LOCAL_CONTROLLER_GLOBAL = 0x13bec80L
    private const val REMOTE_PLAYERS_GLOBAL = 0x13bf260L
    private const val ALL_DRONES_GLOBAL = 0x13be0a0L
    private const val DIRECTOR_GLOBAL = 0x13ec228L
    private const val CAM_POINT_GLOBAL = 0x13c1008L

    // Code cave and scratch (verified zero-filled RX region / RW .bss gap).
    private const val CAVE = 0xfbcac0L
    private const val SCRATCH = 0x13f4000L
    private const val LOS_SLOT = 0x13f4700L
    private const val JOYPAD_SLOT = 0x13f4780L
    private const val CAN_SEE_TARGET = 0x619e2cL
    private const val FAKE_GETTER = 0xd97ee8L
    private const val FIRE_ANGLE = 0x6888f0L

    // Assembled with NDK clang from cave.s (BL cave; RET; <clear>; <blocked>; NOP; NOP).
    private val STUB_HEX = "74 D0 24 94 C0 03 5F D6 00 00 00 00 00 00 00 00 1F 20 03 D5 1F 20 03 D5"
    private val CAVE_HEX = "FF8300D1FD7B01A9B0020058000200F9B0010058E00310AAB101005820023FD6300200580002003970010058111200911012919A000240BDFD7B41A9FF830091C0035FD600403F01000000002C9E610000000000F88868000000000080473F010000000000473F0100000000"

    private val originals = HashMap<Long, ByteArray>()
    private var comboThread: Thread? = null
    @Volatile private var comboRunning = false
    @Volatile private var aimEnabled = false
    @Volatile private var triggerEnabled = false
    @Volatile private var selectedTarget: EspTarget? = null
    @Volatile private var lastLosClear = false
    @Volatile private var lastAimDeg = 0f

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

    private fun writeLong(pid: Int, address: Long, value: Long) {
        writeBytes(pid, address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }

    private fun writeDouble(pid: Int, address: Long, value: Double) {
        writeBytes(pid, address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
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

    // ---- target enumeration ----

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

    private fun soldierPosition(pid: Int, ctrl: Long): Pair<Double, Double>? {
        val body = readLong(pid, ctrl + 0x1a8) ?: return null
        if (body == 0L) return null
        val x = readDouble(pid, body + 0x30) ?: return null
        val y = readDouble(pid, body + 0x38) ?: return null
        return x to y
    }

    private fun dronePosition(pid: Int, base: Long, obj: Long): Pair<Double, Double>? {
        val vptr = readLong(pid, obj) ?: 0L
        val offset = when (vptr - base) {
            0x1334610L + 0x10, 0x132d1c0L + 0x10 -> 0x200L
            0x13337f0L + 0x10 -> 0x230L
            0x135a960L + 0x10 -> 0x228L
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
        for (ctrl in dictionaryObjects(pid, base, REMOTE_PLAYERS_GLOBAL)) {
            if (!seen.add(ctrl)) continue
            val pos = soldierPosition(pid, ctrl) ?: continue
            out.add(EspTarget(pos.first, pos.second, KIND_PLAYER, 0f))
        }
        return out
    }

    // ---- ESP camera (authoritative global from Stage::updateViewV4) ----

    fun camera(pid: Int, base: Long): Camera? {
        val director = readLong(pid, base + DIRECTOR_GLOBAL) ?: return null
        val width = readFloat(pid, director + 0xd0) ?: return null
        val height = readFloat(pid, director + 0xd4) ?: return null
        val point = readLong(pid, base + CAM_POINT_GLOBAL) ?: return null
        val camX = readFloat(pid, point) ?: return null
        val camY = readFloat(pid, point + 4) ?: return null
        return Camera(camX, camY, width, height)
    }

    // ---- LOS cave ----

    private fun installCave(pid: Int, base: Long): Boolean {
        val stubAddr = base + FIRE_ANGLE
        if (!originals.containsKey(stubAddr)) {
            val original = readBytes(pid, stubAddr, 24) ?: return false
            originals[stubAddr] = original
        }
        if (!writeBytes(pid, base + CAVE, hexToBytes(CAVE_HEX))) return false
        if (!writeBytes(pid, stubAddr, hexToBytes(STUB_HEX))) return false

        // Fake Enemy object: vptr -> fake vtable -> cpSegmentShapeGetA.
        val s = base + SCRATCH
        writeLong(pid, s, s + 0x200)
        writeLong(pid, s + 0x200 + 0x358, base + FAKE_GETTER)
        writeFloat(pid, s + 0x180, 100000f) // sight range
        return true
    }

    private fun uninstallCave(pid: Int, base: Long) {
        val original = originals.remove(base + FIRE_ANGLE) ?: return
        writeBytes(pid, base + FIRE_ANGLE, original)
    }

    /** Write segment endpoints into the fake Enemy and return whether the path is clear. */
    private fun castLos(pid: Int, base: Long, from: Pair<Double, Double>, to: EspTarget): Boolean {
        val s = base + SCRATCH
        writeDouble(pid, s + 0x80, from.first)
        writeDouble(pid, s + 0x88, from.second)
        writeDouble(pid, s + 0x160, to.x)
        writeDouble(pid, s + 0x168, to.y)
        // The game thread evaluates this on its next getFireAngle call (~16 ms).
        try {
            Thread.sleep(24)
        } catch (_: InterruptedException) {
            // Ignore.
        }
        return readBytes(pid, base + LOS_SLOT, 1)?.firstOrNull() == 1.toByte()
    }

    private fun findJoypad(pid: Int, base: Long): Long? {
        readLong(pid, base + JOYPAD_SLOT)?.let { if (it != 0L) return it }
        val director = readLong(pid, base + DIRECTOR_GLOBAL) ?: return null
        val scene = readLong(pid, director + 0xa0) ?: return null
        for (child in children(pid, scene)) {
            val vptr = readLong(pid, child) ?: continue
            if (vptr - base != 0x1354a50L + 0x10) continue // StageLayer
            val stage = readLong(pid, child + 0x170) ?: return null
            val hud = readLong(pid, stage + 0x170) ?: return null
            return readLong(pid, hud + 0x178)
        }
        return null
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

    private fun zeroSway(pid: Int, lc: Long) {
        val view = readLong(pid, lc + 0x150) ?: return
        if (view == 0L) return
        val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0f).array()
        for (off in longArrayOf(0x298, 0x29c, 0x2a4)) writeBytes(pid, view + off, zero)
    }

    private fun normalizeDeg(d: Float): Float {
        var v = d
        while (v < 0f) v += 360f
        while (v >= 360f) v -= 360f
        return v
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = kotlin.math.abs(normalizeDeg(a) - normalizeDeg(b))
        if (d > 180f) d = 360f - d
        return d
    }

    private fun startCombo() {
        if (comboRunning) return
        val pid = findPid() ?: return
        val base = findModuleBase(pid) ?: return
        if (!installCave(pid, base)) return
        comboRunning = true
        comboThread = Thread {
            while (comboRunning) {
                val p = findPid()
                val b = p?.let { findModuleBase(it) }
                if (p != null && b != null) {
                    val stub = b + FIRE_ANGLE
                    val joypad = findJoypad(p, b)
                    val lc = localController(p, b)
                    val live = joypad?.let { readFloat(p, it + 0x180) }
                    val baseAngle = lc?.let {
                        readLong(p, it + 0x150)?.let { v -> readFloat(p, v + 0x2a0) }
                    } ?: live ?: 0f

                    var target: EspTarget? = null
                    var best = 4000.0
                    val origin = localPosition(p, b)
                    if (origin != null) {
                        for (t in collectTargets(p, b)) {
                            val dist = hypot(t.x - origin.first, t.y - origin.second)
                            if (dist < 10.0 || dist >= best) continue
                            best = dist
                            target = t
                        }
                    }

                    if ((aimEnabled || triggerEnabled) && target != null && origin != null) {
                        val deg = normalizeDeg((atan2(-(target!!.y - origin.second), target!!.x - origin.first) * 180.0 / Math.PI).toFloat())
                        lastAimDeg = deg
                        if (aimEnabled) {
                            zeroSway(p, lc!!)
                            // Clear angle at stub+8, blocked fallback at stub+12.
                            writeFloat(p, stub + 8, deg)
                            writeFloat(p, stub + 12, baseAngle)
                        } else {
                            val fallback = live ?: baseAngle
                            writeFloat(p, stub + 8, fallback)
                            writeFloat(p, stub + 12, fallback)
                        }
                        val aligned = !triggerEnabled || aimEnabled || angleDiff(deg, live ?: deg) < 12f
                        if (aligned) {
                            selectedTarget = target
                            lastLosClear = castLos(p, b, origin, target!!)
                        } else {
                            selectedTarget = null
                            lastLosClear = false
                        }
                    } else {
                        selectedTarget = null
                        lastLosClear = false
                        val fallback = live ?: baseAngle
                        writeFloat(p, stub + 8, fallback)
                        writeFloat(p, stub + 12, fallback)
                    }

                    if (triggerEnabled && joypad != null && selectedTarget != null && lastLosClear) {
                        // Aimlock already points us at the target; hold fire briefly.
                        writeBytes(p, joypad + 0x18a, byteArrayOf(1))
                    } else if (joypad != null) {
                        writeBytes(p, joypad + 0x18a, byteArrayOf(0))
                    }
                }
                try {
                    Thread.sleep(16)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun setAimlock(enable: Boolean): Boolean {
        val pid = findPid() ?: return false
        val base = findModuleBase(pid) ?: return false
        aimEnabled = enable
        if (enable) startCombo()
        if (!enable && !triggerEnabled) {
            comboRunning = false
            comboThread?.interrupt()
            try {
                comboThread?.join(200)
            } catch (_: InterruptedException) {
                // Ignore.
            }
            comboThread = null
            uninstallCave(pid, base)
        }
        return true
    }

    fun setTriggerbot(enable: Boolean): Boolean {
        val pid = findPid() ?: return false
        val base = findModuleBase(pid) ?: return false
        triggerEnabled = enable
        if (enable) startCombo()
        if (!enable && !aimEnabled) {
            comboRunning = false
            comboThread?.interrupt()
            try {
                comboThread?.join(200)
            } catch (_: InterruptedException) {
                // Ignore.
            }
            comboThread = null
            uninstallCave(pid, base)
        }
        return true
    }

    fun debugInfo(pid: Int, base: Long): String {
        fun hex(v: Long?) = v?.let { "0x${it.toString(16)}" } ?: "null"
        val sb = StringBuilder()
        sb.appendLine("pid=$pid base=0x${base.toString(16)}")
        val cam = camera(pid, base)
        sb.appendLine("cam=${cam?.x?.toInt()},${cam?.y?.toInt()} win=${cam?.width?.toInt()}x${cam?.height?.toInt()}")
        val lc = localController(pid, base)
        sb.appendLine("localCtrl=${hex(lc)} pos=${localPosition(pid, base)?.first?.toInt()},${localPosition(pid, base)?.second?.toInt()}")
        sb.appendLine("targets=${collectTargets(pid, base).size} selected=${selectedTarget?.kind} aim=${lastAimDeg.toInt()}")
        sb.appendLine("LOS(1=clear)=${readBytes(pid, base + LOS_SLOT, 1)?.firstOrNull()} joypad=${hex(findJoypad(pid, base))}")
        sb.appendLine("aim=$aimEnabled trigger=$triggerEnabled cave=${hex(readLong(pid, base + FIRE_ANGLE))}")
        return sb.toString()
    }
}
