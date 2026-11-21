package com.mmc.patcher.mmc_patcher

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channel = "com.mmc.patcher/launcher"
    private val targetPackage = "com.appsomniacs.mmc"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            when (call.method) {
                "launchGame" -> {
                    val intent = packageManager.getLaunchIntentForPackage(targetPackage)
                    if (intent != null) {
                        startActivity(intent)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "isInstalled" -> {
                    result.success(packageManager.getLaunchIntentForPackage(targetPackage) != null)
                }
                else -> result.notImplemented()
            }
        }
    }
}
