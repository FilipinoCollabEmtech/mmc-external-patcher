package com.mmc.patcher.mmc_patcher

import android.content.Context
import java.io.File
import java.io.FileOutputStream

internal data class InjectResult(val ok: Boolean, val detail: String)

/**
 * Stages the LGL .so (and the PoC marker) where the game can dlopen() it, then
 * runs the ptrace injector as root via `su` and reports the outcome.
 *
 * The app process is unprivileged, so ptrace cannot run in-process; the CMake
 * `injector_cli` executable is the working path. `nativeInject` is kept for
 * root installs and self-tests and shares the same C core.
 */
internal object NativeInjector {
    private const val TAG = "Injector"
    private const val LGL_ASSET = "libMyLibName.so"
    private const val MARKER_ASSET = "libmarker.so"
    private const val CLI_ASSET = "injector_cli"
    private const val TARGET_PACKAGE = "com.appsomniacs.mmc"
    private const val MARKER_FILE = "/data/local/tmp/mmc_injector_marker.txt"
    private const val CLI_REMOTE = "/data/local/tmp/mmc_injector_cli"

    private val jniAvailable = runCatching { System.loadLibrary("injector") }.isSuccess
    private external fun nativeInject(pid: Int, path: String): Int
    @Volatile private var appContext: Context? = null

    @Volatile var active = false
        private set
    @Volatile var status = "IDLE"
        private set

