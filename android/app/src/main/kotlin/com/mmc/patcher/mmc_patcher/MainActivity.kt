package com.mmc.patcher.mmc_patcher

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channel = "com.mmc.patcher/launcher"
    private val targetPackage = "com.appsomniacs.mmc"
    private var floatingMenu: FloatingMenu? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        PatchLogger.installCrashHandler(applicationContext)
        NativeInjector.init(this)
        PatchLogger.log("APP", "launcher opened")
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                        startActivity(intent)
                        result.success("permission")
                        return@setMethodCallHandler
                    }
                    if (floatingMenu == null) floatingMenu = FloatingMenu(this)
                    floatingMenu?.show()

                    val launch = packageManager.getLaunchIntentForPackage(targetPackage)
                    if (launch != null) {
                        PatchLogger.log("APP", "launching MMC")
                        startActivity(launch)
                        moveTaskToBack(true)
                        result.success("started")
                    } else {
                        PatchLogger.log("APP", "MMC not installed")
                        result.success("not_installed")
                    }
                }
                "isInstalled" -> {
                    result.success(packageManager.getLaunchIntentForPackage(targetPackage) != null)
                }
                "copyLogs" -> {
                    result.success(if (PatchLogger.copyToClipboard(this@MainActivity)) "copied" else "failed")
                }
                "injectorStatus" -> {
                    result.success(NativeInjector.statusText())
                }
                "loadLgl" -> {
                    val res = NativeInjector.loadLgl(this@MainActivity)
                    result.success(if (res.ok) "ok: ${res.detail}" else "error: ${res.detail}")
                }
                "pocMarker" -> {
                    val res = NativeInjector.runMarker(this@MainActivity)
                    result.success(if (res.ok) "ok: ${res.detail}" else "error: ${res.detail}")
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingMenu?.onConfigurationChanged()
    }

    override fun onDestroy() {
        floatingMenu?.hide()
        super.onDestroy()
    }
}
