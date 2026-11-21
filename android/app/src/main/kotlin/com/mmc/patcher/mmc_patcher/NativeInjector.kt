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

    private fun gameUid(pid: Int): String? = try {
        File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
    } catch (_: Exception) {
        null
    }

    /**
     * /data/app/<pkg>/lib/arm64 is read-only, so stage into the game's own
     * writable files directory with the game UID; fall back to /data/local/tmp.
     */
    private fun stageIntoGame(pid: Int, localPath: String, destName: String): String? {
        val uid = gameUid(pid)
        val candidates = listOf(
            "/data/data/$TARGET_PACKAGE/files",
            "/data/user/0/$TARGET_PACKAGE/files",
            "/data/local/tmp",
        )
        for (dir in candidates) {
            val dest = "$dir/$destName"
            val owner = uid?.let { "chown $it:$it '$dest' && " }.orEmpty()
            val cmd = "mkdir -p '$dir' && cp '$localPath' '$dest' && ${owner}chmod 755 '$dest' 2>&1; echo RC=\$?"
            val out = runSuCapture(cmd)
            if (out.contains("RC=0")) {
                PatchLogger.log(TAG, "staged $destName -> $dest uid=$uid")
                return dest
            }
            PatchLogger.log(TAG, "stage candidate failed dest=$dest out=${out.trim().take(200)}")
        }
        return null
    }

    private fun stageCli(context: Context): String? {
        val local = stageFromAsset(context, CLI_ASSET, CLI_ASSET) ?: return null
        val cmd = "cp '$local' '$CLI_REMOTE' && chmod 755 '$CLI_REMOTE'"
        return if (runSu(cmd) == 0) CLI_REMOTE else null
    }

    /** Logs the UID/ptrace constraints that decide whether attach can work. */
    private fun logPrivilegeContext(pid: Int) {
        fun uidOf(path: String) = try {
            File(path).readLines().firstOrNull { it.startsWith("Uid:") }?.trim() ?: "?"
        } catch (_: Exception) { "?" }
        val scope = try {
            File("/proc/sys/kernel/yama/ptrace_scope").readText().trim()
        } catch (_: Exception) { "?" }
        PatchLogger.log(
            TAG,
            "privilege self=${uidOf("/proc/self/status")} game=${uidOf("/proc/$pid/status")} " +
                "ptrace_scope=$scope jniAvailable=$jniAvailable",
        )
    }

    /**
     * Prefer in-process injection: the patcher and game are both hosted by the
     * same VirtualApp, so PTRACE_ATTACH can work without root. The su CLI is a
     * last resort only.
     */
    private fun inject(pid: Int, soPath: String): InjectResult {
        logPrivilegeContext(pid)
        val attempts = mutableListOf<String>()
        if (jniAvailable) {
            val rc = nativeInject(pid, soPath)
            PatchLogger.log(TAG, "direct JNI rc=$rc path=$soPath")
            if (rc == 0) return InjectResult(true, "loaded $soPath (direct JNI)")
            attempts += "direct JNI rc=$rc (${describe(rc)})"
        }
        val cli = appContext?.let { stageCli(it) }
        if (cli != null) {
            val out = runSuCapture("'$cli' $pid '$soPath'")
            val rc = extractRc(out)
            PatchLogger.log(TAG, "CLI out=${out.trim()}")
            if (rc == 0) return InjectResult(true, "loaded $soPath (CLI)")
            if (rc != Int.MIN_VALUE) attempts += "CLI rc=$rc (${describe(rc)})"
        }
        return InjectResult(false, attempts.joinToString("; ").ifBlank { "no injector path available" })
    }

    /**
     * Both apps live under the same VirtualApp host, so the game's virtual data
     * dir is writable by us without root. Derive it from our own wrapped path.
     */
    private fun stageViaVirtualApp(local: String, destName: String): String? {
        val own = appContext?.filesDir?.absolutePath ?: return null
        val marker = "/data/user/0/"
        val idx = own.indexOf(marker)
        if (idx < 0) {
            PatchLogger.log(TAG, "cannot derive VA root from $own")
            return null
        }
        val root = own.substring(0, idx)
        val dir = File("$root${marker}$TARGET_PACKAGE/files")
        val dest = File(dir, destName)
        return try {
            dir.mkdirs()
            File(local).copyTo(dest, overwrite = true)
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            PatchLogger.log(TAG, "VA staged $destName -> ${dest.absolutePath}")
            dest.absolutePath
        } catch (e: Exception) {
            PatchLogger.log(TAG, "VA staging failed: ${e.message}")
            null
        }
    }

    /** Try our own path, then the game's virtual data dir; root staging is last. */
    private fun loadSo(pid: Int, local: String, destName: String): InjectResult {
        val attempts = mutableListOf<String>()
        var result = inject(pid, local)
        if (result.ok) return result
        attempts += "own path: ${result.detail}"

        val vaPath = stageViaVirtualApp(local, destName)
        if (vaPath != null) {
            result = inject(pid, vaPath)
            if (result.ok) return result
            attempts += "VA path: ${result.detail}"
        }

        val staged = stageIntoGame(pid, local, destName)
        if (staged != null && staged != local && staged != vaPath) {
            result = inject(pid, staged)
            if (result.ok) return result
            attempts += "root path: ${result.detail}"
        }
        return InjectResult(false, attempts.joinToString("; "))
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
        status = "REVERTING LOCAL PATCHES"
        disableConflictingPatches()

        status = "INJECTING"
        val result = loadSo(pid, local, LGL_ASSET)
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
        runSu("rm -f '$MARKER_FILE'")
        val result = loadSo(pid, local, "libmmcmarker.so")
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
