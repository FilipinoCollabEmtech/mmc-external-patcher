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
    private const val LOCAL_CONTROLLER_GLOBAL = 0x13bec80L
    private const val REMOTE_PLAYERS_GLOBAL = 0x13bf260L
    private const val ALL_DRONES_GLOBAL = 0x13be0a0L
    private const val DIRECTOR_GLOBAL = 0x13ec228L

    // Internal ESP: one hook in Stage::draw, engine in the .plt/.text alignment gap.
    private const val STAGE_DRAW_OFFSET = 0x9087d8L
    private const val ESP_CODE_OFFSET = 0x500310L
    private const val ESP_DATA_OFFSET = 0x500b10L
    private const val MPROTECT_OFFSET = 0x4d51d0L

    // LOS probe: runs inside the aimlock getter and calls the game's segment query.
    private const val LOS_CODE_OFFSET = 0x500c00L
    private const val LOS_DATA_OFFSET = 0x500e00L
    private const val LOS_BRANCH_HEX = "C4E0F917"  // b 0x500c00 from 0x6888f0
    private const val LOS_ENGINE_HEX =
        "ffc300d1e00300f9fe1700f9f3d300a9f55b01a9f7e301a9530f0010782a40f9e00313aa00cc7492010084d2e20080d200033fd6740240b9d4030034601240bd0020201e66030054740640f915bf81d29527a0f2956a75f8d50200b4b50240f9950200b4752200f916a48ad2b609a0f29602168be00315aa601240fd611640fd621a40fd631e40fd01208052e2031f2a63020291c0023fd6602600f9a00000b534008052740600b9601240bd0500001414008052740600b9f70340f9e08241bdf7e341a9f55b41a9f3d340a9fe1740f9ffc30091c0035fd6"

    @Volatile private var losActive = false
    @Volatile private var losOrigin = Pair(0.0, 0.0)
    @Volatile private var losTarget = Pair(0.0, 0.0)
    private val losSaved = HashMap<Long, ByteArray>()

    private const val ESP_ENGINE_HEX =
        "FF4303D1FD7B0BA9F35300A9F55B01A9F76302A9F96B03A9FB7304A9E827803DE06300F9F33E0010600640B920010035E00313AA00CC7492010084D2" +
        "E20080D2701E40F900023FD620008052600600B9E06340F9FF8300D11D2110947C0240B93C0A0034E06340F9612A40F91F0001EB20010054602A00F9" +
        "602240F9800000B4210080D2705E40F900023FD67F2200F97F2600F9F56340F9752600F9742240F9700A40F900023FD6A00800B4F40300AA742200F9" +
        "E00315AAE10314AA02D490D22200A0F2700E40F900023FD6E00314AA701240F900023FD67F000094E05300BDE15700BD602E40F9000040F9C00200B4" +
        "170C40F9180080D2770200B41F0304F12A02005418070091F68640F9F79240F956FFFFB4E00316AA90000094E05B00BDE15F00BDD80000949CFE0F36" +
        "E05B40BDE15F40BDE25340BDE35740BD12010094EEFFFF17603240F9000040F9800300B4170C40F9180080D2370300B41F0304F1EA02005418070091" +
        "F68640F9F79240F956FFFFB4E00316AA66000094E05B00BDE15F00BDC00000949CFE0F36E05B40BDE15F40BDE25340BDE35740BDFA000094EEFFFF17" +
        "602240F9A00000B4701240F900023FD67F2200F901000014E06340F9F35340A9F55B41A9F76342A9F96B43A9FB7344A9E827C03DFD7B4BA9FF430391" +
        "C0035FD6FF4301D1E02700F9FF1300F9E003271E015C40F9810600B4210C40F9410600B4220040B95F000071ED0500545F000871AC050054230440F9" +
        "630500B4040080D29F00026B0A050054657864F8850400B4A65C40F9460400B4C60C40F9060400B4C70040B9FF000071AD030054FF0008716C030054" +
        "C80440F9280300B4090080D23F01076BCA0200540A7969F84A0200B4432D40BD443140BD63C0201E84C0201E6528241E0B59A8526601271E4B3FA852" +
        "6701271EA020261EE9000054A020271EAA000054A020201E69000054A040201EEA1300F929050091EAFFFF1784040091D8FFFF17E01340F9FF430191" +
        "C0035FD6603640F9000040F9400100B4000040F9000100B401D440F9C10000B4201840FD211C40FD0040621E2140621EC0035FD6E003271EE103271E" +
        "C0035FD601D440F9E10000B4201840FD211C40FD0040621E2140621E62008052C0035FD6E003271EE103271E62008052C0035FD6006861F8600000B4" +
        "00D440F9C0035FD6000080D2C0035FD6FFC300D1FE1300F9E01700F9610640F9020040F9420001CB034080D2040080520500805206C488D26626A0F2" +
        "5F0006EB40040054063A9AD24626A0F25F0006EBC0030054060087D26626A0F25F0006EBA1000054034680D2240080522500805216000014062E95D2" +
        "A626A0F25F0006EBA1000054034580D244008052250080520E000014034080D204008052E01740F9D6FFFF97800100B5034680D224008052E01740F9" +
        "D1FFFF97E00000B5034580D24400805201000014E01740F9E10303AACAFFFF97200100B4001840FD011C40FD0040621E2140621EE203042AFE1340F9" +
        "FFC30091C0035FD6E003271EE103271E02008052FE1340F9FFC30091C0035FD66BE201915F040071610000546B220291C0035FD65F08007161000054" +
        "6B620291C0035FD66BA20291C0035FD6FF0302D1FE3F00F9E04300BDE14700BDF1FFFF976001C03DE01B803D0334A8526300271E030090526341A872" +
        "6400271E0538231E0628231E2738241E2828241EE52300BDE72700BDE62B00BDE72F00BDE63300BDE83700BDE53B00BDE83F00BD0900A8522001271E" +
        "E00314AAE1830091E2A300912001271EE3830191701640F900023FD6E00314AAE1A30091E2C300912001271EE3830191701640F900023FD6E00314AA" +
        "E1C30091E2E300912001271EE3830191701640F900023FD6E00314AAE1E30091E28300912001271EE3830191701640F900023FD60910A852E00314AA" +
        "E10301912001271EE2830191701A40F900023FD6FE3F40F9FF030291C0035FD6FF4301D1FE2700F9B3FFFF976001C03DE00F803DE02300BDE12700BD" +
        "E22B00BDE32F00BDE00314AAE1830091E2A3009109F0A7522001271EE3C30091701640F900023FD6FE2740F9FF430191C0035FD6"

    private val espSaved = HashMap<Long, ByteArray>()
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

    /** Finite angle only when a target exists; NaN means "use the native joystick angle". */
    private fun aimAngle(pid: Int, base: Long): Float {
        val lc = localController(pid, base) ?: return Float.NaN
        zeroSway(pid, lc)
        val origin = soldierPosition(pid, lc) ?: return Float.NaN
        losOrigin = origin
        var best = 4000.0
        var found = Float.NaN
        for (t in collectTargets(pid, base)) {
            val dx = t.x - origin.first
            val dy = t.y - origin.second
            val dist = hypot(dx, dy)
            if (dist < 10.0 || dist >= best) continue
            best = dist
            var deg = (atan2(-dy, dx) * 180.0 / Math.PI).toFloat()
            if (deg < 0f) deg += 360f
            found = deg
            losTarget = t.x to t.y
        }
        return found
    }

    fun toggleAimlock(enable: Boolean): Boolean {
        if (enable) {
            val pid = findPid() ?: return false
            val base = findModuleBase(pid) ?: return false
            val address = base + FIRE_ANGLE_OFFSET
            val original = readBytes(pid, address, 24) ?: return false
            originals[address] = original

            // NaN in the literal slot means "no target" -> native joystick angle.
            val patch = hexToBytes("008041BD8100001C2020211E207C201EC0035FD60000C07F")
            if (!writeBytes(pid, address, patch)) return false
            // Enabling aimlock after LOS must not clobber the probe's branch.
            if (losActive) {
                losSaved[address] = patch
                if (!writeBytes(pid, address, hexToBytes(LOS_BRANCH_HEX))) return false
            }

            aimRunning = true
            aimThread = Thread {
                while (aimRunning) {
                    val p = findPid()
                    val b = p?.let { findModuleBase(it) }
                    if (p != null && b != null) {
                        val angle = aimAngle(p, b)
                        writeFloat(p, b + FIRE_ANGLE_OFFSET + 20, angle)
                        writeLosFrame(p, b, angle)
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
            val losPid = findPid()
            val losBase = losPid?.let { findModuleBase(it) }
            if (losPid != null && losBase != null) uninstallLos(losPid, losBase)
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

    // ---- internal ESP (drawn inside Stage::draw, no per-frame syscalls) ----

    /** Installs the in-process draw hook once. Refuses to run against an unknown build/cave. */
    fun installInternalEsp(pid: Int, base: Long): Boolean {
        val orig = readBytes(pid, base + STAGE_DRAW_OFFSET, 4) ?: run {
            PatchLogger.log("ESP", "cannot read Stage::draw prologue")
            return false
        }
        if (!orig.contentEquals(hexToBytes("FF8300D1"))) {
            PatchLogger.log("ESP", "Stage::draw prologue mismatch: ${orig.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }}")
            return false
        }
        if (readBytes(pid, base + ESP_CODE_OFFSET, 4)?.any { it != 0.toByte() } != false) {
            PatchLogger.log("ESP", "code cave not empty - already patched or wrong build")
            return false
        }

        val d = ByteBuffer.allocate(0xc0).order(ByteOrder.LITTLE_ENDIAN)
        d.putInt(0)                       // flags: bit0 boxes, bit1 lines
        d.putInt(0)                       // engine-set init marker
        d.putLong(base)
        d.putLong(base + 0xc3f1d8L)       // CCDrawNode::create
        d.putLong(base + 0xc23a7cL)       // CCNode::addChild
        d.putLong(base + 0xc41314L)       // CCDrawNode::clear
        d.putLong(base + 0xc3fb30L)       // drawSegment
        d.putLong(base + 0xc3f700L)       // drawDot
        d.putLong(base + MPROTECT_OFFSET)
        d.putLong(0)                      // cached draw node
        d.putLong(0)                      // cached world layer
        d.putLong(0)                      // cached stage
        d.putLong(base + ALL_DRONES_GLOBAL)
        d.putLong(base + REMOTE_PLAYERS_GLOBAL)
        d.putLong(base + LOCAL_CONTROLLER_GLOBAL)
        d.putLong(base + STAGE_DRAW_OFFSET + 4) // resume after the 4-byte hook
        arrayOf(
            floatArrayOf(1f, 0.15f, 0.15f, 1f),
            floatArrayOf(0.15f, 0.85f, 1f, 1f),
            floatArrayOf(1f, 0.85f, 0.15f, 1f),
            floatArrayOf(0.3f, 1f, 0.3f, 1f),
        ).forEach { color -> color.forEach { d.putFloat(it) } }
        d.putLong(base + 0xc23b50L)  // +0xB8: CCNode::removeFromParentAndCleanup(bool)

        if (!writeBytes(pid, base + ESP_DATA_OFFSET, d.array())) {
            PatchLogger.log("ESP", "failed writing engine data table")
            return false
        }
        if (!writeBytes(pid, base + ESP_CODE_OFFSET, hexToBytes(ESP_ENGINE_HEX))) {
            PatchLogger.log("ESP", "failed writing engine code")
            return false
        }
        if (!writeBytes(pid, base + STAGE_DRAW_OFFSET, hexToBytes("CEDEEF17"))) {
            PatchLogger.log("ESP", "failed writing Stage::draw hook")
            return false
        }
        espSaved[base + STAGE_DRAW_OFFSET] = orig
        return true
    }

    /** Toggling only changes a flags word — code bytes are never rewritten. */
    fun setEspFlags(pid: Int, base: Long, boxes: Boolean, lines: Boolean) {
        val flags = (if (boxes) 1 else 0) or (if (lines) 2 else 0)
        writeBytes(
            pid,
            base + ESP_DATA_OFFSET,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(flags).array(),
        )
    }

    fun uninstallInternalEsp(pid: Int, base: Long) {
        setEspFlags(pid, base, false, false)
        Thread.sleep(50)
        espSaved.remove(base + STAGE_DRAW_OFFSET)?.let {
            writeBytes(pid, base + STAGE_DRAW_OFFSET, it)
        }
    }

    // ---- line of sight ---------------------------------------------------

    /** Installs a code-cave LOS probe behind the aimlock getter. Requires aimlock on. */
    fun installLos(pid: Int, base: Long): Boolean {
        if (losActive) return true
        val address = base + FIRE_ANGLE_OFFSET
        if (!originals.containsKey(address)) {
            PatchLogger.log("LOS", "enable aimlock first")
            return false
        }
        val saved = readBytes(pid, base + LOS_CODE_OFFSET, 4)
        if (saved == null || !saved.all { it == 0.toByte() }) {
            PatchLogger.log("LOS", "LOS cave not empty")
            return false
        }
        val stub = hexToBytes(LOS_BRANCH_HEX)
        val prev = readBytes(pid, address, 24) ?: return false
        val data = ByteBuffer.allocate(0xa0).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(1)                       // flags: enabled
        data.putInt(0)                       // visible
        data.putLong(base)                   // base
        data.putFloat(Float.NaN)             // angle (NaN = no target)
        data.putFloat(0f)                    // pad
        data.putLong(0)                      // align to 0x20
        data.putDouble(losOrigin.first)
        data.putDouble(losOrigin.second)
        data.putDouble(losTarget.first)
        data.putDouble(losTarget.second)
        data.putLong(0)                      // space debug
        data.putLong(0)                      // query result debug
        data.putLong(base + MPROTECT_OFFSET) // mprotect PLT for the cave
        repeat(5) { data.putLong(0) }        // pad to 0x80
        repeat(4) { data.putLong(0) }        // 32-byte zeroed query info
        if (!writeBytes(pid, base + LOS_DATA_OFFSET, data.array())) return false
        if (!writeBytes(pid, base + LOS_CODE_OFFSET, hexToBytes(LOS_ENGINE_HEX))) return false
        if (!writeBytes(pid, address, stub)) return false
        losSaved[address] = prev
        losActive = true
        PatchLogger.log("LOS", "installed")
        return true
    }

    /** Called by the aim thread each tick with the current angle and target. */
    fun writeLosFrame(pid: Int, base: Long, angle: Float) {
        if (!losActive) return
        val d = base + LOS_DATA_OFFSET
        // Absolute puts: never touch engine-owned slots (+0x04 visible,
        // +0x40 space, +0x48 result) that the cave writes each frame.
        val b = ByteBuffer.allocate(0x40).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00, 1)                    // flags
        b.putLong(0x08, base)
        b.putFloat(0x10, angle)
        b.putDouble(0x20, losOrigin.first)
        b.putDouble(0x28, losOrigin.second)
        b.putDouble(0x30, losTarget.first)
        b.putDouble(0x38, losTarget.second)
        writeBytes(pid, d, b.array())
    }

    fun uninstallLos(pid: Int, base: Long) {
        if (!losActive) return
        losActive = false
        val address = base + FIRE_ANGLE_OFFSET
        losSaved.remove(address)?.let { writeBytes(pid, address, it) }
        PatchLogger.log("LOS", "uninstalled")
    }

    fun losVisible(pid: Int, base: Long): Boolean? {
        if (!losActive) return null
        val v = readInt(pid, base + LOS_DATA_OFFSET + 4) ?: return null
        return v == 1
    }

    /** Live readout of every value the in-game ESP engine reads, for debugging. */
    @Suppress("JoinDeclarationAndAssignment")
    fun espDebugInfo(pid: Int, base: Long): String {
        fun hx(v: Long?) = v?.let { "0x${it.toString(16)}" } ?: "null"
        val sb = StringBuilder()
        val d = base + ESP_DATA_OFFSET
        sb.appendLine("=== ESP DEBUG ===")
        sb.appendLine("pid=$pid base=${hx(base)}")

        val hook = readBytes(pid, base + STAGE_DRAW_OFFSET, 4)
        val hookText = hook?.joinToString(" ") { "%02X".format(it.toInt() and 0xff) } ?: "unreadable"
        val installed = hook?.contentEquals(hexToBytes("CEDEEF17")) == true
        sb.appendLine("hook@0x9087d8: $hookText ${if (installed) "INSTALLED" else "ORIGINAL/none"}")
        sb.appendLine("data@0x500b10: flags=${readInt(pid, d)} init=${readInt(pid, d + 4)}")

        val names = listOf("create", "addChild", "clear", "segment", "dot", "mprotect")
        names.forEachIndexed { i, name ->
            sb.appendLine("  $name=${hx(readLong(pid, d + 0x10L + i * 8))}")
        }
        sb.appendLine("  drawNode=${hx(readLong(pid, d + 0x40))} world=${hx(readLong(pid, d + 0x48))} stage=${hx(readLong(pid, d + 0x50))}")

        fun dict(label: String, global: Long) {
            val slot = base + global
            val holder = readLong(pid, slot)
            val dict = holder?.let { readLong(pid, it) }
            val first = dict?.let { readLong(pid, it + 0x18) }
            sb.appendLine("  $label slot=${hx(slot)} holder=${hx(holder)} dict=${hx(dict)} first=${hx(first)}")
        }
        dict("drones", ALL_DRONES_GLOBAL)
        dict("players", REMOTE_PLAYERS_GLOBAL)

        val lcHolder = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)
        val lc = lcHolder?.let { readLong(pid, it) }
        val body = lc?.let { readLong(pid, it + 0x1a8) }
        val lx = body?.let { readDouble(pid, it + 0x30) }
        val ly = body?.let { readDouble(pid, it + 0x38) }
        sb.appendLine("  local holder=${hx(lcHolder)} ctrl=${hx(lc)} body=${hx(body)} xy=${lx?.toInt()},${ly?.toInt()}")

        val director = readLong(pid, base + DIRECTOR_GLOBAL)
        val scene = director?.let { readLong(pid, it + 0xa0) }
        sb.appendLine("director=${hx(director)} scene=${hx(scene)}")

        if (scene != null && scene != 0L) {
            val list = mutableListOf<Triple<Float, Long, Long>>()
            for (c1 in children(pid, scene)) {
                val p1 = readPoint(pid, c1) ?: continue
                for (c2 in children(pid, c1)) {
                    val p2 = readPoint(pid, c2) ?: continue
                    val mag = kotlin.math.abs(p2.first) + kotlin.math.abs(p2.second)
                    if (mag in 100f..8000f) list.add(Triple(mag, c2, c1))
                }
            }
            sb.appendLine("world candidates=${list.size}")
            list.sortedByDescending { it.first }.take(4).forEach {
                sb.appendLine("  mag=${it.first.toInt()} node=${hx(it.second)} parent=${hx(it.third)}")
            }
        } else {
            sb.appendLine("world candidates=scene missing")
        }

        val drones = dictionaryObjects(pid, base, ALL_DRONES_GLOBAL)
        sb.appendLine("drones objects=${drones.size}")
        drones.take(5).forEach { obj ->
            val vptr = readLong(pid, obj)
            val pos = dronePosition(pid, base, obj)
            sb.appendLine("  ${hx(obj)} vptr-base=${hx(vptr?.minus(base))} xy=${pos?.first?.toInt()},${pos?.second?.toInt()}")
        }
        val players = dictionaryObjects(pid, base, REMOTE_PLAYERS_GLOBAL)
        sb.appendLine("players objects=${players.size}")
        players.take(5).forEach { ctrl ->
            val pos = soldierPosition(pid, ctrl)
            sb.appendLine("  ${hx(ctrl)} body=${hx(readLong(pid, ctrl + 0x1a8))} xy=${pos?.first?.toInt()},${pos?.second?.toInt()}")
        }
        val ld = base + LOS_DATA_OFFSET
        sb.appendLine("LOS: active=$losActive visible=${readInt(pid, ld + 4)} " +
            "space=${hx(readLong(pid, ld + 0x40))} result=${hx(readLong(pid, ld + 0x48))} " +
            "start=${losOrigin.first.toInt()},${losOrigin.second.toInt()} " +
            "end=${losTarget.first.toInt()},${losTarget.second.toInt()}")
        return sb.toString()
    }
}
