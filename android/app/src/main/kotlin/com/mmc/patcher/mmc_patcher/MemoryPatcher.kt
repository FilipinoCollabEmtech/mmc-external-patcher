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
    private const val LOS_ENGINE_HEX =
        "ff8300d1e00300f9fe0700f9f35301a9930f0010782a40f9e00313aa00cc7492010084d2e20080d200033fd6740240b9b4030034601240bd0020201e46030054740640f915bf81d21578a2f2956a75f8b50200b4b50240f9750200b4752200f916a48ad2b609a0f29602168be00315aa601240fd611640fd621a40fd631e40fd01208052e2031f2a63020291c0023fd6602600f9800000b534008052740600b90500001414008052740600b9f70340f9e08241bdf35341a9fe0740f9ff830091c0035fd6"

    @Volatile private var losActive = false
    @Volatile private var losOrigin = Pair(0.0, 0.0)
    @Volatile private var losTarget = Pair(0.0, 0.0)
    private val losSaved = HashMap<Long, ByteArray>()

    private const val ESP_ENGINE_HEX =
        "ff4303d1fd7b0ba9f35300a9f55b01a9f76302a9f96b03a9fb7304a9e827803de06300f9f33e0010600640b920010035e00313aa00cc7492010084d2e20080d2" +
        "701e40f900023fd620008052600600b9e06340f9ff8300d11d2110947c0240b97c0e0034e06340f9612a40f91f0001eb80000054602a00f97f2200f97f2600f9" +
        "e06340f97a000094f50300aaf50000b4622640f9bf0202ebe0010054752600f97f2200f90c000014680640f9094598d2c927a0f2096969f8c90000b4205140f9" +
        "800000b46a000094f50300aa550000b5f56340f9742240f9940200b4a15e40f9410200b4220c40f9020200b4430040b97f000071ad0100547f0008716c010054" +
        "440440f9240100b4050080d2bf00036bca000054867865f8df0014ebc0010054a5040091faffff17700a40f900023fd6a00800b4f40300aa742200f9e00315aa" +
        "e10314aa02d490d22200a0f2700e40f900023fd6e00314aa701240f900023fd67f000094e05300bde15700bd602e40f9000040f9c00200b4170c40f9180080d2" +
        "770200b41f0304f12a02005418070091f68640f9f79240f956ffffb4e00316aa90000094e05b00bde15f00bdd80000949cfe0f36e05b40bde15f40bde25340bd" +
        "e35740bd12010094eeffff17603240f9000040f9800300b4170c40f9180080d2370300b41f0304f1ea02005418070091f68640f9f79240f956ffffb4e00316aa" +
        "66000094e05b00bde15f00bdc00000949cfe0f36e05b40bde15f40bde25340bde35740bdfa000094eeffff17602240f9a00000b4701240f900023fd67f2200f9" +
        "01000014e06340f9f35340a9f55b41a9f76342a9f96b43a9fb7344a9e827c03dfd7b4ba9ff430391c0035fd6ff4301d1e02700f9ff1300f9e003271e015c40f9" +
        "810600b4210c40f9410600b4220040b95f000071ed0500545f000871ac050054230440f9630500b4040080d29f00026b0a050054657864f8850400b4a65c40f9" +
        "460400b4c60c40f9060400b4c70040b9ff000071ad030054ff0008716c030054c80440f9280300b4090080d23f01076bca0200540a7969f84a0200b4432d40bd" +
        "443140bd63c0201e84c0201e6528241e0b59a8526601271e4b3fa8526701271ea020261ee9000054a020271eaa000054a020201e69000054a040201eea1300f9" +
        "29050091eaffff1784040091d8ffff17e01340f9ff430191c0035fd6603640f9000040f9400100b4000040f9000100b401d440f9c10000b4201840fd211c40fd" +
        "0040621e2140621ec0035fd6e003271ee103271ec0035fd601d440f9e10000b4201840fd211c40fd0040621e2140621e62008052c0035fd6e003271ee103271e" +
        "62008052c0035fd6006861f8600000b400d440f9c0035fd6000080d2c0035fd6ffc300d1fe1300f9e01700f9610640f9020040f9420001cb034080d204008052" +
        "0500805206c488d26626a0f25f0006eb40040054063a9ad24626a0f25f0006ebc0030054060087d26626a0f25f0006eba1000054034680d22400805225008052" +
        "16000014062e95d2a626a0f25f0006eba1000054034580d244008052250080520e000014034080d204008052e01740f9d6ffff97800100b5034680d224008052" +
        "e01740f9d1ffff97e00000b5034580d24400805201000014e01740f9e10303aacaffff97200100b4001840fd011c40fd0040621e2140621ee203042afe1340f9" +
        "ffc30091c0035fd6e003271ee103271e02008052fe1340f9ffc30091c0035fd66be201915f040071610000546b220291c0035fd65f080071610000546b620291" +
        "c0035fd66ba20291c0035fd6ff0302d1fe3f00f9e04300bde14700bdf1ffff976001c03de01b803d0334a8526300271e030090526341a8726400271e0538231e" +
        "0628231e2738241e2828241ee52300bde72700bde62b00bde72f00bde63300bde83700bde53b00bde83f00bd0900a8522001271ee00314aae1830091e2a30091" +
        "2001271ee3830191701640f900023fd6e00314aae1a30091e2c300912001271ee3830191701640f900023fd6e00314aae1c30091e2e300912001271ee3830191" +
        "701640f900023fd6e00314aae1e30091e28300912001271ee3830191701640f900023fd60910a852e00314aae10301912001271ee2830191701a40f900023fd6" +
        "fe3f40f9ff030291c0035fd6ff4301d1fe2700f9b3ffff976001c03de00f803de02300bde12700bde22b00bde32f00bde00314aae1830091e2a3009109f0a752" +
        "2001271ee3c30091701640f900023fd6fe2740f9ff430191c0035fd6"

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

        val d = ByteBuffer.allocate(0xb8).order(ByteOrder.LITTLE_ENDIAN)
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
        val stub = hexToBytes("C4E0F917")  // b 0x500c00 from 0x6888f0
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
        val b = ByteBuffer.allocate(0x50).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1)                 // keep LOS enabled
        b.putInt(0)                 // visible set by engine
        b.putLong(base)
        b.putFloat(angle)
        b.putFloat(0f)
        b.putLong(0)                // align to 0x20
        b.putDouble(losOrigin.first)
        b.putDouble(losOrigin.second)
        b.putDouble(losTarget.first)
        b.putDouble(losTarget.second)
        b.putLong(0)
        b.putLong(0)
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
