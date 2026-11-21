package com.mmc.patcher.mmc_patcher

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct control of the native feature flags inside the dlopen()-ed LGL mod.
 *
 * JNI_OnLoad() only registers the Java menu (Setup.cpp:41-67), so under a plain
 * dlopen() the menu never runs. The flags live in libMyLibName.so's .bss and
 * are ordinary static bools, so writing them through /proc/<pid>/mem is enough.
 *
 * Offsets below are BASE-RELATIVE to libMyLibName.so's load base. They were
 * derived from the shipped build (1,179,912 bytes, 2026-11-21) by decoding the
 * jump table of Changes() at 0x8abb8 (cmp w3,#0x11, table at 0x46b00) and
 * independently cross-checking joypad_fireAngle() at 0x82e18, which reads
 * g_aimlock/g_smartTrigger/g_lockedDeg in one sequence. Re-derive them (see
 * INJECTOR.md) whenever libMyLibName.so is rebuilt.
 */
internal object LglControl {
    private const val LGL_LIB = "libMyLibName.so"

    // ---- derived base-relative offsets in libMyLibName.so ----
    const val ESP_ENABLED = 0x12177cL
    const val ESP_PLAYERS = 0x121690L
    const val SHOW_DEBUG = 0x1217b0L
    const val TRIGGER_SHOOT = 0x121784L
    const val AUTO_MELEE = 0x12179cL
    const val AIMLOCK = 0x1216b0L
    const val SMART_TRIGGER = 0x1216b4L
    const val LOCKED_DEG = 0x1216b8L // float; owned by the mod, do not drive from UI
    const val GOD_MODE = 0x121740L
    const val INF_AMMO = 0x1216e0L
    const val ONE_SHOT = 0x1216f0L
    const val RAPID_FIRE = 0x12173cL
    const val NO_RECOIL = 0x1217bcL
    const val SPEED_HACK = 0x121718L
    const val TEAM_CHECK = 0x121694L
    const val ENEMY_CHECK = 0x121698L

    /** Feature numbers from Changes()' switch (Main.cpp:942-959). */
    private val FEATURE_OFFSETS = mapOf(
        0 to ESP_ENABLED,
        1 to ESP_PLAYERS,
        2 to SHOW_DEBUG,
        3 to TRIGGER_SHOOT,
        4 to AUTO_MELEE,
        6 to AIMLOCK,
        7 to GOD_MODE,
        8 to INF_AMMO,
        9 to ONE_SHOT,
        10 to RAPID_FIRE,
        11 to NO_RECOIL,
        12 to SPEED_HACK,
        15 to SMART_TRIGGER,
        16 to TEAM_CHECK,
        17 to ENEMY_CHECK,
    )

    fun base(pid: Int): Long? = MemoryPatcher.findModuleBase(pid, LGL_LIB)

    fun isLoaded(pid: Int): Boolean = base(pid) != null

    private fun readBool(pid: Int, address: Long): Boolean =
        MemoryPatcher.readBytes(pid, address, 1)?.firstOrNull() == 1.toByte()

    private fun writeBool(pid: Int, address: Long, value: Boolean): Boolean =
        MemoryPatcher.writeBytes(pid, address, byteArrayOf(if (value) 1 else 0))

    /**
     * Mirrors Changes(): disabling aimlock or smart trigger releases the
     * reticle only when neither is still active.
     */
    fun setFeature(pid: Int, feature: Int, on: Boolean): Boolean {
        val offset = FEATURE_OFFSETS[feature] ?: return false
        val b = base(pid) ?: run {
            PatchLogger.log("LGL", "libMyLibName.so not mapped in pid=$pid")
            return false
        }
        var ok = writeBool(pid, b + offset, on)
        if ((feature == 6 || feature == 15) && !on) {
            val aimOn = readBool(pid, b + AIMLOCK)
            val triggerOn = readBool(pid, b + SMART_TRIGGER)
            if (!aimOn && !triggerOn) {
                val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0f).array()
                ok = MemoryPatcher.writeBytes(pid, b + LOCKED_DEG, zero) && ok
            }
        }
        PatchLogger.log("LGL", "feature=$feature -> ${if (on) "ON" else "OFF"} at 0x${offset.toString(16)} ok=$ok")
        return ok
    }

    /** Reads back the flags our UI exposes, for the status row and logs. */
    fun snapshot(pid: Int): String {
        val b = base(pid) ?: return "LGL not loaded"
        fun flag(name: String, off: Long) = "$name=${if (readBool(pid, b + off)) 1 else 0}"
        return listOf(
            "base=0x${b.toString(16)}",
            flag("esp", ESP_ENABLED),
            flag("aim", AIMLOCK),
            flag("smart", SMART_TRIGGER),
            flag("team", TEAM_CHECK),
            flag("enemy", ENEMY_CHECK),
        ).joinToString(" ")
    }

    /** Parses /proc/<pid>/maps for the on-disk path of the LGL module. */
    fun loadedPath(pid: Int): String? = findPath(pid, LGL_LIB)

    internal fun findPath(pid: Int, needle: String): String? {
        return try {
            File("/proc/$pid/maps").forEachLine { line ->
                if (line.contains(needle)) {
                    // Pathname is the final whitespace-delimited field of a maps line.
                    val trimmed = line.trim()
                    val lastSpace = trimmed.lastIndexOf(' ')
                    if (lastSpace >= 0 && lastSpace + 1 < trimmed.length) {
                        return trimmed.substring(lastSpace + 1)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
