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
    @Volatile private var worldPoints = mutableListOf<Pair<EspTarget, Pair<Float, Float>>>()
    @Volatile private var origin: Pair<Float, Float>? = null
    @Volatile private var cam: Camera? = null

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
        thread = Thread {
            while (running) {
                val p = MemoryPatcher.findPid()
                val b = p?.let { MemoryPatcher.findModuleBase(it) }
                if (p != null && b != null) {
                    targets = collectTargets(p, b)
                    cam = camera(p, b)
                    val vw = view?.width ?: 1
                    val vh = view?.height ?: 1
                    worldPoints.clear()
                    for (t in targets) {
                        MemoryPatcher.projectWorld(p, b, t.x, t.y, vw, vh)?.let { worldPoints.add(t to it) }
                    }
                    origin = localPosition(p, b)?.let { pos ->
                        MemoryPatcher.projectWorld(p, b, pos.first, pos.second, vw, vh)
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

    private fun project(x: Double, y: Double, c: Camera, sx: Float, sy: Float, screenH: Float): Pair<Float, Float> =
        ((x - c.x).toFloat() * sx) to (screenH - (y - c.y).toFloat() * sy)

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
            val player = origin
            if (player != null) {
                dotPaint.color = Color.CYAN
                canvas.drawCircle(player.first, player.second, 6f, dotPaint)
                canvas.drawLine(player.first - 14f, player.second, player.first + 14f, player.second, dotPaint)
                canvas.drawLine(player.first, player.second - 14f, player.first, player.second + 14f, dotPaint)
            }
            for ((t, p) in worldPoints) {
                if (p.first < -100f || p.first > width + 100f || p.second < -100f || p.second > height + 100f) continue

                val color = when (t.kind) {
                    KIND_HAWK -> Color.rgb(40, 215, 255)
                    KIND_WORM -> Color.rgb(255, 215, 40)
                    KIND_PLAYER -> Color.rgb(60, 255, 120)
                    KIND_AI -> Color.rgb(180, 140, 255)
                    else -> Color.rgb(255, 70, 70)
                }

                if (boxes) {
                    boxPaint.color = color
                    val w = 40f
                    val h = 70f
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