    /** FloatingMenu hooks this to repaint after a load reverts local patches. */
    var onStateChanged: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!jniAvailable) {
            PatchLogger.log(TAG, "JNI lib unavailable (CLI path still works)")
        }
    }

    fun statusText(): String =
        if (active) "LGL ACTIVE · $status" else "LGL INACTIVE · $status"

    /** Reverts every MemoryPatcher runtime patch so LGL owns these hooks alone. */
    private fun disableConflictingPatches() {
        val attached = MemoryPatcher.attach() ?: run {
            PatchLogger.log(TAG, "no game process; nothing to revert")
            return
        }
        val (pid, base) = attached
        PATCH_GROUPS.forEach { MemoryPatcher.applyGroup(pid, base, it, false) }
        if (LglControl.isLoaded(pid)) MemoryPatcher.uninstallInternalEsp(pid, base)
        MemoryPatcher.toggleAimlock(false)
        PatchLogger.log(TAG, "conflicting native patches reverted")
    }

    private fun stageFromAsset(context: Context, assetName: String, destName: String): String? {
        return try {
            val local = File(context.filesDir, destName)
            context.assets.open(assetName).use { input ->
                FileOutputStream(local).use { out -> input.copyTo(out) }
            }
            local.setReadable(true, false)
            local.absolutePath
        } catch (e: Exception) {
            PatchLogger.log(TAG, "asset $assetName missing: ${e.message}")
            null
        }
    }

    /** CMake shared libs land in nativeLibraryDir, not in assets. */
    private fun stageFromNativeLib(context: Context, libName: String, destName: String): String? {
        val src = File(context.applicationInfo.nativeLibraryDir, libName)
        if (!src.exists()) {
            PatchLogger.log(TAG, "$libName not in nativeLibraryDir")
            return null
        }
        return try {
            val local = File(context.filesDir, destName)
            src.copyTo(local, overwrite = true)
            local.setReadable(true, false)
            local.absolutePath
        } catch (e: Exception) {
            PatchLogger.log(TAG, "staging $libName failed: ${e.message}")
            null
        }
    }

    /** Picks the game's real lib directory (VirtualApp-aware) via /proc maps. */
    private fun targetLibDir(pid: Int): Pair<String, String?> {
        val gameLib = LglControl.findPath(pid, "libcocos2dcpp.so")
        if (gameLib != null) {
            val dir = File(gameLib).parent
            if (!dir.isNullOrBlank()) return dir to gameLib
        }
        PatchLogger.log(TAG, "game lib path not in maps; falling back to /data/local/tmp")
        return "/data/local/tmp" to null
    }

    private fun stageIntoGame(pid: Int, localPath: String, destName: String): String? {
        val (dir, reference) = targetLibDir(pid)
        val dest = "$dir/$destName"
        val refPart = reference?.let { "chcon --reference='$it' '$dest' 2>/dev/null;" }.orEmpty()
        val cmd = "mkdir -p '$dir' && cp '$localPath' '$dest' && chmod 755 '$dest'; $refPart true"
        val rc = runSu(cmd)
        if (rc != 0) {
            PatchLogger.log(TAG, "staging failed rc=$rc dest=$dest")
            return null
        }
        return dest
    }

    private fun stageCli(context: Context): String? {
        val local = stageFromAsset(context, CLI_ASSET, CLI_ASSET) ?: return null
        val cmd = "cp '$local' '$CLI_REMOTE' && chmod 755 '$CLI_REMOTE'"
        return if (runSu(cmd) == 0) CLI_REMOTE else null
    }

    /** Runs the injector as root, falling back to in-process JNI for root installs. */
    private fun inject(pid: Int, soPath: String): InjectResult {
        val cli = appContext?.let { stageCli(it) }
        if (cli != null) {
            val cmd = "'$cli' $pid '$soPath'"
            val out = runSuCapture(cmd)
            val rc = extractRc(out)
            PatchLogger.log(TAG, "CLI out=${out.trim()}")
            if (rc == 0) return InjectResult(true, "loaded $soPath")
            if (rc != Int.MIN_VALUE) return InjectResult(false, "injector rc=$rc (${describe(rc)})")
        }
        if (jniAvailable) {
            val rc = nativeInject(pid, soPath)
            return if (rc == 0)
                InjectResult(true, "loaded $soPath (JNI)")
            else
                InjectResult(false, "JNI injector rc=$rc (${describe(rc)})")
        }
        return InjectResult(false, "root injector CLI missing and JNI unavailable - rebuild with NDK/CMake")
    }

    fun loadLgl(context: Context): InjectResult {
        init(context)
        val pid = MemoryPatcher.findPid()
            ?: return InjectResult(false, "launch MMC first (pid not found)").also {
                status = "NO GAME PROCESS"; notifyState()
            }
        status = "STAGING"
        val local = stageFromAsset(context, LGL_ASSET, LGL_ASSET)
            ?: return InjectResult(false, "libMyLibName.so asset missing").also { status = "ASSET MISSING"; notifyState() }
        val dest = stageIntoGame(pid, local, LGL_ASSET)
            ?: return InjectResult(false, "could not stage .so into game dir").also { status = "STAGE FAILED"; notifyState() }

        status = "REVERTING LOCAL PATCHES"
        disableConflictingPatches()

        status = "INJECTING"
        val result = inject(pid, dest)
        active = result.ok
        status = if (result.ok) "LGL LOADED pid=$pid" else result.detail
        PatchLogger.log(TAG, "loadLgl ${if (result.ok) "OK" else "FAILED"}: ${result.detail}")
        if (result.ok) {
            LglControl.base(pid)?.let { PatchLogger.log(TAG, "LGL base=0x${it.toString(16)}") }
                ?: PatchLogger.log(TAG, "LGL base not yet visible in maps (dlopen may still be resolving)")
        }
        notifyState()
        return result
    }

    /** Phase-1 PoC: prove remote dlopen works with a marker .so first. */
    fun runMarker(context: Context): InjectResult {
        init(context)
        val pid = MemoryPatcher.findPid()
            ?: return InjectResult(false, "launch MMC first (pid not found)")
        val local = stageFromNativeLib(context, MARKER_ASSET, "libmmcmarker.so")
            ?: stageFromAsset(context, MARKER_ASSET, "libmmcmarker.so")
            ?: return InjectResult(false, "libmarker.so missing (NDK/CMake build required)")
        val dest = stageIntoGame(pid, local, "libmmcmarker.so")
            ?: return InjectResult(false, "could not stage marker .so")
        runSu("rm -f '$MARKER_FILE'")
        val result = inject(pid, dest)
        if (!result.ok) return result
        val marker = runSuCapture("cat '$MARKER_FILE' 2>/dev/null").trim()
        val ok = marker.contains("MMC_INJECTOR_DLOPEN_OK")
        val detail = if (ok) "PoC OK: $MARKER_FILE written by game process"
                     else "dlopen returned but marker file missing (check SELinux/path)"
        PatchLogger.log(TAG, "marker: $detail")
        return InjectResult(ok, detail)
    }

    private fun notifyState() {
        onStateChanged?.invoke()
    }

    private fun runSu(command: String): Int = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
    } catch (_: Exception) {
        -1
    }

    private fun runSuCapture(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.ifBlank { process.errorStream.bufferedReader().use { it.readText() } }
    } catch (_: Exception) {
        ""
    }

    private fun extractRc(output: String): Int {
        val match = Regex("INJECT_RC=(-?\\d+)").find(output) ?: return Int.MIN_VALUE
        return match.groupValues[1].toIntOrNull() ?: Int.MIN_VALUE
    }

    private fun describe(rc: Int): String = when (rc) {
        -1 -> "bad arguments"
        -2 -> "ptrace attach failed"
        -3 -> "getregs failed"
        -4 -> "local symbol resolution failed"
        -5 -> "libc not found in target"
        -6 -> "remote mmap failed"
        -7 -> "invalid RWX address"
        -8 -> "dlopen call faulted or timed out"
        -9 -> "dlopen returned NULL (wrong path/SELinux)"
        -10 -> "setregs failed"
        else -> "unknown"
    }
}
