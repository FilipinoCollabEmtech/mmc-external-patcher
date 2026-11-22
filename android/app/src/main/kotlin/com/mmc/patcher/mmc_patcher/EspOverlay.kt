package com.mmc.patcher.mmc_patcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.mmc.patcher.mmc_patcher.MemoryPatcher.camera
import com.mmc.patcher.mmc_patcher.MemoryPatcher.collectTargets
import com.mmc.patcher.mmc_patcher.MemoryPatcher.localPosition

/** Transparent full-screen overlay that draws ESP boxes and lines from live memory. */
internal class EspOverlay(private val activity: MainActivity) {
    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: EspView? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var boxes = false
    @Volatile private var lines = false
    @Volatile private var targets = emptyList<EspTarget>()
    @Volatile private var origin: Pair<Float, Float>? = null
    @Volatile private var cam: Camera? = null
    @Volatile private var localRaw: Pair<Double, Double>? = null

    fun show() {
        if (view != null) return
        view = EspView(activity)
        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, params)
        running = true
        var cachedPid = -1
        var cachedBase = -1L
        var lastPidCheck = 0L
        var lastBaseCheck = 0L
        var lastAlignLog = 0L
        thread = Thread {
            while (running) {
                val now = System.currentTimeMillis()
                if (cachedPid <= 0 || now - lastPidCheck > 500L ||
                    !java.io.File("/proc/$cachedPid").exists()
                ) {
                    lastPidCheck = now
                    cachedPid = MemoryPatcher.findPid() ?: -1
                    cachedBase = -1L
                }
                if (cachedPid > 0 && (cachedBase <= 0L || now - lastBaseCheck > 1000L)) {
                    lastBaseCheck = now
                    cachedBase = MemoryPatcher.findModuleBase(cachedPid) ?: -1L
                }
                val p = if (cachedPid > 0) cachedPid else null
                val b = if (cachedBase > 0L) cachedBase else null
                if (p != null && b != null) {
                    targets = collectTargets(p, b)
                    cam = camera(p, b)
                    val vw = view?.width ?: activity.resources.displayMetrics.widthPixels
                    val vh = view?.height ?: activity.resources.displayMetrics.heightPixels
                    localRaw = localPosition(p, b)
                    origin = localRaw?.let { pos ->
                        cam?.let { c -> project(pos.first, pos.second, c, vw, vh) }
                    }
                    if (now - lastAlignLog > 2000L) {
                        lastAlignLog = now
                        PatchLogger.log("ESPALIGN", MemoryPatcher.espAlignmentDebug(p, b, vw, vh))
                    }
                }
                view?.postInvalidate()
                try {
                    Thread.sleep(33)
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

    fun setBoxes(enabled: Boolean) {
        boxes = enabled
        if (enabled) show()
    }

    fun setLines(enabled: Boolean) {
        lines = enabled
        if (enabled) show()
    }

    fun isOff(): Boolean = !boxes && !lines

    /** Player-anchored projection: cocos content pixels fitted to the view. */
    private fun project(x: Double, y: Double, c: Camera, viewW: Int, viewH: Int): Pair<Float, Float> {
        val csf = if (c.csf > 0f) c.csf else 1f
        val contentW = c.width * csf
        val contentH = c.height * csf
        val scale = if (contentW > 0f && contentH > 0f)
            minOf(viewW / contentW, viewH / contentH) else 1f
        val anchor = localRaw
        val ax = anchor?.first ?: x
        val ay = anchor?.second ?: y
        val px = viewW / 2f + ((x - ax) * scale).toFloat()
        val py = viewH / 2f - ((y - ay) * scale).toFloat()
        return px to py
    }

    private fun viewScale(c: Camera, viewW: Int, viewH: Int): Float {
        val csf = if (c.csf > 0f) c.csf else 1f
        val contentW = c.width * csf
        val contentH = c.height * csf
        return if (contentW > 0f && contentH > 0f) minOf(viewW / contentW, viewH / contentH) else 1f
    }

    @SuppressLint("ViewConstructor")
    private inner class EspView(context: Context) : View(context) {
        private val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        private val linePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
            alpha = 140
        }
        private val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val c = cam ?: return
            val player = origin
            val vw = width
            val vh = height
            val scale = viewScale(c, vw, vh)
            val kx = scale
            val ky = scale
            for (t in targets) {
                val p = project(t.x, t.y, c, vw, vh)
                if (p.first < -100f || p.first > vw + 100f || p.second < -100f || p.second > vh + 100f) continue

                val color = when (t.kind) {
                    KIND_HAWK -> Color.rgb(40, 215, 255)
                    KIND_WORM -> Color.rgb(255, 215, 40)
                    KIND_PLAYER -> Color.rgb(60, 255, 120)
                    KIND_AI -> Color.rgb(180, 140, 255)
                    else -> Color.rgb(255, 70, 70)
                }

                if (boxes) {
                    boxPaint.color = color
                    val w = 40f * scale
                    val h = 70f * scale
                    canvas.drawRect(p.first - w / 2, p.second - h / 2, p.first + w / 2, p.second + h / 2, boxPaint)
                    dotPaint.color = color
                    canvas.drawCircle(p.first, p.second - h / 2 - 6f, 3f, dotPaint)
                }
                if (lines && player != null) {
                    linePaint.color = color
                    canvas.drawLine(player.first, player.second, p.first, p.second, linePaint)
                }
            }
        }
    }
}
