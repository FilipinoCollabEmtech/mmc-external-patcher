package com.mmc.patcher.mmc_patcher

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

internal data class EspTarget(
    val x: Double, val y: Double, val kind: Int, val hp: Float,
    val friendly: Boolean = false, val teamId: Int = -1,
    var vx: Double = 0.0, var vy: Double = 0.0, var velSrc: String = "-",
    var ax: Double = 0.0, var ay: Double = 0.0,
    var crouched: Boolean = false, val ctrl: Long = 0L,
)
internal data class Camera(
    val x: Float, val y: Float,           // B: world offset in cocos points
    val width: Float, val height: Float,  // winSize in points
    val scaleX: Float = 1f, val scaleY: Float = 1f, // A: accumulated world scale
    val csf: Float = 1f,
    val offsetX: Float = 0f, val offsetY: Float = 0f, // letterbox in screen px
    val viewScale: Float = 1f,            // px per cocos point
)

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
    // Verified in cocos_new.so at file/vaddr offset 0x1084a18: 35 fa 8e bc =
    // float32 -0.017453292f = -PI/180. addBullet/addGrenade/addRocket/addShell/addSaw
    // all do rad = degrees * this constant, then fire along (cos(rad), sin(rad))
    // (disasm:904723-904728, 904918-904922, 904991-905002, 509779-509796).
    // Our atan2(-dy,dx) degree convention is therefore already correct: the game
    // turns it into the world direction (dx,dy)/|d|. No extra PI or 180 offset.
    private const val LOCAL_CONTROLLER_GLOBAL = 0x13bec80L
    private const val REMOTE_PLAYERS_GLOBAL = 0x13bf260L
    private const val ALL_DRONES_GLOBAL = 0x13be0a0L
    private const val DIRECTOR_GLOBAL = 0x13ec228L
    private const val TEAM_ID_OFFSET = 0x144L
    private const val LOCAL_TEAM_GLOBAL = 0x13bede8L

    // SoldierController::isDucked 0x8e2b94 reads byte [this+0x1ea] & 1
    // (cocos2dcpp_disasm.txt:1133378). SoldierController::setDucked 0x8e2bb4
    // writes the same byte (disasm:1133393). SoldierRemoteController::setDucked
    // 0x8f70b4 also stores 13 (0xd) into [this+0x214] when ducked
    // (disasm:1154595-1154597) - used only as a fallback when the byte is unreadable.
    private const val CTRL_DUCKED = 0x1eaL
    private const val CTRL_DUCKED_SHAPE = 0x214L
    private const val DUCKED_SHAPE_VALUE = 13

    // ---- multiplayer join capture (READ-ONLY; external /proc/<pid>/mem) ----
    // All offsets below come from cocos2dcpp_disasm.txt / cocos2dcpp_dynsyms.txt.
    // NetworkManager singleton: dynsyms file line 24357 `m_pInstance` @0x13d9540;
    // sharedNetworkManager() (disasm 0x713478-0x713480) reads *(0x13c0528) to get the
    // address of that slot, then dereferences it. Both paths are sampled and logged so a
    // build with a different relocation still yields a usable trace.
    private const val NM_INSTANCE_GLOBAL = 0x13d9540L
    private const val NM_ACCESSOR_SLOT_GLOBAL = 0x13c0528L
    // ZTV14NetworkManager @0x1341070 (dynsyms file line 7179) + 0x10 = expected vptr.
    private const val NM_VTABLE = 0x1341080L
    private const val NM_PEER_DICT = 0x128L          // getPeerCount() disasm 0x71918c -> CCDictionary*
    private const val NM_LOCAL_PEER_ID = 0x160L      // getLocalPeerID() disasm 0x71a154 -> std::string
    private const val NM_LEADER_PEER_ID = 0x178L     // getLeaderPeerID() disasm 0x71e06c -> std::string
    private const val NM_SESSION = 0x3f8L            // onlineMatchExists() disasm 0x71d2f4
    private const val NM_RAKNET = 0x400L             // getRakNetInstance() disasm 0x718824
    private const val NM_CONNECTED = 0x418L          // isConnected() disasm 0x71eff4
    private const val NM_WAS_INVITED = 0x419L        // getWasInvited() disasm 0x71f640
    private const val NM_IN_GAME = 0x41aL            // isInGame() disasm 0x71dff0
    private const val NM_MAX_PLAYERS = 0x41cL        // getMaxPlayers() disasm 0x71df40
    private const val NM_SESSION_TO_JOIN = 0x428L    // getSessionToJoin() disasm 0x71d784 -> std::string
    // Each global below holds a pointer to the scalar the getter returns.
    private const val NET_CONN_TYPE_GLOBAL = 0x13bf448L    // getConnectionType() disasm 0x71f620
    private const val NET_REQ_SESSION_GLOBAL = 0x13be530L  // isRequestingSession() disasm 0x74327c
    private const val NET_SESSION_DONE_GLOBAL = 0x13c0e58L // hasCompletedSessionRequest() disasm 0x74317c
    private const val NET_QUICKPLAY_POP_GLOBAL = 0x13c13b0L// getQuickplayPopulation() disasm 0x74233c
    private const val NET_SHOW_ASK_GLOBAL = 0x13bf640L     // getShowAskButton() disasm 0x7422cc

    private const val JOIN_MAX_LINES = 600
    private const val JOIN_SAMPLE_MS = 50L
    private const val JOIN_HEARTBEAT_MS = 2000L
    private const val JOIN_MAX_LOGCAT = 200

    @Volatile var teamCheck = true
        private set
    @Volatile var showFriendliesDimmed = false
    private const val TEAM_FAIL_OPEN = true

    fun setTeamCheck(enabled: Boolean) { teamCheck = enabled }

    // Internal ESP: one hook in Stage::draw, engine in the .plt/.text alignment gap.
    private const val STAGE_DRAW_OFFSET = 0x9087d8L
    private const val ESP_CODE_OFFSET = 0x500310L
    private const val ESP_DATA_OFFSET = 0x500b10L
    private const val MPROTECT_OFFSET = 0x4d51d0L

    // LOS probe: runs inside the aimlock getter and calls the game's segment query.
    private const val LOS_CODE_OFFSET = 0x500c00L
    private const val LOS_DATA_OFFSET = 0x500e00L
    private const val LOS_BRANCH_HEX = "C4E0F917"  // b 0x500c00 from 0x6888f0

    // Triggerbot: publish Joypad* from the proven LOS cave, then pull its fire byte.
    private const val JOY_FIRE_STATE = 0x18aL
    private const val JOY_ANGLE = 0x180L
    private const val LOS_JOYPTR_SLOT = 0x58L
    private const val JOYPTR_BRANCH_HEX = "35000014"
    private const val JOYPTR_STUB_HEX = "E00300F909000090202D07F9C9FFFF17"
    // Joypad::fire() (0x688e14) samples the byte at +0x18a with `and #1` once per
    // frame. At 30 FPS a 40 ms pulse can fall entirely between two samples, and
    // the game's own touch handlers write the same byte (disasm:508552 / 508647).
    // Keep the byte asserted every tick while a shot is pending instead.
    private const val TRIGGER_COOLDOWN_MS = 150L
    private const val TRIGGER_HOLD_MS = 100L   // >= 3 frames at 30 FPS
    private const val TRIGGER_MAX_HOLD_MS = 320L
    private const val AIM_TARGET_MATCH_RADIUS = 40.0  // cocos points
    private const val TRIG_UNKNOWN_RANGE_CAP = 800.0  // conservative cap when weapon read fails

    @Volatile private var triggerRunning = false
    private var triggerThread: Thread? = null
    @Volatile private var joyPtrPublished = false
    private val joyPtrSaved = HashMap<Long, ByteArray>()
    private var savedFireState: Byte? = null
    private var lastFireMs = 0L
    private var fireStartMs = 0L
    private var firePulled = false
    @Volatile private var trigFireWrites = 0L
    @Volatile private var trigReleaseWrites = 0L
    @Volatile private var trigSuccessCount = 0L
    @Volatile private var trigLastReason = "-"
    @Volatile private var trigLosSource = "-"
    @Volatile private var trigTargetKey = "-"
    private const val LOS_ENGINE_HEX =
        "ffc300d1e00300f9fe1700f9f3d300a9f55b01a9f7e301a9530f0010782a40f9e00313aa00cc7492010084d2e20080d200033fd6740240b9d4030034601240bd0020201e66030054740640f915bf81d29527a0f2956a75f8d50200b4b50240f9950200b4752200f916a48ad2b609a0f29602168be00315aa601240fd611640fd621a40fd631e40fd01208052e2031f2a63020291c0023fd6602600f9a00000b534008052740600b9601240bd0500001414008052740600b9f70340f9e08241bdf7e341a9f55b41a9f3d340a9fe1740f9ffc30091c0035fd6"

    @Volatile private var losActive = false
    @Volatile private var losOrigin = Pair(0.0, 0.0)
    @Volatile private var losTarget = Pair(0.0, 0.0)
    private val losSaved = HashMap<Long, ByteArray>()

    private const val ESP_ENGINE_HEX =
        "FF4303D1FD7B0BA9F35300A9F55B01A9F76302A9F96B03A9FB7304A9E827803DE06300F9F33E0010600640B920010035E00313AA00CC7492010084D2" +
        "E20080D2701E40F900023FD620008052600600B9E06340F9FF8300D11D2110947C0240B97C0A0034E06340F9612A40F91F0001EB20010054602A00F9" +
        "602240F9800000B4210080D2705E40F900023FD67F2200F97F2600F9F56340F9752600F9742240F9540000B40C000014700A40F900023FD6A00800B4" +
        "F40300AA742200F9E00315AAE10314AA02D490D22200A0F2700E40F900023FD6E00314AA701240F900023FD67F000094E05300BDE15700BD602E40F9" +
        "000040F9C00200B4170C40F9180080D2770200B41F0304F12A02005418070091F68640F9F79240F956FFFFB4E00316AA90000094E05B00BDE15F00BD" +
        "D80000949CFE0F36E05B40BDE15F40BDE25340BDE35740BD12010094EEFFFF17603240F9000040F9800300B4170C40F9180080D2370300B41F0304F1" +
        "EA02005418070091F68640F9F79240F956FFFFB4E00316AA66000094E05B00BDE15F00BDC00000949CFE0F36E05B40BDE15F40BDE25340BDE35740BD" +
        "FA000094EEFFFF17602240F9A00000B4701240F900023FD67F2200F901000014E06340F9F35340A9F55B41A9F76342A9F96B43A9FB7344A9E827C03D" +
        "FD7B4BA9FF430391C0035FD6FF4301D1E02700F9FF1300F9E003271E015C40F9810600B4210C40F9410600B4220040B95F000071ED0500545F000871" +
        "AC050054230440F9630500B4040080D29F00026B0A050054657864F8850400B4A65C40F9460400B4C60C40F9060400B4C70040B9FF000071AD030054" +
        "FF0008716C030054C80440F9280300B4090080D23F01076BCA0200540A7969F84A0200B4432D40BD443140BD63C0201E84C0201E6528241E0B59A852" +
        "6601271E4B3FA8526701271EA020261EE9000054A020271EAA000054A020201E69000054A040201EEA1300F929050091EAFFFF1784040091D8FFFF17" +
        "E01340F9FF430191C0035FD6603640F9000040F9400100B4000040F9000100B401D440F9C10000B4201840FD211C40FD0040621E2140621EC0035FD6" +
        "E003271EE103271EC0035FD601D440F9E10000B4201840FD211C40FD0040621E2140621E62008052C0035FD6E003271EE103271E62008052C0035FD6" +
        "006861F8600000B400D440F9C0035FD6000080D2C0035FD6FFC300D1FE1300F9E01700F9610640F9020040F9420001CB034080D20400805205008052" +
        "06C488D26626A0F25F0006EB40040054063A9AD24626A0F25F0006EBC0030054060087D26626A0F25F0006EBA1000054034680D22400805225008052" +
        "16000014062E95D2A626A0F25F0006EBA1000054034580D244008052250080520E000014034080D204008052E01740F9D6FFFF97800100B5034680D2" +
        "24008052E01740F9D1FFFF97E00000B5034580D24400805201000014E01740F9E10303AACAFFFF97200100B4001840FD011C40FD0040621E2140621E" +
        "E203042AFE1340F9FFC30091C0035FD6E003271EE103271E02008052FE1340F9FFC30091C0035FD66BE201915F040071610000546B220291C0035FD6" +
        "5F080071610000546B620291C0035FD66BA20291C0035FD6FF0302D1FE3F00F9E04300BDE14700BDF1FFFF976001C03DE01B803D0334A8526300271E" +
        "030090526341A8726400271E0538231E0628231E2738241E2828241EE52300BDE72700BDE62B00BDE72F00BDE63300BDE83700BDE53B00BDE83F00BD" +
        "0900A8522001271EE00314AAE1830091E2A300912001271EE3830191701640F900023FD6E00314AAE1A30091E2C300912001271EE3830191701640F9" +
        "00023FD6E00314AAE1C30091E2E300912001271EE3830191701640F900023FD6E00314AAE1E30091E28300912001271EE3830191701640F900023FD6" +
        "0910A852E00314AAE10301912001271EE2830191701A40F900023FD6FE3F40F9FF030291C0035FD6FF4301D1FE2700F9B3FFFF976001C03DE00F803D" +
        "E02300BDE12700BDE22B00BDE32F00BDE00314AAE1830091E2A3009109F0A7522001271EE3C30091701640F900023FD6FE2740F9FF430191C0035FD6"

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

    /** One persistent /proc/<pid>/mem handle per thread; pread instead of open+seek+read+close. */
    private class MemReader {
        private var raf: RandomAccessFile? = null
        private var channel: FileChannel? = null
        private var pid = -1

        fun bind(target: Int) {
            if (target == pid && channel != null) return
            close()
            pid = target
            raf = RandomAccessFile("/proc/$target/mem", "r")
            channel = raf!!.channel
        }

        fun block(address: Long, length: Int): ByteArray? {
            val ch = channel ?: return null
            val buf = ByteArray(length)
            var done = 0
            while (done < length) {
                val b = ByteBuffer.wrap(buf, done, length - done)
                val n = try {
                    ch.read(b, address + done)
                } catch (_: IOException) {
                    return null
                }
                if (n <= 0) return null
                done += n
            }
            return buf
        }

        fun close() {
            runCatching { channel?.close() }
            runCatching { raf?.close() }
            channel = null
            raf = null
            pid = -1
        }
    }

    private val memReader = ThreadLocal.withInitial { MemReader() }

    private fun readBytes(pid: Int, address: Long, length: Int): ByteArray? {
        val reader = memReader.get()
        return try {
            reader.bind(pid)
            reader.block(address, length)
        } catch (_: Exception) {
            reader.close()
            null
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

    private fun writeFloat(pid: Int, address: Long, value: Float): Boolean =
        writeBytes(pid, address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())

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
            // One pread covers both obj(+0x108) and next(+0x120).
            val block = readBytes(pid, elem, 0x128) ?: break
            val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            val obj = b.getLong(0x108)
            elem = b.getLong(0x120)
            if (obj != 0L) out.add(obj)
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

    private const val WORLD_LIMIT = 20000.0

    private fun bodyPosition(pid: Int, body: Long): Pair<Double, Double>? {
        if (body == 0L) return null
        val buf = readBytes(pid, body + 0x30, 16) ?: return null
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val x = b.getDouble(0)
        val y = b.getDouble(8)
        if (kotlin.math.abs(x) >= WORLD_LIMIT || kotlin.math.abs(y) >= WORLD_LIMIT) return null
        return x to y
    }

    /** Drones: humanoid/enemy go through a controller; hawk/worm point straight at the body. */
    private fun dronePosition(pid: Int, base: Long, obj: Long): Pair<Double, Double>? {
        val vptr = readLong(pid, obj) ?: return null
        val offset: Long
        val viaController: Boolean
        when (vptr - base) {
            0x1334620L, 0x132d1d0L -> { offset = 0x200L; viaController = true }  // humanoid / enemy
            0x1333800L -> { offset = 0x230L; viaController = false }              // hawk
            0x135a970L -> { offset = 0x228L; viaController = false }              // worm
            else -> return null                                                    // no fallback probing
        }
        val first = readLong(pid, obj + offset) ?: return null
        if (first == 0L) return null
        return if (viaController) {
            val body = readLong(pid, first + 0x1a8) ?: return null
            bodyPosition(pid, body)
        } else {
            bodyPosition(pid, first)
        }
    }

    fun localController(pid: Int, base: Long): Long? =
        readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)?.let { readLong(pid, it) }

    /** Crouch state for a remote SoldierController. Byte read is authoritative. */
    private fun isDucked(pid: Int, ctrl: Long): Boolean {
        if (ctrl == 0L) return false
        val flag = readBytes(pid, ctrl + CTRL_DUCKED, 1)?.get(0)?.toInt()
        if (flag != null) return (flag and 1) == 1
        // Fallback for a partially readable controller: remote setDucked also tags
        // the ducked body-shape id. Fail closed if neither field is readable.
        return readInt(pid, ctrl + CTRL_DUCKED_SHAPE) == DUCKED_SHAPE_VALUE
    }

    private fun teamIdOf(pid: Int, obj: Long): Int =
        if (obj == 0L) -1 else (readInt(pid, obj + TEAM_ID_OFFSET) ?: -1)

    fun localTeamId(pid: Int, base: Long): Int {
        val t = teamIdOf(pid, localController(pid, base) ?: 0L)
        if (t >= 0 && t != 1 && t != 0) return t
        val holder = readLong(pid, base + LOCAL_TEAM_GLOBAL) ?: return t
        val g = readInt(pid, holder) ?: return t
        return if (g >= 0) g else t
    }

    private fun isHostile(team: Int, local: Int, kind: Int): Boolean {
        if (kind == KIND_DRONE || kind == KIND_HAWK || kind == KIND_WORM) return true
        if (team < 0 || local < 0) return TEAM_FAIL_OPEN
        if (team == 1 || local == 1 || team == 0 || local == 0) return true
        return team != local
    }

    fun localPosition(pid: Int, base: Long): Pair<Double, Double>? {
        val lc = localController(pid, base) ?: return null
        return soldierPosition(pid, lc)
    }

    // ---- write safety across scene transitions ---------------------------
    // Leaving a match for the lobby and rejoining swaps the local controller and
    // joypad objects. The globals can briefly still hold the freed pointer; writing
    // into those objects corrupts the game heap (observed as "Invalid address ...
    // passed to free" in GLThread). Validate the controller vtable and pause all
    // controller writes while the scene pointer is changing.
    private const val LOCAL_CONTROLLER_VTABLE = 0x1352e00L // ZTV22SoldierLocalController 0x1352df0 + 0x10
    private const val SCENE_SETTLE_MS = 1500L

    @Volatile private var lastScene = 0L
    @Volatile private var sceneSettleUntil = 0L

    private fun sceneStable(pid: Int, base: Long): Boolean {
        val director = readLong(pid, base + DIRECTOR_GLOBAL) ?: return false
        val scene = readLong(pid, director + 0xa0) ?: return false
        val now = System.currentTimeMillis()
        if (scene == 0L) return false
        if (scene != lastScene) {
            lastScene = scene
            sceneSettleUntil = now + SCENE_SETTLE_MS
            return false
        }
        return now >= sceneSettleUntil
    }

    /** Non-null only for a live SoldierLocalController that is safe to write into. */
    private fun writableLocalController(pid: Int, base: Long): Long? {
        if (!sceneStable(pid, base)) return null
        val lc = localController(pid, base).takeIf { it != 0L } ?: return null
        val vptr = readLong(pid, lc) ?: return null
        if (vptr - base != LOCAL_CONTROLLER_VTABLE) return null
        return lc
    }

    fun collectTargets(pid: Int, base: Long, withVelocity: Boolean = false): List<EspTarget> {
        val out = mutableListOf<EspTarget>()
        val seen = HashSet<Long>()
        val localTeam = if (teamCheck) localTeamId(pid, base) else -1

        // Campaign robots: all-drones dictionary at *(*(base+0x13be0a0))
        for (obj in dictionaryObjects(pid, base, ALL_DRONES_GLOBAL)) {
            if (!seen.add(obj)) continue
            val pos = dronePosition(pid, base, obj) ?: continue
            val kind = when (readLong(pid, obj)?.minus(base)) {
                0x13337f0L + 0x10 -> KIND_HAWK
                0x135a960L + 0x10 -> KIND_WORM
                else -> KIND_DRONE
            }
            val t = EspTarget(pos.first, pos.second, kind, 0f)
            if (withVelocity) attachVelocity(pid, base, obj, kind, false, t)
            out.add(t)
        }

        // Multiplayer / LAN players: *(*(base+0x13bf260))
        for (ctrl in dictionaryObjects(pid, base, REMOTE_PLAYERS_GLOBAL)) {
            if (!seen.add(ctrl)) continue
            // One pread covers teamId(+0x144) and body pointer(+0x1a8). A failed
            // combined read must not discard an otherwise valid player: retry the
            // two fields separately before giving up.
            val blk = readBytes(pid, ctrl + TEAM_ID_OFFSET, 0x6c)
            val team: Int
            val body: Long
            if (blk != null && blk.size >= 0x6c) {
                val bb = ByteBuffer.wrap(blk).order(ByteOrder.LITTLE_ENDIAN)
                team = bb.getInt(0)
                body = bb.getLong(0x64)
            } else {
                // Keep the team check strict: never fail open on an unreadable team.
                team = readInt(pid, ctrl + TEAM_ID_OFFSET) ?: continue
                body = readLong(pid, ctrl + 0x1a8) ?: continue
            }
            val pos = bodyPosition(pid, body) ?: continue
            val hostile = !teamCheck || isHostile(team, localTeam, KIND_PLAYER)
            val friendly = teamCheck && !hostile
            if (friendly && !showFriendliesDimmed) continue
            val t = EspTarget(pos.first, pos.second, KIND_PLAYER, 0f, friendly, team,
                crouched = isDucked(pid, ctrl), ctrl = ctrl)
            if (withVelocity) attachVelocity(pid, base, ctrl, KIND_PLAYER, true, t)
            out.add(t)
        }
        return out
    }

    // ---- target velocity: game field first, finite difference as fallback ----

    private class VelMem(
        val x: Double, val y: Double, val t: Long,
        var evx: Double, var evy: Double,
        var ax: Double = 0.0, var ay: Double = 0.0,
        var rvx: Double = 0.0, var rvy: Double = 0.0,
        var src: String = "-",
        var stableTicks: Int = 0,
        var stationaryTicks: Int = 0,
    )
    private val velHistory = ConcurrentHashMap<Long, VelMem>()

    private fun finite(v: Double) = !v.isNaN() && !v.isInfinite()

    private fun readVec(pid: Int, address: Long): Pair<Double, Double>? {
        val buf = readBytes(pid, address, 16) ?: return null
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val x = b.getDouble(0)
        val y = b.getDouble(8)
        if (!finite(x) || !finite(y) || hypot(x, y) > MAX_TARGET_SPEED) return null
        return x to y
    }

    /** Reads the velocity the game itself stores; null when the field is missing/garbage. */
    private fun directVelocity(
        pid: Int, base: Long, key: Long, kind: Int, isPlayer: Boolean,
    ): Pair<Double, Double>? {
        if (isPlayer) return readVec(pid, key + REMOTE_VEL_X)
        val vptr = readLong(pid, key) ?: return null
        val body = when (vptr - base) {
            0x1334620L, 0x132d1d0L -> {
                val ctrl = readLong(pid, key + 0x200) ?: return null
                readLong(pid, ctrl + 0x1a8) ?: return null
            }
            0x1333800L -> readLong(pid, key + 0x230) ?: return null  // hawk
            0x135a970L -> readLong(pid, key + 0x228) ?: return null  // worm
            else -> return null
        }
        readVec(pid, body + BODY_VEL_X)?.let { return it }
        return readVec(pid, key + FALLBACK_VEL_X)
    }

    /**
     * Source-specific velocity smoothing. The game's own field (cpBody.vel / replicated
     * velocity) is authoritative and has no finite-difference lag, so it gets a fast EMA.
     * Blending is done only against a previous sample from the SAME source: a
     * [mem] -> [diff] switch previously mixed two estimators and injected a spurious
     * vector into the lead. Acceleration is derived only after the source has been
     * stable for VEL_STABLE_TICKS and is hard-capped; its lead contribution is gated
     * separately by LEAD_ACCEL_GAIN so a 16 ms derivative cannot dominate a 1.5 s lead.
     */
    private fun attachVelocity(
        pid: Int, base: Long, key: Long, kind: Int, isPlayer: Boolean, t: EspTarget,
    ) {
        val now = System.currentTimeMillis()
        val prev = velHistory[key]
        val direct = directVelocity(pid, base, key, kind, isPlayer)

        var diff: Pair<Double, Double>? = null
        if (prev != null) {
            val dt = now - prev.t
            if (dt in VEL_MIN_DT_MS..VEL_MAX_DT_MS) {
                val dx = (t.x - prev.x) * 1000.0 / dt
                val dy = (t.y - prev.y) * 1000.0 / dt
                if (finite(dx) && finite(dy) && hypot(dx, dy) <= MAX_TARGET_SPEED) diff = dx to dy
            }
        }

        val directSpeed = direct?.let { hypot(it.first, it.second) } ?: -1.0
        val diffSpeed = diff?.let { hypot(it.first, it.second) } ?: -1.0

        // Observed world-space motion is ground truth. If the target's position has
        // been effectively unchanged for several samples, it is stationary even when
        // the game's velocity field reports motion (a stale replicated field, or a
        // field that means something other than world velocity). Trusting that stale
        // field is what produces a lead on a stationary enemy while the shooter moves.
        val diffStationary = diffSpeed in 0.0..VEL_STATIONARY_SPEED
        val stationaryTicks = if (diffStationary) (prev?.stationaryTicks ?: 0) + 1 else 0
        val trustStationary = stationaryTicks >= VEL_STATIONARY_TICKS

        // Reject a memory field whose heading disagrees sharply with observed motion.
        val headingAgrees = if (direct != null && diff != null && directSpeed > 1.0 && diffSpeed > 1.0) {
            (direct.first * diff.first + direct.second * diff.second) / (directSpeed * diffSpeed) > 0.5
        } else true

        val useDirect = direct != null && !trustStationary && headingAgrees
        val src = if (trustStationary) "stationary" else if (useDirect) "mem" else if (diff != null) "diff" else "hold"
        val raw = when {
            trustStationary -> 0.0 to 0.0
            useDirect -> direct
            diff != null -> diff
            else -> prev?.let { it.evx to it.evy } ?: (0.0 to 0.0)
        }

        val sameSource = prev != null && prev.src == src
        val alpha = if (trustStationary) STATIONARY_EMA else if (useDirect) VEL_EMA else DIFF_EMA
        val evx = if (sameSource) prev.evx + alpha * (raw.first - prev.evx) else raw.first
        val evy = if (sameSource) prev.evy + alpha * (raw.second - prev.evy) else raw.second
        val stable = if (sameSource) (prev.stableTicks + 1).coerceAtMost(VEL_STABLE_TICKS) else 0

        val dt = (now - (prev?.t ?: now)).toDouble() / 1000.0
        val ax: Double
        val ay: Double
        if (sameSource && stable >= VEL_STABLE_TICKS && dt in 0.008..0.25) {
            val dax = ((raw.first - prev.rvx) / dt).coerceIn(-MAX_ACCEL, MAX_ACCEL)
            val day = ((raw.second - prev.rvy) / dt).coerceIn(-MAX_ACCEL, MAX_ACCEL)
            ax = ACCEL_EMA * dax + (1.0 - ACCEL_EMA) * prev.ax
            ay = ACCEL_EMA * day + (1.0 - ACCEL_EMA) * prev.ay
        } else {
            // Never carry acceleration across a source switch: that is the over-lead spike.
            ax = if (sameSource) prev.ax else 0.0
            ay = if (sameSource) prev.ay else 0.0
        }
        velHistory[key] = VelMem(t.x, t.y, now, evx, evy, ax, ay, raw.first, raw.second, src, stable, stationaryTicks)
        if (velHistory.size > 128) {
            val it = velHistory.entries.iterator()
            while (it.hasNext()) if (now - it.next().value.t > 2000L) it.remove()
        }
        t.vx = evx
        t.vy = evy
        t.ax = ax
        t.ay = ay
        t.velSrc = src
    }

    // ---- ESP camera ----

    private class NodeInfo(
        val px: Float, val py: Float,
        val sx: Float, val sy: Float,
        val ax: Float, val ay: Float,
        val cw: Float, val ch: Float,
    )

    private fun nodeInfo(pid: Int, node: Long): NodeInfo? {
        val buf = readBytes(pid, node, 0x54) ?: return null
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        return NodeInfo(
            px = b.getFloat(0x2c), py = b.getFloat(0x30),
            sx = b.getFloat(0x20), sy = b.getFloat(0x24),
            ax = b.getFloat(0x44), ay = b.getFloat(0x48),
            cw = b.getFloat(0x4c), ch = b.getFloat(0x50),
        )
    }

    /** Fraction of the view where the game frames the local player (from device screenshot). */
    private const val PLAYER_ANCHOR_X = 0.447f
    private const val PLAYER_ANCHOR_Y = 0.653f

    fun camera(pid: Int, base: Long, viewW: Int, viewH: Int): Camera? {
        val director = readLong(pid, base + DIRECTOR_GLOBAL) ?: return null
        val width = readFloat(pid, director + 0xd0) ?: return null
        val height = readFloat(pid, director + 0xd4) ?: return null
        val scene = readLong(pid, director + 0xa0) ?: return null
        val local = localPosition(pid, base) ?: return null

        // px per cocos point: min(view/win) already includes the content scale factor.
        val s = if (width > 0f && height > 0f) minOf(viewW / width, viewH / height) else 1f
        val ox = (viewW - width * s) / 2f
        val oy = (viewH - height * s) / 2f
        val anchorX = viewW * PLAYER_ANCHOR_X
        val anchorY = viewH * PLAYER_ANCHOR_Y

        data class Cand(val wx: Float, val wy: Float, val sx: Float, val sy: Float, val depth: Int)
        val cands = ArrayList<Cand>()
        fun walk(node: Long, depth: Int, ax: Float, ay: Float, asx: Float, asy: Float) {
            if (depth > 3 || cands.size >= 256) return
            val n = nodeInfo(pid, node) ?: return
            val wx = ax + asx * (n.px - n.ax * n.cw * n.sx)
            val wy = ay + asy * (n.py - n.ay * n.ch * n.sy)
            val sx = asx * n.sx
            val sy = asy * n.sy
            cands.add(Cand(wx, wy, sx, sy, depth))
            for (ch in children(pid, node)) walk(ch, depth + 1, wx, wy, sx, sy)
        }
        walk(scene, 0, 0f, 0f, 1f, 1f)

        // The design-fit layer sits at winSize/2 with scaleX = winW/480 (from RE dump).
        val design = cands.filter {
            it.sx > 1.05f && it.sx < 3f && kotlin.math.abs(it.sy - 1f) < 0.25f &&
                kotlin.math.abs(it.wx - width / 2f) < 4f &&
                kotlin.math.abs(it.wy - height / 2f) < 4f
        }.maxByOrNull { it.depth }
        // The game zooms by scaling a world node; scale the ESP world transform by
        // the same factor so boxes stay on target when Camera POV is widened.
        // The node itself is untouched by our writes, so apply the zoom once here.
        val zoom = if (povPercent == POV_MIN) 1f else livePovScale(pid, base)
        val ax = (design?.sx ?: 1f) * zoom
        val ay = (design?.sy ?: 1f) * zoom

        val bx = (anchorX - ox) / s - ax * local.first.toFloat()
        val by = (viewH - anchorY - oy) / s - ay * local.second.toFloat()
        return Camera(bx, by, width, height, ax, ay, 1f, ox, oy, s)
    }

    private fun defaultViewSize(): Pair<Int, Int> {
        val m = android.content.res.Resources.getSystem().displayMetrics
        return m.widthPixels to m.heightPixels
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

    // ---- weapon diagnostics: offsets verified against cocos2dcpp_disasm.txt ----
    private const val LC_PRIMARY = 0x1c8L
    private const val LC_SECONDARY = 0x1d0L
    private const val LC_DUAL = 0x1d8L
    private const val LC_SIDE = 0x1e0L

    private const val W_TYPE = 0x338L
    private const val W_DAMAGE = 0x320L
    private const val W_RANGE_BASE = 0x322L
    private const val W_RANGE_MOD = 0x26cL
    private const val W_SPEED_BASE = 0x2b4L
    private const val W_SPEED_MOD = 0x270L
    private const val W_RATE = 0x324L
    private const val W_ROUNDS = 0x318L
    private const val W_ACCURACY = 0x31cL
    private const val W_AMMO = 0x360L
    private const val W_CLIP = 0x362L
    private const val W_AMMO_CAP = 0x330L
    private const val W_MELEE_LEN = 0x2a0L
    private const val W_MELEE_DMG = 0x32cL
    private const val W_RELOAD_TIMER = 0x3bcL
    private const val W_RELOAD_MOD = 0x27cL
    private const val W_IS_RELOADING = 0x285L
    private const val W_IS_DUAL = 0x288L

    // ---- velocity / lead fields (cocos2dcpp_disasm.txt line numbers in comments) ----
    // chipmunk cpBody: pos at +0x30/+0x38, vel at +0x40/+0x48 (both double pairs).
    // SoldierController::getBodyPosition 0x8e2d20 loads [body+0x30] (disasm:1133503);
    // SoldierController::getBodyVelocity 0x8e2d84 loads [body+0x40] (disasm:1133530).
    // Same +0x40 velocity read in SoldierAIController (disasm:1131355),
    // HumanoidDrone (disasm:497088-497091), HawkDrone (disasm:490899-490902),
    // WormDrone (disasm:1261979-1261982).
    private const val BODY_VEL_X = 0x40L
    private const val BODY_VEL_Y = 0x48L
    // SoldierRemoteController::getBodyVelocity 0x8f6380 loads [this+0x260] (disasm:1153673);
    // setRemoteVelocity 0x8f62e4 stores [this+0x260]/[this+0x268] (disasm:1153641-1153643).
    private const val REMOTE_VEL_X = 0x260L
    private const val REMOTE_VEL_Y = 0x268L
    // Fallback used by the drone getBodyVelocity implementations when the cpBody is
    // absent: Enemy::setRemoteVelocity 0x61a3e8 stores +0x1d8/+0x1e0 (disasm:394488-394490);
    // HawkDrone::getBodyVelocity fallback at 0x677388 (disasm:490905-490908).
    private const val FALLBACK_VEL_X = 0x1d8L
    private const val FALLBACK_VEL_Y = 0x1e0L
    // Weapon::getDesignFiringPoint 0x948eb8 reads a cpVect design muzzle offset from
    // [weapon+0x2d0]/[weapon+0x2d8] (disasm:1239650), adds CCNode::getPosition()
    // (weapon vptr+0xa0), then applies nodeToWorldTransform (vptr+0x308) and the
    // director transform (0x13c0678 -> vptr+0x310). The result is the WORLD firing
    // point, and ProjectileManager::weaponFired passes it straight to addBullet as
    // the bullet origin (disasm:902757-902823). Weapon vtable slot +0x670 resolves
    // to getDesignFiringPoint (ELF .rela.dyn), and SoldierController::getFiringPoint
    // 0x8e2018 calls that same slot (disasm:1132586).
    private const val W_FIRE_POINT_X = 0x2d0L
    private const val W_FIRE_POINT_Y = 0x2d8L

    // The external patcher cannot call getDesignFiringPoint in-process, so the
    // muzzle-to-body correction is a configurable world-space vector. The two
    // modes are: a fixed (dx,dy) offset, or the design offset rotated into world
    // space by the current aim angle. Both need live calibration (see AIM ACCURACY).
    // FILL: MUZZLE_* values must be calibrated on device by firing at a stationary
    // target and measuring the systematic miss; see espDebugInfo() AIM ACCURACY.
    @Volatile var muzzleOffsetX = 0.0
        private set
    @Volatile var muzzleOffsetY = 0.0
        private set
    // Live logs show the design firing point is a small, consistent weapon-local
    // point (UZI/TEC9/MAGNUM 95,16; SAWGUN 80,15), so use it by default. The
    // disassembly-proven muzzle origin is otherwise never applied and bullets
    // fly from the body centre, which is the systematic miss being reported.
    @Volatile var muzzleUseDesign = true
        private set
    @Volatile private var muzzleDesignScale = 1.0

    fun setMuzzleOffset(dx: Double, dy: Double) {
        muzzleOffsetX = dx.coerceIn(-500.0, 500.0)
        muzzleOffsetY = dy.coerceIn(-500.0, 500.0)
        PatchLogger.log("AIM", "muzzle world offset set dx=${"%.2f".format(muzzleOffsetX)} dy=${"%.2f".format(muzzleOffsetY)}")
    }

    fun setMuzzleUseDesign(enabled: Boolean, scale: Double = 1.0) {
        muzzleUseDesign = enabled
        muzzleDesignScale = scale.coerceIn(0.0, 5.0)
        PatchLogger.log("AIM", "muzzle design-offset mode=$enabled scale=$muzzleDesignScale")
    }

    // getBulletSpeed 0x9487f0 returns an int: int[w+0x2b4] * float[w+0x270],
    // truncated by `fcvtzs w0, s0` (disasm:1239172-1239180). addBullet passes that
    // int through unchanged to Projectile::reset via weapon vptr+0x5f0
    // (disasm:904723-904787; vtable slot resolved from ELF .rela.dyn). No scale
    // factor exists anywhere on the fire path, so the world-unit conversion is 1.0.
    // FILL: if live traces show a constant lead bias, calibrate this against a
    // stationary target at a known distance; 1.0 is the disassembly-backed value.
    private const val BULLET_SPEED_SCALE = 1.0

    // Acceleration derived from 16-33 ms velocity samples is dominated by noise
    // and source switches ([mem] -> [diff]); the old 1.5 s clamp multiplied that
    // noise by t^2/2 and produced systematic over-lead. Keep a small, damped term.
    private const val LEAD_ACCEL_GAIN = 0.0   // FILL: raise only after live A/B test
    private const val MAX_TARGET_SPEED = 5000.0   // cocos points/s; rejects garbage fields
    private const val VEL_EMA = 0.70              // direct game velocity is authoritative: track it fast
    private const val DIFF_EMA = 0.35             // heavier smoothing for noisy finite differences
    private const val ACCEL_EMA = 0.15            // heavy damping on the residual acceleration
    private const val MAX_ACCEL = 2000.0          // cocos points/s^2; hard cap (was 20000)
    private const val VEL_STABLE_TICKS = 8        // require a stable source before trusting accel
    private const val VEL_STATIONARY_SPEED = 15.0 // points/s: observed motion below this is "not moving"
    private const val VEL_STATIONARY_TICKS = 3    // consecutive samples before we zero a stale field
    private const val STATIONARY_EMA = 0.60       // decay fast once a target is confirmed stationary
    private const val TARGET_STATIONARY_SPEED = 20.0 // below this, intercept() aims straight (no lead)
    private const val VEL_MIN_DT_MS = 8L
    private const val VEL_MAX_DT_MS = 250L
    private const val MAX_LEAD_SECONDS = 1.5      // clamp so stale/fast fields cannot explode
    private const val MAX_LEAD_DISTANCE = 900.0   // cocos points of displacement
    private const val LEAD_ITERATIONS = 4
    private const val RANGE_SAFETY_MARGIN = 20.0          // cocos points
    private const val RANGE_SAFETY_FRACTION = 0.03        // or 3% of range, whichever is larger

    private val WEAPON_NAMES = arrayOf(
        "?", "MACHETE", "FRAGNADE", "DEAGLE", "MAGNUM", "UZI", "MP5", "AK47", "M16",
        "SHOTGUN", "M93BA", "SMAW", "ROCKET", "RIOTSHIELD", "HEALTHPACK", "BOOSTTANK",
        "M14", "PHASR", "GDEAGLE", "FLAMETHROWER", "GASNADE", "EMP", "?", "PROXYNADE",
        "SAW", "SAWGUN", "TAVOR", "MINIGUN", "TEC9", "RG6", "EMPNADE", "XM8",
        "MORTAR", "FLAGBLUE", "FLAGORANGE", "BOMBBLUE", "BOMBORANGE", "AA12", "?", "M1881",
    )

    // Object vptr = _ZTV + 0x10, so the key is readLong(w) - base - 0x10.
    private val WEAPON_VTABLES = mapOf(
        0x1316eb0L to "AA12", 0x1317c20L to "AK47", 0x1323440L to "BOMBBLUE",
        0x1323b70L to "BOMBORANGE", 0x132aaf0L to "DEAGLE", 0x132ba30L to "EMP",
        0x132c160L to "EMPNADE", 0x132e190L to "FLAGBLUE", 0x132e8c0L to "FLAGORANGE",
        0x132f360L to "FLAMETHROWER", 0x132fa90L to "FRAGNADE", 0x1331ea0L to "GASNADE",
        0x13325d0L to "GDEAGLE", 0x13390f0L to "M16", 0x1339820L to "M1881",
        0x1339f50L to "M93BA", 0x133a680L to "MACHETE", 0x133adb0L to "MAGNUM",
        0x133b4e0L to "MINIGUN", 0x133bc10L to "MORTAR", 0x133c340L to "MP5",
        0x1341af0L to "PHASR", 0x134a320L to "RG6", 0x134aa50L to "RIOTSHIELD",
        0x134b180L to "ROCKET", 0x134e770L to "SAW", 0x134eec0L to "SAWGUN",
        0x134f5f0L to "SHOTGUN", 0x134fd20L to "SMAW", 0x1356090L to "TAVOR",
        0x13567c0L to "TEC9", 0x1359560L to "UZI", 0x135ae30L to "XM8",
        0x135a180L to "Weapon",
    )

    private fun readU16(pid: Int, address: Long): Int? = readInt(pid, address)?.and(0xffff)
    private fun readI16(pid: Int, address: Long): Int? = readU16(pid, address)?.let {
        if (it >= 0x8000) it - 0x10000 else it
    }

    /** Multi-line weapon dump for the diagnostics overlays and COPY LOGS. */
    fun weaponInfo(pid: Int, base: Long): String {
        fun hx(v: Long?) = v?.let { "0x${it.toString(16)}" } ?: "null"
        val sb = StringBuilder()
        sb.appendLine("=== WEAPON ===")
        val lc = localController(pid, base)
        if (lc == null || lc == 0L) {
            sb.appendLine("localCtrl=null")
            return sb.toString()
        }
        sb.appendLine("lc=${hx(lc)}")

        fun dump(label: String, slot: Long): Long? {
            val w = readLong(pid, lc + slot) ?: 0L
            if (w == 0L) {
                sb.appendLine("$label=null")
                return null
            }
            val type = readInt(pid, w + W_TYPE)
            val idName = type?.let { WEAPON_NAMES.getOrNull(it) } ?: "?"
            val vptr = readLong(pid, w)
            val cls = vptr?.let { WEAPON_VTABLES[it - base - 0x10] } ?: "?"
            val dmg = readU16(pid, w + W_DAMAGE)
            val rangeBase = readU16(pid, w + W_RANGE_BASE)
            val rangeMod = readFloat(pid, w + W_RANGE_MOD)
            val range = if (rangeBase != null && rangeMod != null) (rangeBase * rangeMod).toInt() else null
            val speedBase = readInt(pid, w + W_SPEED_BASE)
            val speedMod = readFloat(pid, w + W_SPEED_MOD)
            val speed = if (speedBase != null && speedMod != null) (speedBase * speedMod).toInt() else null

            sb.appendLine("$label w=${hx(w)} vptr-base=${hx(vptr?.minus(base))} id=$type name=$idName cls=$cls")
            sb.appendLine("  dmg=$dmg range=$range bulletSpeed=$speed " +
                "rate=${readFloat(pid, w + W_RATE)} rpf=${readU16(pid, w + W_ROUNDS)} " +
                "acc=${readFloat(pid, w + W_ACCURACY)}")
            sb.appendLine("  bulletLen=${readInt(pid, w + W_MELEE_LEN)} meleeDmg=${readU16(pid, w + W_MELEE_DMG)} " +
                "clip=${readI16(pid, w + W_CLIP)} ammo=${readI16(pid, w + W_AMMO)} cap=${readU16(pid, w + W_AMMO_CAP)}")
            sb.appendLine("  reloading=${readBytes(pid, w + W_IS_RELOADING, 1)?.get(0)?.toInt()} " +
                "reloadT=${readFloat(pid, w + W_RELOAD_TIMER)} reloadMod=${readFloat(pid, w + W_RELOAD_MOD)} " +
                "dual=${readBytes(pid, w + W_IS_DUAL, 1)?.get(0)?.toInt()}")
            return w
        }

        val primary = dump("primary", LC_PRIMARY)
        dump("secondary", LC_SECONDARY)
        val dual = dump("dual", LC_DUAL)
        dump("side", LC_SIDE)
        sb.appendLine("active=${when {
            primary != null -> "primary"
            dual != null -> "dual"
            else -> "none"
        }}")
        return sb.toString()
    }

    // ---- camera POV (zoom-out) -------------------------------------------
    // HUD::setZoomLevel writes lc+0x2b4 on zoom/weapon change; updateStep and
    // getViewScaleV4 read it every frame. Lower value = wider view.
    private const val POV_LC_FIELD = 0x2b4L
    private const val POV_LC_SMOOTH = 0x29cL
    private const val POV_LC_LEGACY = 0x298L
    const val POV_MIN = 100
    const val POV_MAX = 500

    @Volatile var povPercent: Int = POV_MIN
        private set
    @Volatile var povScale: Float = 1f          // 100% -> 1.0, 500% -> 0.2
        private set
    @Volatile private var povKeeper = false
    private var povThread: Thread? = null

    /**
     * Zoom the game is actually rendering with. All three consumers of the POV
     * fields (getViewScaleV4, getViewScale, updateStep) are linear in this
     * value with no runtime clamp, so ESP must use the live value rather than
     * the requested one when the game briefly overwrites it (weapon/scope swap).
     */
    private fun livePovScale(pid: Int, base: Long): Float {
        val lc = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)?.let { readLong(pid, it) } ?: return povScale
        val z = readFloat(pid, lc + POV_LC_FIELD) ?: return povScale
        return if (finite(z.toDouble()) && z in 0.05f..1f) z else povScale
    }

    private fun applyPov(): Boolean {
        val pid = findPid() ?: return false
        val base = findModuleBase(pid) ?: return false
        // Never write into a controller that may have been freed by a scene swap.
        val lc = writableLocalController(pid, base) ?: run {
            if (povPercent != POV_MIN) logPredict("POV", "write skipped - scene settling or invalid controller")
            return false
        }
        val z = povScale
        val ok = writeFloat(pid, lc + POV_LC_FIELD, z) and
            writeFloat(pid, lc + POV_LC_SMOOTH, z) and
            writeFloat(pid, lc + POV_LC_LEGACY, z)
        if (povPercent != POV_MIN) {
            val actual = readFloat(pid, lc + POV_LC_FIELD)
            logPredict("POV", "pct=$povPercent req=${"%.3f".format(z)} " +
                "actual=${actual?.let { "%.3f".format(it) } ?: "?"} ok=$ok")
        }
        return ok
    }

    fun setPov(percent: Int): Boolean {
        val pct = percent.coerceIn(POV_MIN, POV_MAX)
        povPercent = pct
        povScale = 100f / pct
        val ok = applyPov()
        if (pct == POV_MIN) stopPovKeeper() else startPovKeeper()
        PatchLogger.log("POV", "set pct=$pct k=${"%.4f".format(povScale)} ok=$ok")
        return ok
    }

    private fun startPovKeeper() {
        if (povKeeper) return
        povKeeper = true
        povThread = Thread {
            while (povKeeper) {
                try {
                    if (povPercent != POV_MIN) applyPov()
                } catch (_: Throwable) {
                    // Transient process/scene change - retry next tick.
                }
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; name = "pov-keeper"; start() }
    }

    private fun stopPovKeeper() {
        povKeeper = false
        povThread?.interrupt()
        try { povThread?.join(200) } catch (_: InterruptedException) {}
        povThread = null
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

        val (dvw, dvh) = defaultViewSize()
        val cam = camera(pid, base, dvw, dvh)
        val targets = collectTargets(pid, base)
        sb.appendLine("cam=${cam?.x?.toInt()},${cam?.y?.toInt()} targets=${targets.size}")
        sb.append(weaponInfo(pid, base))
        return sb.toString()
    }

    // ---- aimlock with iterative predictive lead ----

    /** SoldierLocalController::fire 0x8ec46c reads primary (+0x1c8) and dual (+0x1d8). */
    private fun activeWeapon(pid: Int, lc: Long): Long? =
        readLong(pid, lc + LC_PRIMARY)?.takeIf { it != 0L }
            ?: readLong(pid, lc + LC_DUAL)?.takeIf { it != 0L }

    /** Raw speed product for diagnostics (before sanity filtering). */
    private fun bulletSpeedRawOf(pid: Int, w: Long): Double {
        val base = readInt(pid, w + W_SPEED_BASE)?.toDouble() ?: return Double.NaN
        val mod = readFloat(pid, w + W_SPEED_MOD)?.toDouble() ?: return Double.NaN
        return base * mod
    }

    /**
     * Weapon::getBulletSpeed 0x9487f0: `scvtf s0,w8 ; fmul s0,s0,s1 ; fcvtzs w0,s0`
     * (disasm:1239176-1239180). The result is an int world-speed passed unchanged
     * into Projectile::reset (weapon vptr+0x5f0, disasm:904723-904787).
     * Grenades/EMP expose 1 here; treat that as unusable instead of computing a
     * fake 1.5 s flight every frame.
     */
    private fun bulletSpeedOf(pid: Int, w: Long): Double {
        val base = readInt(pid, w + W_SPEED_BASE) ?: return 0.0
        val mod = readFloat(pid, w + W_SPEED_MOD) ?: return 0.0
        // Match the game's float32 multiply + truncation toward zero.
        val v = (base.toFloat() * mod).toInt().toDouble()
        return if (finite(v) && v in 100.0..100000.0) v * BULLET_SPEED_SCALE else 0.0
    }

    /**
     * Raw cpVect design muzzle offset from Weapon::getDesignFiringPoint
     * (disasm:1239650: `ldr q0, [x0, #0x2d0]`). Bounded to reject unreadable memory.
     */
    private fun designFiringOffsetOf(pid: Int, w: Long): Pair<Double, Double>? {
        val buf = readBytes(pid, w + W_FIRE_POINT_X, 16) ?: return null
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val fx = b.getDouble(0)
        val fy = b.getDouble(8)
        if (!finite(fx) || !finite(fy) || hypot(fx, fy) > 2000.0) return null
        return fx to fy
    }

    /**
     * Bullets spawn at Weapon::getDesignFiringPoint (weapon vptr+0x670), which
     * weaponFired passes to addBullet as the projectile origin (disasm:902757-902823).
     * Prediction from the body centre leaves a constant lateral miss. The game
     * function cannot be called externally, so apply a configurable world correction:
     * fixed (dx,dy), or the design offset rotated by the aim angle. FILL-calibrated.
     */
    private fun firingOrigin(
        pid: Int, w: Long?, body: Pair<Double, Double>, refDeg: Float,
        refDist: Double,
    ): Pair<Double, Double> {
        if (!muzzleUseDesign) {
            return body.first + muzzleOffsetX to body.second + muzzleOffsetY
        }
        val design = w?.let { designFiringOffsetOf(pid, it) } ?: return body
        // refDeg is the game's y-flipped fire angle (atan2(-dy,dx)); the game converts
        // it with -PI/180. Negate it here so the weapon-local +x axis points along
        // the actual firing direction instead of opposite to it when aiming upward.
        val rad = -Math.toRadians(refDeg.toDouble())
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        var dx = (design.first * c - design.second * s) * muzzleDesignScale
        var dy = (design.first * s + design.second * c) * muzzleDesignScale
        // The rotated design muzzle is ~96 world units from the body. When a target is
        // closer than that, the predicted origin lands PAST it and the origin->target
        // vector reverses: the angle derived from it is 180 degrees off (deg=347.6 at
        // d=16 in the device log). Keep the offset strictly inside the target distance
        // so the direction stays well-defined at point-blank range.
        val mag = hypot(dx, dy)
        // 0.9 leaves a 10% margin: full design offset at every normal range, clamped
        // only when the target is close enough for the muzzle to reach/past it.
        val cap = refDist * 0.9
        if (mag > 0.0 && finite(cap) && cap > 0.0 && mag > cap) {
            dx *= cap / mag
            dy *= cap / mag
        }
        return body.first + dx to body.second + dy
    }

    /** Weapon::getRange 0x9485cc: uint16 [w+0x322] x float [w+0x26c] (disasm:1239009-1239013). */
    private fun effectiveRangeOf(pid: Int, w: Long): Double {
        val base = readU16(pid, w + W_RANGE_BASE)?.toDouble() ?: return 0.0
        val mod = readFloat(pid, w + W_RANGE_MOD)?.toDouble() ?: return 0.0
        val v = base * mod
        return if (finite(v) && v in 20.0..100000.0) v else 0.0
    }

    private fun weaponNameOf(pid: Int, w: Long?): String {
        if (w == null) return "-"
        val type = readInt(pid, w + W_TYPE)
        return type?.let { WEAPON_NAMES.getOrNull(it) } ?: "?"
    }

    private data class Lead(
        val x: Double, val y: Double, val seconds: Double, val iterations: Int,
    )

    /** One trigger candidate with the ray test already resolved for the effective angle. */
    private data class TrigCandidate(
        val target: EspTarget, val raw: Double, val lead: Lead, val hit: Boolean,
    )

    /** Iterative intercept: t = |target + v*t + a*t^2/2 - origin| / bulletSpeed, lead clamped. */
    private fun intercept(
        ox: Double, oy: Double, tx: Double, ty: Double,
        vx: Double, vy: Double, ax: Double, ay: Double, speed: Double,
    ): Lead {
        if (speed <= 0.0 || !finite(speed)) return Lead(tx, ty, 0.0, 0)
        // A stationary enemy needs no lead at all. Without this, a stale velocity
        // field aims at a point beside the target even though it is not moving.
        if (!finite(vx) || !finite(vy) || hypot(vx, vy) < TARGET_STATIONARY_SPEED) {
            return Lead(tx, ty, hypot(tx - ox, ty - oy) / speed, 0)
        }
        var t = hypot(tx - ox, ty - oy) / speed
        var px = tx
        var py = ty
        var iter = 0
        while (iter < LEAD_ITERATIONS) {
            iter++
            val capped = t.coerceIn(0.0, MAX_LEAD_SECONDS)
            var lx = vx * capped + 0.5 * ax * capped * capped
            var ly = vy * capped + 0.5 * ay * capped * capped
            val mag = hypot(lx, ly)
            if (mag > MAX_LEAD_DISTANCE) {
                val s = MAX_LEAD_DISTANCE / mag
                lx *= s
                ly *= s
            }
            px = tx + lx
            py = ty + ly
            val next = hypot(px - ox, py - oy) / speed
            if (kotlin.math.abs(next - t) < 0.002) {
                t = next
                break
            }
            t = next
        }
        return Lead(px, py, t.coerceIn(0.0, MAX_LEAD_SECONDS), iter)
    }

    /** Live values surfaced in espDebugInfo() and throttled PatchLogger lines. */
    private data class PredictSnapshot(
        val weapon: String = "-",
        val range: Double = 0.0,
        val speed: Double = 0.0,
        val targetKind: Int = -1,
        val rawDist: Double = 0.0,
        val vx: Double = 0.0,
        val vy: Double = 0.0,
        val velSrc: String = "-",
        val ax: Double = 0.0,
        val ay: Double = 0.0,
        val travel: Double = 0.0,
        val leadAngle: Float = Float.NaN,
        val predictedDist: Double = 0.0,
        val iterations: Int = 0,
        val gateLimit: Double = 0.0,
        val gatePass: Boolean? = null,
        // ---- AIM ACCURACY ----
        val bodyX: Double = 0.0, val bodyY: Double = 0.0,
        val originX: Double = 0.0, val originY: Double = 0.0,
        val muzzleSrc: String = "-",
        val designX: Double = Double.NaN, val designY: Double = Double.NaN,
        val speedRaw: Double = Double.NaN,
        val speedScale: Double = BULLET_SPEED_SCALE,
        val leadX: Double = 0.0, val leadY: Double = 0.0,
        val validSpeed: Boolean = false,
        val accelApplied: Boolean = false,
        val clamped: Boolean = false,
        // Published by the aim thread so the trigger can reuse its exact target
        // instead of re-selecting against a different sample of the same list.
        val targetX: Double = 0.0,
        val targetY: Double = 0.0,
        val targetKey: String = "-",
        val reason: String = "-",
        val losSource: String = "-",
    )
    private fun targetKey(t: EspTarget): String =
        "K${t.kind}@${t.x.toInt()},${t.y.toInt()}" +
            if (t.ctrl != 0L) "/${java.lang.Long.toHexString(t.ctrl)}" else ""
    @Volatile private var aimPredict = PredictSnapshot()
    @Volatile private var trigPredict = PredictSnapshot()
    private val predictLogMs = ConcurrentHashMap<String, Long>()

    private fun logPredict(tag: String, message: String) {
        val now = System.currentTimeMillis()
        if (now - (predictLogMs[tag] ?: 0L) < 2000L) return
        predictLogMs[tag] = now
        PatchLogger.log(tag, message)
    }

    /**
     * Direction sanity for the value actually written into the aimlock literal slot.
     * `gameVel` is the direction the game will fire along: rad = -deg * PI/180,
     * velocity = (cos(rad), sin(rad)). It must point the same way as the independent
     * body->target direction; the dot product below is the test.
     */
    private data class AimFlipSnapshot(
        val targetX: Double = 0.0, val targetY: Double = 0.0,
        val expectedX: Double = 0.0, val expectedY: Double = 0.0,
        val originDx: Double = 0.0, val originDy: Double = 0.0,
        val computedDeg: Float = Float.NaN,
        val gameRad: Double = Double.NaN,
        val gameVelX: Double = Double.NaN, val gameVelY: Double = Double.NaN,
        val dot: Double = Double.NaN,
        val flipped: Boolean = false,
        val fallbackDeg: Float = Float.NaN,
        val writtenDeg: Float = Float.NaN,
    )
    @Volatile private var aimFlip = AimFlipSnapshot()

    /** The game's degree -> world-velocity mapping, duplicated verbatim from the .so. */
    private fun gameVelocity(deg: Float): Triple<Double, Double, Double> {
        val rad = -Math.toRadians(deg.toDouble())
        return Triple(rad, kotlin.math.cos(rad), kotlin.math.sin(rad))
    }

    private fun normalizeDeg(deg: Double): Float {
        val d = deg % 360.0
        return (if (d < 0.0) d + 360.0 else d).toFloat()
    }

    /**
     * Zero recoil/sway. Optionally publish the resolved angle into the view's base
     * firing-angle field: SoldierView::getFiringAngle (0x8fb0ac, disasm:1158685) reads
     * view+0x2a0 plus the sway/recoil fields at +0x2a4 and +0x298, which Stage's
     * sight update refreshes from Joypad::getFireAngle. Writing +0x2a0 directly closes
     * the one-frame window where a bullet could use a stale angle; the joypad getter
     * override stays the primary hook (it drives the reticle too) and gets the same
     * value, so tracking behaviour is unchanged.
     */
    private fun zeroSway(pid: Int, lc: Long, fireDeg: Float? = null) {
        val view = readLong(pid, lc + 0x150) ?: return
        if (view == 0L) return
        // A scene swap can leave a dangling view pointer; reject it before writing.
        if (readFloat(pid, view + 0x29c)?.let { finite(it.toDouble()) } != true) return
        val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0f).array()
        for (off in longArrayOf(0x298, 0x29c, 0x2a4)) writeBytes(pid, view + off, zero)
        if (fireDeg != null && finite(fireDeg.toDouble())) writeFloat(pid, view + 0x2a0, fireDeg)
    }

    /** Finite angle only when a target exists; NaN means "use the native joystick angle". */
    private fun aimAngle(pid: Int, base: Long): Float {
        // Skip the whole write path while the scene/controller is being replaced.
        val lc = writableLocalController(pid, base) ?: run {
            logPredict("AIM", "paused - scene settling or invalid controller")
            return Float.NaN
        }
        // Direct aim: no velocity lead, no muzzle offset, no intercept solve.
        val origin = soldierPosition(pid, lc) ?: return Float.NaN
        zeroSway(pid, lc)
        losOrigin = origin
        var best = 4000.0
        var found = Float.NaN
        var snap = PredictSnapshot(weapon = weaponNameOf(pid, activeWeapon(pid, lc)))
        for (t in collectTargets(pid, base)) {
            if (t.friendly) continue
            val dx = t.x - origin.first
            val dy = t.y - origin.second
            val dist = hypot(dx, dy)
            if (dist < 10.0 || dist >= best) continue
            best = dist
            var deg = (atan2(-dy, dx) * 180.0 / Math.PI).toFloat()
            if (deg < 0f) deg += 360f
            found = deg
            losTarget = t.x to t.y
            snap = snap.copy(
                targetKind = t.kind, rawDist = dist, predictedDist = dist,
                leadAngle = deg, targetX = t.x, targetY = t.y, targetKey = targetKey(t),
            )
        }
        aimPredict = snap
        aimFlip = AimFlipSnapshot()
        logPredict("AIM",
            "direct w=${snap.weapon} tgt=${snap.targetKind} d=${"%.0f".format(snap.rawDist)} " +
                "deg=${"%.1f".format(found)}")
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
            PatchLogger.log("AIM", "direct aimlock enabled")
            aimThread = Thread {
                while (aimRunning) {
                    val p = findPid()
                    val b = p?.let { findModuleBase(it) }
                    if (p != null && b != null) {
                        val aim = aimAngle(p, b)
                        val override = grenadeOverrideAngle
                        val angle = if (override.isNaN()) aim else override
                        writeFloat(p, b + FIRE_ANGLE_OFFSET + 20, angle)
                        aimFlip = aimFlip.copy(writtenDeg = angle)
                        // The LOS cave returns native [Joypad+0x180] instead of the
                        // literal when its segment query fails. That can point anywhere,
                        // including opposite the target, so surface the condition.
                        if (losActive && losVisible(p, b) == false && override.isNaN()) {
                            logPredict("AIMFLIP",
                                "LOS query failed - getter serving native joypad angle " +
                                    "instead of written $angle deg")
                        }
                        // Publish the same angle into the view's firing-angle base so
                        // bullets (SoldierView::getFiringAngle) cannot lag the reticle
                        // by one sight-update frame. NaN is left for native control.
                        val liveLc = localController(p, b)
                        if (liveLc != null && liveLc != 0L) zeroSway(p, liveLc, angle)
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
        val engine = hexToBytes(LOS_ENGINE_HEX)
        val existing = readBytes(pid, base + LOS_CODE_OFFSET, 4)
        val ours = existing != null && existing.contentEquals(engine.copyOfRange(0, 4))
        if (existing == null || (!ours && !existing.all { it == 0.toByte() })) {
            PatchLogger.log("LOS", "LOS cave occupied by unknown bytes")
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
        if (!writeBytes(pid, base + LOS_CODE_OFFSET, engine)) return false
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

    val triggerActive: Boolean get() = triggerRunning

    fun uninstallLos(pid: Int, base: Long) {
        // The grenade hook patches updateStep independently of the LOS cave; tear it
        // down first so disabling Wall Check cannot leave an armed throw behind.
        if (autoGrenadeEnabled) toggleAutoGrenade(false)
        if (!losActive) return
        if (triggerRunning) toggleTriggerbot(false)
        unpublishJoypadPointer(pid, base)
        losActive = false
        val address = base + FIRE_ANGLE_OFFSET
        losSaved.remove(address)?.let { writeBytes(pid, address, it) }
        // Clear our own cave so a later toggle can install cleanly.
        runCatching {
            writeBytes(pid, base + LOS_CODE_OFFSET, ByteArray(hexToBytes(LOS_ENGINE_HEX).size))
            writeBytes(pid, base + LOS_DATA_OFFSET, ByteArray(0xa0))
        }
        PatchLogger.log("LOS", "uninstalled")
    }

    fun losVisible(pid: Int, base: Long): Boolean? {
        if (!losActive) return null
        val v = readInt(pid, base + LOS_DATA_OFFSET + 4) ?: return null
        return v == 1
    }

    // ---- triggerbot ------------------------------------------------------

    private fun publishJoypadPointer(pid: Int, base: Long): Boolean {
        if (joyPtrPublished) return true
        if (!losActive) return false
        val code = base + LOS_CODE_OFFSET
        val orig = readBytes(pid, code + 4, 4) ?: return false
        if (!orig.contentEquals(hexToBytes("E00300F9"))) {
            PatchLogger.log("TRIG", "unexpected LOS cave prologue")
            return false
        }
        if (!writeBytes(pid, code + 4, hexToBytes(JOYPTR_BRANCH_HEX))) return false
        if (!writeBytes(pid, code + 0xd8, hexToBytes(JOYPTR_STUB_HEX))) return false
        joyPtrSaved[code + 4] = orig
        joyPtrPublished = true
        return true
    }

    private fun unpublishJoypadPointer(pid: Int, base: Long) {
        if (!joyPtrPublished) return
        val code = base + LOS_CODE_OFFSET
        joyPtrSaved.remove(code + 4)?.let { writeBytes(pid, code + 4, it) }
        writeBytes(pid, code + 0xd8, ByteArray(16))
        joyPtrPublished = false
    }

    private fun joypadPointer(pid: Int, base: Long): Long? =
        if (!joyPtrPublished) null
        else readLong(pid, base + LOS_DATA_OFFSET + LOS_JOYPTR_SLOT)?.takeIf { it != 0L }

    /** The cave republishes Joypad* each frame, but after a scene swap it can be stale. */
    private fun joypadUsable(pid: Int, jp: Long): Boolean {
        if (jp == 0L) return false
        val angle = readFloat(pid, jp + JOY_ANGLE) ?: return false
        if (!finite(angle.toDouble()) || kotlin.math.abs(angle) >= 100000f) return false
        // Joypad::fire() (0x688e14) reads this byte and masks it with &1, so any
        // readable value is valid. Restricting it to 0/1 skipped writes whenever
        // the game used a different encoding while firing.
        return (readBytes(pid, jp + JOY_FIRE_STATE, 1)?.size ?: 0) == 1
    }

    private fun releaseFire(pid: Int, base: Long, jp: Long) {
        val saved = savedFireState ?: return
        if (joypadUsable(pid, jp) && writeBytes(pid, jp + JOY_FIRE_STATE, byteArrayOf(saved))) {
            trigReleaseWrites++
        }
        firePulled = false
    }

    private fun rayHitsBox(
        ox: Double, oy: Double, dx: Double, dy: Double,
        tx: Double, ty: Double, hw: Double, hh: Double,
    ): Boolean {
        var tmin = 0.0
        var tmax = 50000.0
        if (kotlin.math.abs(dx) < 1e-6) {
            if (ox < tx - hw || ox > tx + hw) return false
        } else {
            var a = (tx - hw - ox) / dx
            var b = (tx + hw - ox) / dx
            if (a > b) { val t = a; a = b; b = t }
            tmin = maxOf(tmin, a); tmax = minOf(tmax, b)
            if (tmin > tmax) return false
        }
        if (kotlin.math.abs(dy) < 1e-6) {
            if (oy < ty - hh || oy > ty + hh) return false
        } else {
            var a = (ty - hh - oy) / dy
            var b = (ty + hh - oy) / dy
            if (a > b) { val t = a; a = b; b = t }
            tmin = maxOf(tmin, a); tmax = minOf(tmax, b)
            if (tmin > tmax) return false
        }
        return true
    }

    private fun halfW(k: Int) = when (k) { KIND_HAWK -> 40.0; KIND_WORM -> 10.0; KIND_PLAYER -> 25.0; else -> 25.0 }
    private fun halfH(k: Int) = when (k) { KIND_HAWK -> 40.0; KIND_WORM -> 5.0; KIND_PLAYER -> 30.0; else -> 25.0 }

    fun toggleTriggerbot(enable: Boolean): Boolean {
        if (enable) {
            val pid = findPid() ?: return false
            val base = findModuleBase(pid) ?: return false
            if (!losActive) { PatchLogger.log("TRIG", "enable Wall Check first"); return false }
            if (!publishJoypadPointer(pid, base)) return false

            var jp = 0L
            for (i in 0 until 20) {
                jp = joypadPointer(pid, base) ?: 0L
                if (jp != 0L) break
                Thread.sleep(50)
            }
            val state = if (jp != 0L) readBytes(pid, jp + JOY_FIRE_STATE, 1) else null
            if (state == null) { unpublishJoypadPointer(pid, base); return false }
            savedFireState = state[0]
            lastFireMs = 0L
            firePulled = false
            triggerRunning = true
            PatchLogger.log("TRIG", "range-gated triggerbot enabled")

            triggerThread = Thread {
                var p = pid
                var b = base
                var joy = jp
                while (triggerRunning) {
                    try {
                        val cp = findPid()
                        val cb = cp?.let { findModuleBase(it) }
                        if (cp == null || cb == null) {
                            releaseFire(p, b, joy)
                            Thread.sleep(100)
                            continue
                        }
                        if (cp != p || cb != b) {
                            releaseFire(p, b, joy)
                            p = cp; b = cb
                            joy = joypadPointer(cp, cb) ?: 0L
                            savedFireState = if (joy != 0L) readBytes(cp, joy + JOY_FIRE_STATE, 1)?.get(0) else null
                        }
                        if (joy == 0L) joy = joypadPointer(cp, cb) ?: 0L
                        val now = System.currentTimeMillis()
                        var shouldFire = false
                        var reason = "no-target"
                        var losSource = "-"
                        var key = "-"

                        val locked = readFloat(cp, cb + FIRE_ANGLE_OFFSET + 20)
                        val native = if (joy != 0L) readFloat(cp, joy + JOY_ANGLE) else null
                        val origin = localPosition(cp, cb)
                        val lc = writableLocalController(cp, cb)
                        val w = lc?.let { activeWeapon(cp, it) }
                        val speed = w?.let { bulletSpeedOf(cp, it) } ?: 0.0
                        val range = w?.let { effectiveRangeOf(cp, it) } ?: 0.0
                        // Unknown range fails open; known range stops short by a small margin.
                        val margin = if (range > 0.0) maxOf(RANGE_SAFETY_MARGIN, range * RANGE_SAFETY_FRACTION) else 0.0
                        // Unknown weapon (transient read during respawn/weapon swap) gets a
                        // conservative cap, not the old 4000, so it cannot burn ammo at a
                        // target the real weapon could never reach. Weapon present with
                        // invalid stats (EMP/grenade) fails fully closed.
                        val gateLimit = when {
                            w == null -> TRIG_UNKNOWN_RANGE_CAP
                            range <= 0.0 -> 0.0
                            else -> maxOf(10.0, range - margin)
                        }
                        var snap = PredictSnapshot(
                            weapon = weaponNameOf(cp, w), range = range, speed = speed,
                            gateLimit = gateLimit,
                        )

                        val losState = losVisible(cp, cb)
                        val aim = aimPredict
                        val aimLive = aim.targetKind != -1 && finite(aim.targetX) && finite(aim.targetY)
                        // The cave returns the override (lead) angle only when its segment
                        // query succeeded (0x500c9c-0x500cbc); otherwise it returns the
                        // native joystick angle from [Joypad+0x180]. Trace the same ray.
                        val caveApplied = losState == true && locked != null && !locked.isNaN()
                        val rayAngle = if (caveApplied) locked else native

                        when {
                            joy == 0L -> reason = "joy"
                            rayAngle == null -> reason = "angle"
                            origin == null || lc == null -> reason = "scene"
                            else -> {
                                val a = rayAngle
                                val rad = if (kotlin.math.abs(a) > 6.283185f)
                                    Math.toRadians(a.toDouble()) else a.toDouble()
                                val dx = kotlin.math.cos(rad)
                                val dy = -kotlin.math.sin(rad)
                                val candidates = collectTargets(cp, cb, withVelocity = true)
                                    .filter {
                                        it.kind == KIND_DRONE || it.kind == KIND_HAWK ||
                                            it.kind == KIND_WORM || it.kind == KIND_PLAYER
                                    }
                                    .map { t ->
                                        val raw = hypot(t.x - origin.first, t.y - origin.second)
                                        val lead = intercept(
                                            origin.first, origin.second, t.x, t.y, t.vx, t.vy, t.ax, t.ay, speed,
                                        )
                                        val hw = halfW(t.kind)
                                        val hh = halfH(t.kind)
                                        // Aimlock fires at the lead point; manual aim at the raw point.
                                        val hit = rayHitsBox(
                                            origin.first, origin.second, dx, dy, t.x, t.y, hw, hh,
                                        ) || rayHitsBox(
                                            origin.first, origin.second, dx, dy, lead.x, lead.y, hw, hh,
                                        )
                                        TrigCandidate(t, raw, lead, hit)
                                    }
                                    .filter { it.raw in 10.0..4000.0 }
                                // When the cave applied the override, its LOS belongs to the
                                // aimlock's exact target. Fire at that target, not a different
                                // one picked from a second sample of the same list. Without
                                // the override the game fires at the native angle, so pick
                                // the nearest target actually on that ray (the original
                                // triggerShoot behavior in Main.cpp:717-777).
                                val aimMatch = if (aimLive) candidates.firstOrNull {
                                    hypot(it.target.x - aim.targetX, it.target.y - aim.targetY) <=
                                        AIM_TARGET_MATCH_RADIUS
                                } else null
                                val target = if (caveApplied)
                                    (aimMatch ?: candidates.minByOrNull { it.raw })
                                else
                                    (candidates.filter { it.hit }.minByOrNull { it.raw }
                                        ?: candidates.minByOrNull { it.raw })
                                losSource = when {
                                    !aimLive -> "aim-none"
                                    target == null -> "no-target"
                                    aimMatch == null -> "mismatch"
                                    losState == null -> "unreadable"
                                    losState == true -> "aimlock"
                                    else -> "aimlock-blocked"
                                }
                                if (target == null) {
                                    reason = "no-target"
                                } else {
                                    val t = target.target
                                    val lead = target.lead
                                    key = targetKey(t)
                                    val gateDist = hypot(lead.x - origin.first, lead.y - origin.second)
                                    // Both the live position and the intercept point must be inside
                                    // the projectile's travel limit, so a lead toward the shooter
                                    // cannot justify firing at an out-of-range target.
                                    val inRange = gateDist <= gateLimit && target.raw <= gateLimit
                                    snap = snap.copy(
                                        targetKind = t.kind, rawDist = target.raw,
                                        vx = t.vx, vy = t.vy, velSrc = t.velSrc,
                                        ax = t.ax, ay = t.ay,
                                        travel = lead.seconds, predictedDist = gateDist,
                                        iterations = lead.iterations, gatePass = inRange,
                                        leadX = lead.x - t.x, leadY = lead.y - t.y,
                                        targetX = t.x, targetY = t.y, targetKey = key,
                                    )
                                    when {
                                        !inRange -> reason = "range"
                                        // Only the aimlock target's LOS is meaningful to the
                                        // cave. A wall on that exact target still blocks fire.
                                        aimMatch != null && losState != true -> reason = "LOS"
                                        !target.hit -> reason = "off-ray"
                                        else -> {
                                            shouldFire = true
                                            reason = "fire"
                                        }
                                    }
                                }
                            }
                        }
                        snap = snap.copy(reason = reason, losSource = losSource, targetKey = key)
                        trigPredict = snap
                        trigLastReason = reason
                        trigLosSource = losSource
                        trigTargetKey = key
                        logPredict("TRIG",
                            "gate=${snap.gatePass ?: false} w=${snap.weapon} range=${"%.0f".format(range)} " +
                                "raw=${"%.0f".format(snap.rawDist)} " +
                                "dist=${"%.0f".format(snap.predictedDist)} limit=${"%.0f".format(gateLimit)} " +
                                "tgt=${snap.targetKind} key=$key v=${"%.0f".format(snap.vx)}," +
                                "${"%.0f".format(snap.vy)}[${snap.velSrc}] " +
                                "tt=${"%.3f".format(snap.travel)} los=$losSource reason=$reason " +
                                "fire=$shouldFire shots=$trigSuccessCount")

                        val curState = if (joy != 0L) readBytes(cp, joy + JOY_FIRE_STATE, 1)?.get(0) else null
                        val joyOk = joypadUsable(cp, joy)
                        // Track the game's resting value: a respawn or scene swap can
                        // change it after we captured it at enable time.
                        if (!firePulled && curState != null && curState != 1.toByte()) savedFireState = curState
                        val saved = savedFireState ?: 0
                        when {
                            joy == 0L || curState == null -> firePulled = false
                            !firePulled -> {
                                if (shouldFire && joyOk && now - lastFireMs >= TRIGGER_COOLDOWN_MS &&
                                    writeBytes(cp, joy + JOY_FIRE_STATE, byteArrayOf(1))
                                ) {
                                    firePulled = true
                                    fireStartMs = now
                                    lastFireMs = now
                                    trigFireWrites++
                                    trigSuccessCount++
                                    // Per-enemy motion captured at this exact fire instant.
                                    logPredict("TRIGF",
                                        "pull#$trigSuccessCount key=$key raw=${"%.0f".format(snap.rawDist)} " +
                                            "pred=${"%.0f".format(snap.predictedDist)} los=$losSource " +
                                            "v=${"%.0f,%.0f".format(snap.vx, snap.vy)}[${snap.velSrc}] " +
                                            "leadVec=${"%.1f,%.1f".format(snap.leadX, snap.leadY)}")
                                }
                            }
                            else -> {
                                // Re-assert every tick. The game writes +0x18a from its
                                // own touch handlers, so a single 40 ms pulse could be
                                // cleared before Joypad::fire() sampled it.
                                if (writeBytes(cp, joy + JOY_FIRE_STATE, byteArrayOf(1))) trigFireWrites++
                                val held = now - fireStartMs
                                if ((!shouldFire && held >= TRIGGER_HOLD_MS) || held >= TRIGGER_MAX_HOLD_MS) {
                                    if (writeBytes(cp, joy + JOY_FIRE_STATE, byteArrayOf(saved))) trigReleaseWrites++
                                    firePulled = false
                                    lastFireMs = now
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        PatchLogger.log("TRIG", "loop: ${t.message}")
                    }
                    Thread.sleep(16)
                }
                releaseFire(p, b, joy)
            }.apply { isDaemon = true; start() }
            return true
        } else {
            triggerRunning = false
            triggerThread?.interrupt()
            try { triggerThread?.join(200) } catch (_: InterruptedException) {}
            triggerThread = null
            val pid = findPid()
            val base = pid?.let { findModuleBase(it) }
            if (pid != null && base != null) {
                releaseFire(pid, base, joypadPointer(pid, base) ?: 0L)
                unpublishJoypadPointer(pid, base)
            }
            savedFireState = null
            return true
        }
    }

    // ---- auto grenade ----------------------------------------------------
    // RE summary (cocos2dcpp_disasm.txt line numbers):
    //   HUD::onGrenade 0x667858 calls the local controller's virtual slot +0x4f8,
    //   resolved via _ZTV22SoldierLocalController to SoldierLocalController::throwDual
    //   0x8edf34 (disasm:1145063). throwDual posts the "GrenadeLaunched" notification
    //   (0x108b0a0) with 9 params; ProjectileManager::grenadeLaunched 0x804980 is the
    //   registered observer (init disasm:902521) and calls addGrenade 0x806820.
    //   The throw ANGLE is the float returned by SoldierLocalController::getFireAngle
    //   (vptr+0x480, disasm:1145207-1145209), i.e. the same joypad angle aimlock writes.
    //   addGrenade converts it with -PI/180 (0x1084a18, disasm:904919-904922) and
    //   launches at a fixed speed of 40.0 (mov w0,#0x28, disasm:1145286/1145296).
    //   FRAGNADE fuse: [weapon+0x190] accumulates dt and detonates above 1.75 s
    //   (disasm:416451-416459).
    // There is NO joypad byte or other writable field that initiates a throw: the
    // only local trigger is the HUD menu callback. Auto Grenade therefore arms the
    // ballistic aim entirely through memory writes and leaves the button tap to the
    // operator (least-risky alternative; no cocos API cave, no in-game call).

    private const val GRENADE_THROW_SPEED = 40.0       // disasm:1145286
    private const val GRENADE_FUSE_SECONDS = 1.75      // disasm:416451
    private const val GRAVITY_FACTOR_GLOBAL = 0x13c20a8L // gravityFactor OBJECT, dynsyms line 1976
    // GameCustomizeLayer::createGravityRow sets the base gravity to 25.0
    // (disasm:453146 `fmov s0, #25.0`). MapManager::getGravityFactor (0x6dbb94)
    // returns a MULTIPLIER from [this+0x18] (disasm:595351), not raw gravity.
    // Using the factor alone produced g=1.0 and a ~9 s flight / near-vertical
    // throw, which crashed the game on the first attempted grenade.
    private const val GRENADE_BASE_GRAVITY = 25.0
    private const val GRENADE_GRAVITY_FALLBACK = GRENADE_BASE_GRAVITY
    private const val GRENADE_MAX_RANGE = 1600.0
    private const val GRENADE_COOLDOWN_MS = 1500L

    // ---- true auto grenade: call throwDual from UPDATE, never from getFireAngle ----
    // RE evidence (cocos2dcpp_disasm.txt / ELF relocations):
    //   SoldierLocalController::updateStep 0x8e4d5c (disasm:1135605) opens with
    //   `str x28, [sp, #-0x20]!`. It is a per-frame UPDATE member function and x0 at
    //   entry is `this` - the local controller - which is throwDual's only argument.
    //   ELF .rela.dyn: ZTV22SoldierLocalController+0x10 (vptr 0x1352e00) slot +0x4f8
    //   resolves to _ZN22SoldierLocalController9throwDualEv (0x8edf34); slot +0x480
    //   resolves to getFireAngle (0x8e6858 -> SoldierView::getFiringAngle), and slot
    //   +0x618 resolves to getGrenadeCount (0x8edea4).
    //   The manual path HUD::onPressGrenadeButton 0x66aa9c -> onGrenade 0x667858 ->
    //   vptr+0x4f8 runs on the GLThread in the update/event phase. Hooking updateStep
    //   puts our call in that same phase, so it never touches the DRAW path that
    //   killed the seven render/label caves. Entering at getFireAngle instead would
    //   re-enter the cave on both of throwDual's getFireAngle calls (0x8ee068,
    //   0x8ee210); entering at updateStep cannot, because neither call reaches it.
    private const val GREN_HOOK_OFFSET = 0x8e4d5cL
    private const val GREN_HOOK_ORIG_HEX = "FC0F1EF8"      // str x28, [sp, #-0x20]!
    private const val GREN_HOOK_BRANCH_HEX = "E56FF017"    // b 0x500cf0 from 0x8e4d5c
    private const val GREN_CODE_OFFSET = 0x500cf0L         // .plt/.text gap, after joypad stub (ends 0x500ce8)
    private const val GREN_DATA_OFFSET = 0x500eb0L         // after LOS data (0x500e00 + 0xa0)
    private const val GREN_CODE_SIZE = 0x108
    private const val GREN_DATA_SIZE = 0x40
    private const val GREN_THROW_DUAL_OFFSET = 0x8edf34L
    private const val GREN_D_FLAGS = 0x00
    private const val GREN_D_REENTRY = 0x04
    private const val GREN_D_THROWFN = 0x08
    private const val GREN_D_EXPECT_VPTR = 0x10
    private const val GREN_D_ATTEMPTS = 0x18
    private const val GREN_D_BADVPTR = 0x20
    private const val GREN_F_ARM = 1
    private const val GREN_F_INTHROW = 2
    private const val LC_GRENADE_ARRAY = 0x2c8L            // getGrenadeCount disasm:1145036
    private const val CCARRAY_DATA = 0x18L                 // CCArray::count disasm:2026511
    private const val CCARRAY_OBJECTS = 0x08L              // objectAtIndex disasm:2026547

    // Keystone 0.9.2 / Capstone 5.0.7 assembled at 0x500cf0, disassembled and verified.
    // Saves x0-x30, arms on flags==1 with a vptr match, sets inThrow, blr throwDual,
    // clears flags, replays the overwritten `str x28,[sp,#-0x20]!`, returns to 0x8e4d60.
    private const val GRENADE_STUB_HEX =
        "FF0304D1E00700A9E20F01A9E41702A9E61F03A9E82704A9EA2F05A9EC3706A9EE3F07A9F04708A9" +
        "F24F09A9F4570AA9F65F0BA9F8670CA9FA6F0DA9FC770EA9FE7F0FA9" +
        "0900009029C13A912B0140B96C0080526C010C0A9F050071610200540D0040F92E0940F9BF010EEB" +
        "610100544C0080522C0100B92C0D40F98C0500912C0D00F9300540F900023FD60C0080522C0100B9" +
        "0A0000142C1140F98C0500912C1100F9060000144B000837040000142C4140F88C0500912C4100F8" +
        "E00740A9E20F41A9E41742A9E61F43A9E82744A9EA2F45A9EC3746A9EE3F47A9F04748A9F24F49A9" +
        "F4574AA9F65F4BA9F8674CA9FA6F4DA9FC774EA9FE7F4FA9FF030491FC0F1EF8DB8F0F14"

    @Volatile private var grenadeHookInstalled = false
    @Volatile private var grenadeHookPid = -1
    @Volatile private var grenadeHookBase = 0L
    private val grenadeHookSaved = HashMap<Long, ByteArray>()
    @Volatile private var grenadeDiag = "not installed"
    @Volatile private var grenadeFailReason = "off"
    @Volatile private var grenadeCountBefore = -1
    @Volatile private var grenadeCountAfter = -1

    private data class GrenadeArc(
        val angleDeg: Float, val distance: Double, val speed: Double,
        val gravity: Double, val flightSeconds: Double, val reachable: Boolean,
    )

    @Volatile var autoGrenadeEnabled = false
        private set
    @Volatile private var autoGrenadeInfo: String = "off"
    @Volatile private var autoGrenadeArmed = false
    @Volatile private var grenadeThrowRequested = false
    @Volatile private var grenadeOverrideAngle = Float.NaN
    private var autoGrenadeThread: Thread? = null
    private var lastGrenadeMs = 0L

    /** Effective gravity = base 25.0 x the game's gravityFactor multiplier. */
    private fun grenadeGravityOf(pid: Int, base: Long): Pair<Double, String> {
        fun fromFactor(v: Double?, tag: String): Pair<Double, String>? {
            if (v == null || !finite(v) || v !in 0.05..20.0) return null
            val g = GRENADE_BASE_GRAVITY * v
            return if (finite(g) && g in 1.0..1000.0) g to tag else null
        }
        fromFactor(readFloat(pid, base + GRAVITY_FACTOR_GLOBAL)?.toDouble(), "base25*f32")
            ?.let { return it }
        fromFactor(readInt(pid, base + GRAVITY_FACTOR_GLOBAL)?.toDouble(), "base25*i32")
            ?.let { return it }
        return GRENADE_GRAVITY_FALLBACK to "fallback(FILL)"
    }

    /**
     * Low-arc ballistic solution toward a same-height target. The game fixes launch
     * speed and gravity, so only the angle is ours to choose:
     *   disc = v^4 - g*(g*d^2 + 2*h*v^2)
     *   theta = atan((v^2 - sqrt(disc)) / (g*d))
     * Three refinement passes fold shooter velocity into the required launch vector.
     */
    private fun solveGrenadeArc(
        ox: Double, oy: Double, tx: Double, ty: Double,
        shooterVx: Double, shooterVy: Double, speed: Double, gravity: Double,
    ): GrenadeArc {
        val d = kotlin.math.abs(tx - ox)
        val h = ty - oy
        if (speed <= 0.0 || gravity <= 0.0 || d < 1.0 || d > GRENADE_MAX_RANGE) {
            return GrenadeArc(Float.NaN, d, speed, gravity, 0.0, false)
        }
        val v2 = speed * speed
        val disc = v2 * v2 - gravity * (gravity * d * d + 2.0 * h * v2)
        if (!finite(disc) || disc < 0.0) {
            return GrenadeArc(Float.NaN, d, speed, gravity, 0.0, false)
        }
        var theta = kotlin.math.atan((v2 - kotlin.math.sqrt(disc)) / (gravity * d))
        var t = 0.0
        repeat(3) {
            t = d / (speed * kotlin.math.cos(theta).coerceAtLeast(0.05))
            val needVx = d / t.coerceAtLeast(0.01) - shooterVx
            val needVy = h / t.coerceAtLeast(0.01) + 0.5 * gravity * t - shooterVy
            theta = kotlin.math.atan2(needVy, needVx)
        }
        val deg = (theta * 180.0 / Math.PI).toFloat()
        return GrenadeArc(deg, d, speed, gravity, t, finite(deg.toDouble()))
    }

    /**
     * getGrenadeCount() (0x8edea4) sums the CCArray counts of the per-type arrays held
     * in [lc+0x2c8]. CCArray layout: data pointer at +0x18, count at [data], object
     * array at [data+8] (CCArray::count 0xc37d04, objectAtIndex 0xc37d6c).
     */
    private fun grenadeCountOf(pid: Int, lc: Long): Int? {
        val arr = readLong(pid, lc + LC_GRENADE_ARRAY) ?: return null
        if (arr == 0L) return 0
        val data = readLong(pid, arr + CCARRAY_DATA) ?: return null
        if (data == 0L) return 0
        val groups = readInt(pid, data) ?: return null
        if (groups <= 0 || groups > 64) return 0
        val objects = readLong(pid, data + CCARRAY_OBJECTS) ?: return null
        if (objects == 0L) return 0
        var total = 0
        for (i in 0 until groups) {
            val sub = readLong(pid, objects + i * 8L) ?: return null
            if (sub == 0L) continue
            val subData = readLong(pid, sub + CCARRAY_DATA) ?: return null
            if (subData == 0L) continue
            total += maxOf(0, readInt(pid, subData) ?: 0)
        }
        return total
    }

    /** Writes data, cave code, then the 4-byte branch. Never partially arms on failure. */
    private fun installGrenadeHook(pid: Int, base: Long): Boolean {
        if (grenadeHookInstalled && grenadeHookPid == pid && grenadeHookBase == base) return true
        val hookAddr = base + GREN_HOOK_OFFSET
        val orig = readBytes(pid, hookAddr, 4)
        if (orig == null || !orig.contentEquals(hexToBytes(GREN_HOOK_ORIG_HEX))) {
            grenadeFailReason = "entry bytes: " +
                (orig?.joinToString(" ") { "%02X".format(it.toInt() and 0xff) } ?: "unreadable")
            PatchLogger.log("GREN", "install FAILED - unexpected updateStep prologue ($grenadeFailReason)")
            return false
        }
        val code = hexToBytes(GRENADE_STUB_HEX)
        val existing = readBytes(pid, base + GREN_CODE_OFFSET, code.size)
        if (existing == null || (!existing.contentEquals(code) && !existing.all { it == 0.toByte() })) {
            grenadeFailReason = "code cave occupied"
            PatchLogger.log("GREN", "install FAILED - $grenadeFailReason")
            return false
        }
        val d = ByteBuffer.allocate(GREN_DATA_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        d.putInt(GREN_D_FLAGS, 0)
        d.putInt(GREN_D_REENTRY, 0)
        d.putLong(GREN_D_THROWFN, base + GREN_THROW_DUAL_OFFSET)
        d.putLong(GREN_D_EXPECT_VPTR, base + LOCAL_CONTROLLER_VTABLE)
        d.putLong(GREN_D_ATTEMPTS, 0)
        d.putLong(GREN_D_BADVPTR, 0)
        if (!writeBytes(pid, base + GREN_DATA_OFFSET, d.array()) ||
            !writeBytes(pid, base + GREN_CODE_OFFSET, code) ||
            !writeBytes(pid, hookAddr, hexToBytes(GREN_HOOK_BRANCH_HEX))
        ) {
            // Roll back whatever landed so a half-written hook can never fire.
            writeBytes(pid, hookAddr, orig)
            writeBytes(pid, base + GREN_CODE_OFFSET, ByteArray(GREN_CODE_SIZE))
            grenadeFailReason = "memory write failed"
            PatchLogger.log("GREN", "install FAILED - $grenadeFailReason (rolled back)")
            return false
        }
        grenadeHookSaved[hookAddr] = orig
        grenadeHookInstalled = true
        grenadeHookPid = pid
        grenadeHookBase = base
        grenadeFailReason = "-"
        grenadeDiag = "entry=0x8e4d5c stub=0x500cf0 data=0x500eb0 " +
            "orig=$GREN_HOOK_ORIG_HEX branch=$GREN_HOOK_BRANCH_HEX"
        PatchLogger.log("GREN", "hook INSTALLED: $grenadeDiag")
        return true
    }

    /** Complete kill switch: branch, cave bytes and data are all restored. */
    private fun uninstallGrenadeHook(fallbackPid: Int? = null, fallbackBase: Long? = null) {
        if (!grenadeHookInstalled) return
        val pid = if (grenadeHookPid > 0) grenadeHookPid else (fallbackPid ?: -1)
        val base = if (grenadeHookBase != 0L) grenadeHookBase else (fallbackBase ?: 0L)
        if (pid > 0 && base != 0L) {
            grenadeHookSaved.remove(base + GREN_HOOK_OFFSET)?.let {
                writeBytes(pid, base + GREN_HOOK_OFFSET, it)
            }
            writeBytes(pid, base + GREN_CODE_OFFSET, ByteArray(GREN_CODE_SIZE))
            writeBytes(pid, base + GREN_DATA_OFFSET, ByteArray(GREN_DATA_SIZE))
        }
        grenadeHookInstalled = false
        grenadeHookPid = -1
        grenadeHookBase = 0L
        grenadeCountBefore = -1
        grenadeCountAfter = -1
        grenadeDiag = "uninstalled - all bytes restored"
        PatchLogger.log("GREN", "hook UNINSTALLED - branch/cave/data restored")
    }

    /** Publishes the ballistic angle before the one-shot arm so the cave cannot read a stale aim. */
    private fun armGrenadeThrow(pid: Int, base: Long, angleDeg: Float) {
        if (!grenadeHookInstalled || grenadeHookBase != base) return
        writeFloat(pid, base + FIRE_ANGLE_OFFSET + 20, angleDeg)
        writeBytes(
            pid,
            base + GREN_DATA_OFFSET + GREN_D_FLAGS,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(GREN_F_ARM).array(),
        )
        grenadeThrowRequested = true
    }

    private fun grenadeHookStats(pid: Int, base: Long): String {
        if (!grenadeHookInstalled) return "not installed ($grenadeFailReason)"
        val d = base + GREN_DATA_OFFSET
        val flags = readInt(pid, d + GREN_D_FLAGS)
        val reentry = readInt(pid, d + GREN_D_REENTRY)
        val attempts = readLong(pid, d + GREN_D_ATTEMPTS)
        val badvptr = readLong(pid, d + GREN_D_BADVPTR)
        val branch = readBytes(pid, base + GREN_HOOK_OFFSET, 4)
        val branchOk = branch?.contentEquals(hexToBytes(GREN_HOOK_BRANCH_HEX)) == true
        return "installed=true branch=$branchOk flags=$flags attempts=$attempts " +
            "reentry=$reentry badvptr=$badvptr orig=$GREN_HOOK_ORIG_HEX branch=$GREN_HOOK_BRANCH_HEX"
    }

    fun toggleAutoGrenade(enable: Boolean): Boolean {
        if (!enable) {
            autoGrenadeEnabled = false
            autoGrenadeThread?.interrupt()
            try { autoGrenadeThread?.join(200) } catch (_: InterruptedException) {}
            autoGrenadeThread = null
            // Kill switch: stop arming first, then restore every byte we patched.
            grenadeOverrideAngle = Float.NaN
            grenadeThrowRequested = false
            val p = findPid()
            val b = p?.let { findModuleBase(it) }
            uninstallGrenadeHook(p, b)
            autoGrenadeArmed = false
            grenadeCountBefore = -1
            grenadeCountAfter = -1
            autoGrenadeInfo = "off"
            PatchLogger.log("GREN", "auto grenade disabled - hook removed")
            return true
        }
        if (autoGrenadeEnabled) return true
        val pid = findPid() ?: return false
        val base = findModuleBase(pid) ?: return false
        // The in-game throwDual cave was removed: it crashed the game on its first
        // call twice with an identical fault address, because the arc solver can
        // produce a >90 s backward launch. Ballistic aim stays external and safe;
        // the operator completes the throw with the real HUD grenade button.
        autoGrenadeEnabled = true
        lastGrenadeMs = 0L
        grenadeCountBefore = -1
        grenadeCountAfter = -1
        PatchLogger.log("GREN", "grenade ballistic aim ON - tap HUD grenade to throw (no in-game call)")

        autoGrenadeThread = Thread {
            var p = pid
            var b = base
            var lastAttempts = 0L
            while (autoGrenadeEnabled) {
                // Fail closed every pass; only the armed branch may override aim.
                grenadeOverrideAngle = Float.NaN
                try {
                    val cp = findPid()
                    val cb = cp?.let { findModuleBase(it) }
                    if (cp == null || cb == null) {
                        autoGrenadeArmed = false
                        Thread.sleep(200)
                        continue
                    }
                    p = cp
                    b = cb
                    val (gravity, gravitySrc) = grenadeGravityOf(p, b)
                    val lc = writableLocalController(p, b)
                    val origin = lc?.let { soldierPosition(p, it) }
                    val localVel = lc?.let { readLong(p, it + 0x1a8) }
                        ?.let { body -> readVec(p, body + BODY_VEL_X) }
                    val count = lc?.let { grenadeCountOf(p, it) }
                    val now = System.currentTimeMillis()
                    val stats = grenadeHookStats(p, b)

                    // The cave increments this counter immediately after blr throwDual.
                    val attempts = readLong(p, b + GREN_DATA_OFFSET + GREN_D_ATTEMPTS) ?: lastAttempts
                    if (attempts > lastAttempts) {
                        grenadeCountAfter = count ?: -1
                        lastAttempts = attempts
                    }

                    var snap = "g=${"%.2f".format(gravity)}[$gravitySrc] nade=$count $stats"

                    if (origin != null && sceneStable(p, b)) {
                        val enemies = collectTargets(p, b, withVelocity = true)
                            .filter { it.kind == KIND_PLAYER && !it.friendly }
                        // The proven LOS cave probes only the aimlock's nearest target,
                        // so the crouched enemy must be that same nearest enemy.
                        val target = enemies.minByOrNull {
                            hypot(it.x - origin.first, it.y - origin.second)
                        }
                        val duckCount = enemies.count { it.crouched }
                        if (target != null && target.crouched) {
                            val rawDist = hypot(target.x - origin.first, target.y - origin.second)
                            val visible = losVisible(p, b) == true
                            val arc = solveGrenadeArc(
                                origin.first, origin.second, target.x, target.y,
                                localVel?.first ?: 0.0, localVel?.second ?: 0.0,
                                GRENADE_THROW_SPEED, gravity,
                            )
                            val inRange = rawDist <= GRENADE_MAX_RANGE && arc.reachable && visible
                            val haveGrenade = (count ?: 0) > 0
                            grenadeFailReason = when {
                                !haveGrenade -> "no grenades (count=$count)"
                                !visible -> "LOS blocked"
                                !arc.reachable -> "arc unreachable d=${"%.0f".format(rawDist)}"
                                else -> "-"
                            }
                            if (inRange && haveGrenade) {
                                grenadeOverrideAngle = arc.angleDeg
                                autoGrenadeArmed = true
                                grenadeCountBefore = count ?: -1
                                if (now - lastGrenadeMs >= GRENADE_COOLDOWN_MS) {
                                    // Pure memory write: publish the solved angle and signal
                                    // readiness. No game code is called from a cave.
                                    writeFloat(p, b + FIRE_ANGLE_OFFSET + 20, arc.angleDeg)
                                    grenadeThrowRequested = true
                                    lastGrenadeMs = now
                                }
                            } else {
                                autoGrenadeArmed = false
                            }
                            snap = "crouch=true d=${"%.0f".format(rawDist)} " +
                                "los=${if (visible) "yes" else "no"} " +
                                "before=$grenadeCountBefore after=$grenadeCountAfter " +
                                "ang=${"%.1f".format(arc.angleDeg)} " +
                                "t=${"%.2f".format(arc.flightSeconds)} " +
                                "g=${"%.2f".format(gravity)}[$gravitySrc] " +
                                "armed=$autoGrenadeArmed range=${if (inRange) "pass" else "fail"} " +
                                "reason=${grenadeFailReason} $stats"
                        } else {
                            autoGrenadeArmed = false
                            grenadeFailReason = "no crouched nearest (enemies=${enemies.size} crouched=$duckCount)"
                            snap = "g=${"%.2f".format(gravity)}[$gravitySrc] $grenadeFailReason $stats"
                        }
                    } else {
                        autoGrenadeArmed = false
                        grenadeFailReason = "scene/controller not writable"
                        snap = "g=${"%.2f".format(gravity)}[$gravitySrc] $grenadeFailReason $stats"
                    }
                    autoGrenadeInfo = snap
                    logPredict("GREN", snap)
                } catch (t: Throwable) {
                    autoGrenadeArmed = false
                    grenadeFailReason = "loop: ${t.message}"
                    autoGrenadeInfo = grenadeFailReason
                    PatchLogger.log("GREN", "loop: ${t.message}")
                }
                Thread.sleep(32)
            }
            autoGrenadeArmed = false
            grenadeOverrideAngle = Float.NaN
        }.apply { isDaemon = true; name = "auto-grenade"; start() }
        return true
    }

    /** Arms request flag set by [armGrenadeThrow]; retained for compatibility/telemetry. */
    fun consumeGrenadeThrowRequest(): Boolean {
        val v = grenadeThrowRequested
        grenadeThrowRequested = false
        return v
    }

    /** Everything needed to diagnose external-ESP alignment, logged every ~2 s. */
    fun espAlignmentDebug(pid: Int, base: Long, viewW: Int, viewH: Int): String {
        fun hx(v: Long?) = v?.let { "0x${it.toString(16)}" } ?: "null"
        fun n(n: NodeInfo) =
            "p=${n.px.toInt()},${n.py.toInt()} s=${"%.3f".format(n.sx)},${"%.3f".format(n.sy)} " +
                "a=${n.ax.toInt()},${n.ay.toInt()} c=${n.cw.toInt()},${n.ch.toInt()}"
        val sb = StringBuilder()
        sb.appendLine("=== ESP ALIGN ===")
        val director = readLong(pid, base + DIRECTOR_GLOBAL)
        val width = director?.let { readFloat(pid, it + 0xd0) }
        val height = director?.let { readFloat(pid, it + 0xd4) }
        val csf = director?.let { readFloat(pid, it + 0xd8) }
        val scene = director?.let { readLong(pid, it + 0xa0) }
        sb.appendLine("director=${hx(director)} scene=${hx(scene)} win=${width?.toInt()}x${height?.toInt()} csf=$csf")
        val w = width ?: 0f
        val h = height ?: 0f
        sb.appendLine("view=${viewW}x${viewH} kx=${if (w > 0f) viewW / w else 1f} ky=${if (h > 0f) viewH / h else 1f}")
        val c = camera(pid, base, viewW, viewH)
        sb.appendLine("chosen cam off=${c?.x?.toInt()},${c?.y?.toInt()} scale=${c?.scaleX},${c?.scaleY}")

        if (scene != null && scene != 0L) {
            var idx1 = 0
            for (c1 in children(pid, scene)) {
                if (idx1++ >= 6) break
                val n1 = nodeInfo(pid, c1) ?: continue
                sb.appendLine("c1[${idx1 - 1}]=${hx(c1)} ${n(n1)}")
                var idx2 = 0
                for (c2 in children(pid, c1)) {
                    if (idx2++ >= 6) break
                    val n2 = nodeInfo(pid, c2) ?: continue
                    val mag = kotlin.math.abs(n2.px) + kotlin.math.abs(n2.py)
                    sb.appendLine("  c2[${idx2 - 1}]=${hx(c2)} ${n(n2)} mag=${mag.toInt()}")
                    var idx3 = 0
                    for (c3 in children(pid, c2)) {
                        if (idx3++ >= 3) break
                        val n3 = nodeInfo(pid, c3) ?: continue
                        sb.appendLine("    c3[${idx3 - 1}]=${hx(c3)} ${n(n3)} mag=${(kotlin.math.abs(n3.px) + kotlin.math.abs(n3.py)).toInt()}")
                    }
                }
            }
        } else {
            sb.appendLine("scene missing - cannot walk hierarchy")
        }

        val projCsf = (c?.csf ?: 1f).let { if (it > 0f) it else 1f }
        val scale = if (c != null && c.width * projCsf > 0f && c.height * projCsf > 0f)
            minOf(viewW / (c.width * projCsf), viewH / (c.height * projCsf)) else 1f
        sb.appendLine("projScale=$scale csf=$projCsf")
        sb.appendLine("A=${c?.scaleX},${c?.scaleY} B=${c?.x?.toInt()},${c?.y?.toInt()} " +
            "letterbox=${c?.offsetX?.toInt()},${c?.offsetY?.toInt()} pxPerPoint=${c?.viewScale} " +
            "anchor=${(viewW * PLAYER_ANCHOR_X).toInt()},${(viewH * PLAYER_ANCHOR_Y).toInt()}")
        fun screen(x: Double, y: Double): String {
            if (c == null) return "no cam"
            val px = c.offsetX + (c.x + x.toFloat() * c.scaleX) * c.viewScale
            val py = viewH - (c.offsetY + (c.y + y.toFloat() * c.scaleY) * c.viewScale)
            return "${px.toInt()},${py.toInt()}"
        }
        val local = localPosition(pid, base)
        sb.appendLine("local raw=${local?.first?.toInt()},${local?.second?.toInt()} screen=${local?.let { screen(it.first, it.second) }}")
        var i = 0
        for (t in collectTargets(pid, base)) {
            if (i++ >= 8) break
            sb.appendLine("target kind=${t.kind} raw=${t.x.toInt()},${t.y.toInt()} screen=${screen(t.x, t.y)}")
        }
        return sb.toString()
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
        sb.appendLine("teamCheck=$teamCheck localTeam=${localTeamId(pid, base)}")
        players.take(5).forEach { ctrl ->
            val pos = soldierPosition(pid, ctrl)
            val team = teamIdOf(pid, ctrl)
            sb.appendLine("  ${hx(ctrl)} team=$team hostile=${isHostile(team, localTeamId(pid, base), KIND_PLAYER)} " +
                "body=${hx(readLong(pid, ctrl + 0x1a8))} xy=${pos?.first?.toInt()},${pos?.second?.toInt()}")
        }
        val ld = base + LOS_DATA_OFFSET
        sb.appendLine("LOS: active=$losActive visible=${readInt(pid, ld + 4)} " +
            "space=${hx(readLong(pid, ld + 0x40))} result=${hx(readLong(pid, ld + 0x48))} " +
            "start=${losOrigin.first.toInt()},${losOrigin.second.toInt()} " +
            "end=${losTarget.first.toInt()},${losTarget.second.toInt()}")
        val povLc = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)?.let { readLong(pid, it) }
        fun f4(v: Float?) = v?.let { "%.4f".format(it) } ?: "?"
        sb.appendLine("POV: pct=$povPercent req=${f4(povScale)} lc=${hx(povLc)} " +
            "field=${if (povLc != null) f4(readFloat(pid, povLc + POV_LC_FIELD)) else "?"} " +
            "smooth=${if (povLc != null) f4(readFloat(pid, povLc + POV_LC_SMOOTH)) else "?"} " +
            "legacy=${if (povLc != null) f4(readFloat(pid, povLc + POV_LC_LEGACY)) else "?"}")
        fun f1(v: Double) = "%.1f".format(v)
        val a = aimPredict
        sb.appendLine("DIRECT AIM: w=${a.weapon} tgt=${a.targetKind} " +
            "d=${f1(a.rawDist)} key=${a.targetKey} " +
            "deg=${f1(a.leadAngle.toDouble())}")
        sb.appendLine("=== AIM TARGET ===")
        sb.appendLine("  weapon=${a.weapon} key=${a.targetKey} kind=${a.targetKind} " +
            "rawDist=${f1(a.rawDist)} angleDeg=${"%.1f".format(a.leadAngle)}")
        sb.appendLine("  direct aim - no lead/velocity/muzzle model in use")
        val g = trigPredict
        sb.appendLine("PREDICT TRIG: w=${g.weapon} range=${f1(g.range)} speed=${f1(g.speed)} " +
            "tgt=${g.targetKind} d=${f1(g.rawDist)} v=${f1(g.vx)},${f1(g.vy)}[${g.velSrc}] " +
            "a=${f1(g.ax)},${f1(g.ay)} tt=${"%.3f".format(g.travel)} lead=${f1(g.predictedDist)} " +
            "limit=${f1(g.gateLimit)} gate=${g.gatePass}")
        sb.appendLine("TRIG: active=$triggerRunning key=$trigTargetKey " +
            "raw=${f1(g.rawDist)} pred=${f1(g.predictedDist)} los=$trigLosSource " +
            "reason=$trigLastReason joy=${hx(joypadPointer(pid, base))} joyOk=${
                joypadPointer(pid, base)?.let { joypadUsable(pid, it) } ?: false
            } pulled=$firePulled " +
            "fireWrites=$trigFireWrites releaseWrites=$trigReleaseWrites shots=$trigSuccessCount")
        val (grenGravity, grenSrc) = grenadeGravityOf(pid, base)
        sb.appendLine("GRENADE: enabled=$autoGrenadeEnabled armed=$autoGrenadeArmed " +
            "speed=${f1(GRENADE_THROW_SPEED)} fuse=${f1(GRENADE_FUSE_SECONDS)} " +
            "g=${"%.2f".format(grenGravity)}[$grenSrc] $autoGrenadeInfo")
        sb.appendLine("GREN HOOK: ${grenadeHookStats(pid, base)}")
        sb.appendLine("GREN CAVE: $grenadeDiag")
        sb.appendLine("GREN COUNT: before=$grenadeCountBefore after=$grenadeCountAfter " +
            "requested=$grenadeThrowRequested reason=$grenadeFailReason")
        players.take(8).forEach { ctrl ->
            val flag = readBytes(pid, ctrl + CTRL_DUCKED, 1)?.get(0)?.toInt()
            val shape = readInt(pid, ctrl + CTRL_DUCKED_SHAPE)
            sb.appendLine("  ${hx(ctrl)} duckedByte=${flag?.and(1)} shape+0x214=$shape " +
                "crouched=${isDucked(pid, ctrl)}")
        }
        sb.appendLine("JOIN CAPTURE: ${joinCaptureStatus()}")
        sb.append(weaponInfo(pid, base))
        return sb.toString()
    }

    // ---- multiplayer join capture -----------------------------------------
    // A read-only, bounded sampler. It never writes game memory and never calls
    // game code; every value comes from the existing /proc/<pid>/mem reader.

    private val JOIN_GROUPS = listOf(
        "PID", "BASE", "SCENE", "WIN", "LC", "TEAM", "POS", "PLAYERS", "DRONES",
        "WEAPON", "NM", "STATE", "SESSION", "PEERS", "NETG",
    )
    private val joinBuffer = ArrayDeque<String>()
    private var joinPrev = LinkedHashMap<String, String>()
    @Volatile private var joinRunning = false
    private var joinThread: Thread? = null
    private var joinStartMs = 0L
    private var joinStartedAt = "-"
    private var joinPid = -1
    private var joinBase = 0L
    private var joinLastHeartbeat = 0L
    @Volatile private var joinTotalSnapshots = 0L
    @Volatile private var joinEmitted = 0L
    @Volatile private var joinDropped = 0
    @Volatile private var joinErrors = 0
    @Volatile private var joinLogcatBaseline = ""

    private val joinLogcatPattern = Regex(
        "join|room|connect|multiplayer|\\bmm\\b|network|host|lobby|session|peer|raknet",
        RegexOption.IGNORE_CASE,
    )

    private fun jhex(v: Long?): String = v?.let { "0x${it.toString(16)}" } ?: "null"

    private fun joinElapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d.%03d".format(total / 3600, (total % 3600) / 60, total % 60, ms % 1000)
    }

    /** One event, timestamped from capture start, into both the ring buffer and PatchLogger. */
    private fun joinEmit(message: String) {
        val elapsed = if (joinStartMs > 0L) System.currentTimeMillis() - joinStartMs else 0L
        val line = "[t+%05dms] %s".format(elapsed, message)
        synchronized(joinBuffer) {
            joinBuffer.addLast(line)
            while (joinBuffer.size > JOIN_MAX_LINES) {
                joinBuffer.removeFirst()
                joinDropped++
            }
        }
        PatchLogger.log("JOIN", line)
    }

    /** libc++ std::string: low bit of byte 0 selects short (inline) vs long (pointer+size). */
    private fun readStdString(pid: Int, addr: Long, maxChars: Int = 24): String {
        if (addr == 0L) return "-"
        val h = readBytes(pid, addr, 24) ?: return "?"
        val raw: ByteArray
        val len: Int
        if (h[0].toInt() and 0x1 == 1) {
            var p = 0L
            var n = 0L
            for (i in 0..7) p = p or ((h[i].toLong() and 0xff) shl (8 * i))
            for (i in 8..15) n = n or ((h[i].toLong() and 0xff) shl (8 * i))
            if (p == 0L || n <= 0 || n > 4096L) return "str@${jhex(p)}"
            len = minOf(n.toInt(), maxChars)
            raw = readBytes(pid, p, len) ?: return "str@${jhex(p)}"
        } else {
            len = minOf((h[0].toInt() and 0xff) ushr 1, maxChars)
            if (len <= 0) return ""
            raw = h.copyOfRange(1, 1 + len)
        }
        val sb = StringBuilder()
        for (b in raw) {
            val c = b.toInt() and 0xff
            sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
        }
        return sb.toString()
    }

    /** The getters at 0x13bf448 etc. hold a pointer to the scalar, not the scalar. */
    private fun netScalar(pid: Int, base: Long, global: Long): Pair<String, Int> {
        val raw = readLong(pid, base + global) ?: 0L
        val deref = if (raw != 0L) readInt(pid, raw) else null
        val value = deref ?: if (raw in 0L..255L) raw.toInt() else -1
        return "${jhex(raw)}->$value" to value
    }

    /** cocos2d CCDictionary::count() disasm 0xc293e8-0xc2940c. */
    private fun dictCountAt(pid: Int, dict: Long): Int {
        if (dict == 0L) return 0
        val first = readLong(pid, dict + 0x18) ?: return 0
        if (first == 0L) return 0
        val owner = readLong(pid, first + 0x110) ?: return 0
        return readInt(pid, owner + 0x10) ?: 0
    }

    /** One complete read-only sample; each map entry is one change-mask group. */
    private fun sampleJoin(pid: Int, base: Long): LinkedHashMap<String, String> {
        val f = LinkedHashMap<String, String>()
        f["PID"] = pid.toString()
        f["BASE"] = jhex(base)

        val director = readLong(pid, base + DIRECTOR_GLOBAL)
        val scene = director?.let { readLong(pid, it + 0xa0) } ?: 0L
        f["SCENE"] = jhex(scene)
        val vw = director?.let { readFloat(pid, it + 0xd0) }
        val vh = director?.let { readFloat(pid, it + 0xd4) }
        f["WIN"] = if (vw != null && vh != null) "${vw.toInt()}x${vh.toInt()}" else "-"

        val lcHolder = readLong(pid, base + LOCAL_CONTROLLER_GLOBAL)
        val lc = lcHolder?.let { readLong(pid, it) } ?: 0L
        val lcVptr = if (lc != 0L) readLong(pid, lc) else null
        f["LC"] = "${jhex(lc)} v=${jhex(lcVptr)} vb=${jhex(lcVptr?.minus(base))}"
        val team = if (lc != 0L) readInt(pid, lc + TEAM_ID_OFFSET) ?: -1 else -1
        f["TEAM"] = team.toString()
        val body = if (lc != 0L) readLong(pid, lc + 0x1a8) else null
        val pos = body?.let { bodyPosition(pid, it) }
        f["POS"] = pos?.let { "${it.first.toInt()},${it.second.toInt()}" } ?: "-"

        val rpDict = readLong(pid, base + REMOTE_PLAYERS_GLOBAL)
            ?.let { readLong(pid, it) } ?: 0L
        val players = dictionaryObjects(pid, base, REMOTE_PLAYERS_GLOBAL)
        val parts = players.take(12).map { ctrl ->
            val blk = readBytes(pid, ctrl + TEAM_ID_OFFSET, 0x6c)
            val bb = blk?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
            val pt = bb?.getInt(0) ?: -1
            val pb = bb?.getLong(0x64) ?: 0L
            val pp = bodyPosition(pid, pb)
            "${jhex(ctrl)}:t$pt:${pp?.first?.toInt() ?: 0},${pp?.second?.toInt() ?: 0}"
        }
        f["PLAYERS"] = "${jhex(rpDict)}/${players.size}[${parts.joinToString(";")}]"
        f["DRONES"] = dictionaryObjects(pid, base, ALL_DRONES_GLOBAL).size.toString()
        f["WEAPON"] = if (lc != 0L) weaponNameOf(pid, activeWeapon(pid, lc)) else "-"

        // Prefer the exported singleton slot; fall back to the accessor's indirect
        // slot. Both pointers are logged so a different build can be re-derived.
        var nm = readLong(pid, base + NM_INSTANCE_GLOBAL) ?: 0L
        var nmPath = "inst"
        var nmVptr = if (nm != 0L) readLong(pid, nm) else null
        if (nm == 0L || nmVptr == null) {
            val slot = readLong(pid, base + NM_ACCESSOR_SLOT_GLOBAL) ?: 0L
            val alt = if (slot != 0L) readLong(pid, slot) ?: 0L else 0L
            if (alt != 0L) {
                nm = alt
                nmPath = "acc"
                nmVptr = readLong(pid, nm)
            }
        }
        f["NM"] = "${jhex(nm)}[$nmPath] v=${jhex(nmVptr)} vb=${jhex(nmVptr?.minus(base))} " +
            "expectVb=${jhex(NM_VTABLE)}"
        if (nm != 0L) {
            val con = readBytes(pid, nm + NM_CONNECTED, 1)?.get(0)?.toInt() ?: -1
            val inv = readBytes(pid, nm + NM_WAS_INVITED, 1)?.get(0)?.toInt() ?: -1
            val ing = readBytes(pid, nm + NM_IN_GAME, 1)?.get(0)?.toInt() ?: -1
            val max = readInt(pid, nm + NM_MAX_PLAYERS) ?: -1
            f["STATE"] = "con=$con inv=$inv ing=$ing max=$max"
            val session = readLong(pid, nm + NM_SESSION) ?: 0L
            val raknet = readLong(pid, nm + NM_RAKNET) ?: 0L
            f["SESSION"] = "ses=${jhex(session)} rk=${jhex(raknet)} " +
                "toJoin=${readStdString(pid, nm + NM_SESSION_TO_JOIN)} " +
                "local=${readStdString(pid, nm + NM_LOCAL_PEER_ID)} " +
                "leader=${readStdString(pid, nm + NM_LEADER_PEER_ID)}"
            val peerDict = readLong(pid, nm + NM_PEER_DICT) ?: 0L
            f["PEERS"] = "${jhex(peerDict)}/${dictCountAt(pid, peerDict)}"
        } else {
            for (g in listOf("STATE", "SESSION", "PEERS")) f[g] = "-"
        }
        val ct = netScalar(pid, base, NET_CONN_TYPE_GLOBAL)
        val rs = netScalar(pid, base, NET_REQ_SESSION_GLOBAL)
        val sd = netScalar(pid, base, NET_SESSION_DONE_GLOBAL)
        val qp = netScalar(pid, base, NET_QUICKPLAY_POP_GLOBAL)
        val sa = netScalar(pid, base, NET_SHOW_ASK_GLOBAL)
        f["NETG"] = "ct=${ct.first} req=${rs.first} done=${sd.first} pop=${qp.first} ask=${sa.first}"
        return f
    }

    private fun stopJoinCaptureInternal(reason: String) {
        joinRunning = false
        joinEmit("STOP reason=$reason snapshots=$joinTotalSnapshots")
        PatchLogger.log("JOIN", "capture stopped: $reason snapshots=$joinTotalSnapshots")
        joinThread?.interrupt()
    }

    private fun startJoinCaptureThread() {
        joinThread = Thread {
            var tick = 0
            while (joinRunning) {
                val t0 = System.currentTimeMillis()
                try {
                    // Re-validate the process every 4th tick (200 ms). A relaunch
                    // mid-join ends the capture instead of tracing a different PID.
                    if (tick++ % 4 == 0) {
                        val nowPid = findPid()
                        if (nowPid == null || nowPid != joinPid) {
                            joinEmit("PIDCHG now=$nowPid expected=$joinPid")
                            stopJoinCaptureInternal("pid change")
                            break
                        }
                        val nowBase = findModuleBase(nowPid)
                        if (nowBase == null || nowBase != joinBase) {
                            joinEmit("BASECHG now=${jhex(nowBase)} expected=${jhex(joinBase)}")
                            stopJoinCaptureInternal("module base change")
                            break
                        }
                    }
                    val fields = sampleJoin(joinPid, joinBase)
                    joinTotalSnapshots++
                    val changed = JOIN_GROUPS.filter { fields[it] != joinPrev[it] }
                    if (changed.isNotEmpty()) {
                        val detail = changed.joinToString(" ") { "$it=${fields[it]}" }
                        joinEmit("CHG ${changed.joinToString(",")} | $detail")
                        joinEmitted++
                        joinPrev = fields
                    }
                    val elapsed = t0 - joinStartMs
                    if (elapsed - joinLastHeartbeat >= JOIN_HEARTBEAT_MS) {
                        joinLastHeartbeat = elapsed
                        val core = listOf("SCENE", "LC", "PLAYERS", "DRONES", "WEAPON", "NM", "STATE", "PEERS")
                            .joinToString(" ") { "$it=${fields[it]}" }
                        joinEmit("HB | $core")
                        joinEmitted++
                    }
                } catch (t: Throwable) {
                    joinErrors++
                    if (joinErrors <= 5) joinEmit("ERR ${t.javaClass.simpleName}: ${t.message}")
                }
                val spent = System.currentTimeMillis() - t0
                if (spent < JOIN_SAMPLE_MS) {
                    try {
                        Thread.sleep(JOIN_SAMPLE_MS - spent)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "mp-join-capture"
            start()
        }
    }

    private fun joinRunLogcat(limit: Int): List<String> {
        // Fixed argument arrays: no untrusted string is ever built into a shell command.
        val commands = listOf(
            arrayOf("logcat", "-d", "-t", limit.toString()),
            arrayOf("su", "-c", "logcat -d -t $limit"),
        )
        for (command in commands) {
            try {
                val process = Runtime.getRuntime().exec(command)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (output.isNotBlank()) return output.lines()
            } catch (_: Exception) {
                // Try the next command.
            }
        }
        return emptyList()
    }

    private fun joinSanitize(line: String): String {
        val trimmed = line.trim().take(240)
        val sb = StringBuilder(trimmed.length)
        for (c in trimmed) sb.append(if (c.isISOControl() && c != '\t') ' ' else c)
        return sb.toString()
    }

    private fun joinMatchingLogcat(): List<String> {
        val lines = joinRunLogcat(1200)
        if (lines.isEmpty()) return emptyList()
        val startIdx = if (joinLogcatBaseline.isBlank()) {
            maxOf(0, lines.size - 400)
        } else {
            val idx = lines.indexOfFirst { joinSanitize(it) == joinLogcatBaseline }
            if (idx >= 0) idx + 1 else maxOf(0, lines.size - 400)
        }
        val out = LinkedHashSet<String>()
        for (line in lines.drop(startIdx)) {
            val s = joinSanitize(line)
            if (s.isNotBlank() && joinLogcatPattern.containsMatchIn(s)) out.add(s)
            if (out.size >= JOIN_MAX_LOGCAT) break
        }
        return out.toList()
    }

    /** Tap-on starts the sampler; returns false when the game is not running. */
    fun startJoinCapture(): Boolean {
        if (joinRunning) return false
        val attached = attach() ?: return false
        synchronized(joinBuffer) { joinBuffer.clear() }
        joinPrev.clear()
        joinPid = attached.first
        joinBase = attached.second
        joinStartMs = System.currentTimeMillis()
        joinStartedAt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(joinStartMs))
        joinTotalSnapshots = 0L
        joinEmitted = 0L
        joinDropped = 0
        joinErrors = 0
        joinLastHeartbeat = 0L
        joinLogcatBaseline = joinRunLogcat(40).lastOrNull { it.isNotBlank() }
            ?.let { joinSanitize(it) } ?: ""
        joinRunning = true
        joinEmit("START pid=$joinPid base=${jhex(joinBase)} sample=${JOIN_SAMPLE_MS}ms")
        PatchLogger.log("JOIN", "capture started pid=$joinPid base=${jhex(joinBase)}")
        startJoinCaptureThread()
        return true
    }

    /** Tap-off stops the sampler and returns the final dump. */
    fun stopJoinCapture(): String {
        if (joinRunning) stopJoinCaptureInternal("toggle off")
        joinThread?.let { thread ->
            try {
                thread.join(400)
            } catch (_: InterruptedException) {
            }
        }
        joinThread = null
        return joinCaptureDump()
    }

    /** Teardown path: stop without building a dump (no logcat work on the UI thread). */
    fun abortJoinCapture() {
        if (joinRunning) stopJoinCaptureInternal("app teardown")
        joinThread = null
    }

    fun joinCaptureRunning(): Boolean = joinRunning

    fun joinCaptureStatus(): String {
        val elapsed = if (joinStartMs > 0L) System.currentTimeMillis() - joinStartMs else 0L
        val buffered = synchronized(joinBuffer) { joinBuffer.size }
        return "running=${if (joinRunning) "ON" else "OFF"} elapsed=${joinElapsed(elapsed)} " +
            "snapshots=$joinTotalSnapshots emitted=$joinEmitted buf=$buffered/$JOIN_MAX_LINES"
    }

    /** Full bounded trace, appended verbatim to COPY LOGS via PatchLogger.appendBlock. */
    fun joinCaptureDump(): String {
        val sb = StringBuilder()
        sb.appendLine("=== MULTIPLAYER JOIN CAPTURE ===")
        sb.appendLine(joinCaptureStatus())
        sb.appendLine("started: $joinStartedAt")
        sb.appendLine("pid/base: $joinPid / ${jhex(joinBase)}")
        sb.appendLine("errors=$joinErrors dropped=$joinDropped sample=${JOIN_SAMPLE_MS}ms")
        val snapshot = synchronized(joinBuffer) { joinBuffer.toList() }
        sb.appendLine("--- trace (${snapshot.size}/$JOIN_MAX_LINES lines) ---")
        if (snapshot.isEmpty()) sb.appendLine("(no snapshots)")
        else snapshot.forEach { sb.appendLine(it) }
        sb.appendLine("--- matching logcat (max $JOIN_MAX_LOGCAT, deduped) ---")
        val logs = joinMatchingLogcat()
        if (logs.isEmpty()) sb.appendLine("(none - logcat may be restricted on this ROM)")
        else logs.forEach { sb.appendLine(it) }
        sb.appendLine("=== END JOIN CAPTURE ===")
        return sb.toString()
    }
}
