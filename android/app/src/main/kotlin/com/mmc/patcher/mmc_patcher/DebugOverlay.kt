package com.mmc.patcher.mmc_patcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.mmc.patcher.mmc_patcher.MemoryPatcher.debugInfo

/** Small always-on-top text panel showing the live memory-chain state. */
internal class DebugOverlay(private val activity: MainActivity) {
    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("SetTextI18n")
    fun show() {
        if (view != null) return
        val label = TextView(activity).apply {
            text = "debug: waiting..."
            setTextColor(Color.rgb(120, 255, 160))
            textSize = 10f
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(12, 10, 12, 10)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 700
        }
        wm.addView(label, params)
        view = label
        running = true
        thread = Thread {
            while (running) {
                val p = MemoryPatcher.findPid()
                val b = p?.let { MemoryPatcher.findModuleBase(it) }
                val text = if (p == null) {
                    "MMC process not found\nlaunch the game first"
                } else if (b == null) {
                    "pid=$p but libcocos2dcpp.so not mapped"
                } else {
                    try {
                        debugInfo(p, b)
                    } catch (e: Exception) {
                        "debug error: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                label.post { label.text = text }
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun hide() {
        running = false
        thread?.interrupt()
        try {
            thread?.join(200)
        } catch (_: InterruptedException) {
            // Ignore.
        }
        thread = null
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
