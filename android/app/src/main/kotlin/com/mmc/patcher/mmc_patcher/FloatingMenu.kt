package com.mmc.patcher.mmc_patcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Floating icon + expanded toggle panel, mirroring the ALP injector's overlay. */
internal class FloatingMenu(private val activity: MainActivity) {
    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var icon: View? = null
    private var panel: View? = null
    private var expanded = false
    private var pid: Int? = null
    private var base: Long? = null

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.RGBA_8888,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 220
        }
    }

    private var iconParams = overlayParams()
    private var panelParams = overlayParams()

    @SuppressLint("SetTextI18n")
    fun show() {
        if (icon != null) return
        icon = buildIcon()
        wm.addView(icon, iconParams)
    }

    fun hide() {
        icon?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        icon = null
        panel = null
        expanded = false
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    @SuppressLint("SetTextI18n")
    private fun buildIcon(): TextView {
        val label = TextView(activity).apply {
            text = "MMC"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(28, 28, 32), 28f)
            setPadding(18, 18, 18, 18)
        }
        makeDraggable(label, { iconParams }, { iconParams = it })
        label.setOnClickListener { togglePanel() }
        return label
    }

    @SuppressLint("SetTextI18n")
    private fun buildPanel(): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(18, 18, 22), 16f)
            setPadding(26, 20, 26, 20)
        }

        val title = TextView(activity).apply {
            text = "MMC PATCHER"
            setTextColor(Color.rgb(255, 82, 82))
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(title)

        val status = TextView(activity).apply {
            text = "attached: no"
            setTextColor(Color.rgb(170, 170, 180))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }
        container.addView(status)

        if (pid == null || base == null) {
            val attached = MemoryPatcher.attach()
            if (attached != null) {
                pid = attached.first
                base = attached.second
                status.text = "pid=$pid  base=0x${base?.toString(16)}"
            } else {
                status.text = "game not running - launch MMC first"
            }
        } else {
            status.text = "pid=$pid  base=0x${base?.toString(16)}"
        }

        for (patch in PATCHES) {
            val sw = Switch(activity).apply {
                text = patch.name
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, 6, 0, 6)
                isChecked = false
            }
            sw.setOnCheckedChangeListener { _, checked ->
                val p = pid
                val b = base
                if (p == null || b == null) {
                    sw.isChecked = false
                    Toast.makeText(activity, "Attach failed - launch MMC first", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                val ok = MemoryPatcher.applyPatch(p, b, patch, checked)
                if (!ok) {
                    sw.isChecked = false
                    Toast.makeText(activity, "Patch failed: ${patch.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "${patch.name}: ${if (checked) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                }
            }
            container.addView(sw)
        }

        val minimize = TextView(activity).apply {
            text = "— MINIMIZE —"
            setTextColor(Color.rgb(140, 190, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 0)
            setOnClickListener { togglePanel() }
        }
        container.addView(minimize)

        makeDraggable(container, { panelParams }, { panelParams = it })
        return container
    }

    private fun togglePanel() {
        if (!expanded) {
            icon?.let { runCatching { wm.removeView(it) } }
            panel = buildPanel()
            panelParams.x = iconParams.x
            panelParams.y = iconParams.y
            wm.addView(panel, panelParams)
            expanded = true
        } else {
            panel?.let { runCatching { wm.removeView(it) } }
            panel = null
            icon = buildIcon()
            iconParams.x = panelParams.x
            iconParams.y = panelParams.y
            wm.addView(icon, iconParams)
            expanded = false
        }
    }

    private fun makeDraggable(
        view: View,
        getParams: () -> WindowManager.LayoutParams,
        setParams: (WindowManager.LayoutParams) -> Unit,
    ) {
        var startX = 0f
        var startY = 0f
        var baseX = 0
        var baseY = 0
        view.setOnTouchListener { _, event ->
            val p = getParams()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    baseX = p.x
                    baseY = p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = baseX + (event.rawX - startX).toInt()
                    p.y = baseY + (event.rawY - startY).toInt()
                    runCatching { wm.updateViewLayout(view, p) }
                    setParams(p)
                }
            }
            false
        }
    }
}
