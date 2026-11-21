package com.mmc.patcher.mmc_patcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.mmc.patcher.mmc_patcher.MemoryPatcher.espDebugInfo

/** Always-on-top readout of the live values the in-game ESP engine depends on. */
internal class EspDebugOverlay(private val activity: MainActivity) {
    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("SetTextI18n")
    fun show() {
        if (view != null) return
        val label = TextView(activity).apply {
            text = "esp debug: waiting..."
            setTextColor(Color.rgb(130, 240, 255))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(225, 4, 6, 12))
            setPadding(10, 8, 10, 8)
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
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = 20
        }
        wm.addView(label, params)
        view = label
        running = true
        thread = Thread {
            while (running) {
                val p = MemoryPatcher.findPid()
                val b = p?.let { MemoryPatcher.findModuleBase(it) }
                val text = if (p == null || b == null) {
                    "MMC not found (pid=$p base=$b)"
                } else {
                    try {
                        espDebugInfo(p, b)
                    } catch (e: Exception) {
                        "esp debug error: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                label.post { label.text = text }
                try {
                    Thread.sleep(400)
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
