package com.mmc.patcher.mmc_patcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import com.mmc.patcher.mmc_patcher.MemoryPatcher.applyGroup
import com.mmc.patcher.mmc_patcher.MemoryPatcher.toggleAimlock

/** Floating icon + toggle panel, mirroring the ALP/ChoRok injector overlay. */
internal class FloatingMenu(private val activity: MainActivity) {
    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var icon: View? = null
    private var panel: View? = null
    private var expanded = false
    private var pid: Int? = null
    private var base: Long? = null
    private var esp: EspOverlay? = null
    private var debug: DebugOverlay? = null

    private val accent = Color.rgb(255, 82, 82)
    private val panelBg = Color.rgb(16, 16, 20)
    private val textDim = Color.rgb(160, 160, 172)

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
            x = 40
            y = 200
        }
    }

    private var iconParams = overlayParams()
    private var panelParams = overlayParams()

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
        esp?.hide()
        esp = null
        debug?.hide()
        debug = null
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    @SuppressLint("SetTextI18n")
    private fun buildIcon(): TextView = TextView(activity).apply {
        text = "MMC"
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = rounded(accent, 28f)
        setPadding(20, 20, 20, 20)
        makeDraggable(this, { iconParams }, { iconParams = it })
        setOnClickListener { togglePanel() }
    }

    @SuppressLint("SetTextI18n")
    private fun buildPanel(): View {
        val density = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panelBg, 18f)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }
        val title = TextView(activity).apply {
            text = "MMC PATCHER"
            setTextColor(accent)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        val close = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTextColor(Color.rgb(255, 120, 120))
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setOnClickListener { togglePanel() }
        }
        header.addView(close)
        makeDraggable(header, { panelParams }, { panelParams = it })
        root.addView(header)

        val scroll = android.widget.ScrollView(activity).apply {
            setPadding((18 * density).toInt(), 0, (18 * density).toInt(), (14 * density).toInt())
            isVerticalScrollBarEnabled = true
        }
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (pid == null || base == null) {
            val attached = MemoryPatcher.attach()
            if (attached != null) {
                pid = attached.first
                base = attached.second
            }
        }
        val status = TextView(activity).apply {
            text = if (pid != null) "pid=$pid   base=0x${base?.toString(16)}" else "game not running - launch MMC"
            setTextColor(textDim)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 14)
        }
        container.addView(status)

        for (group in PATCH_GROUPS) {
            container.addView(sectionLabel(group.name))
            val sw = Switch(activity).apply {
                text = "Enable"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(6, 4, 0, 10)
                isChecked = false
            }
            sw.setOnCheckedChangeListener { _, checked ->
                val p = pid
                val b = base
                if (p == null || b == null) {
                    sw.isChecked = false
                    toast("Launch MMC first")
                    return@setOnCheckedChangeListener
                }
                val ok = applyGroup(p, b, group, checked)
                if (!ok) {
                    sw.isChecked = false
                    toast("Failed: ${group.name}")
                } else {
                    toast("${group.name}: ${if (checked) "ON" else "OFF"}")
                }
            }
            container.addView(sw)
        }

        container.addView(sectionLabel("AIM"))
        val aimSwitch = Switch(activity).apply {
            text = "Aimlock (nearest enemy)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(6, 4, 0, 10)
            isChecked = false
        }
        aimSwitch.setOnCheckedChangeListener { _, checked ->
            val ok = toggleAimlock(checked)
            if (!ok) {
                aimSwitch.isChecked = false
                toast("Aimlock failed - launch MMC first")
            } else {
                toast("Aimlock: ${if (checked) "ON" else "OFF"}")
            }
        }
        container.addView(aimSwitch)

        container.addView(sectionLabel("ESP"))
        for ((label, isBoxes) in listOf("Boxes" to true, "Lines" to false)) {
            val sw = Switch(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(6, 4, 0, 10)
                isChecked = false
            }
            sw.setOnCheckedChangeListener { _, checked ->
                if (esp == null) esp = EspOverlay(activity)
                if (isBoxes) esp?.setBoxes(checked) else esp?.setLines(checked)
                val bothOff = esp?.isOff() ?: true
                if (bothOff) {
                    esp?.hide()
                    esp = null
                }
                toast("ESP $label: ${if (checked) "ON" else "OFF"}")
            }
            container.addView(sw)
        }

        container.addView(sectionLabel("DIAGNOSTICS"))
        val debugSwitch = Switch(activity).apply {
            text = "Live debug data"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(6, 4, 0, 10)
            isChecked = false
        }
        debugSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (debug == null) debug = DebugOverlay(activity)
                debug?.show()
            } else {
                debug?.hide()
                debug = null
            }
            toast("Debug: ${if (checked) "ON" else "OFF"}")
        }
        container.addView(debugSwitch)

        return root
    }

    private fun sectionLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.rgb(255, 170, 80))
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 8, 0, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun togglePanel() {
        if (!expanded) {
            icon?.let { runCatching { wm.removeView(it) } }
            panel = buildPanel()
            panelParams.x = iconParams.x
            panelParams.y = iconParams.y
            val dm = activity.resources.displayMetrics
            panelParams.width = (270 * dm.density).toInt()
            panelParams.height = (dm.heightPixels - 140).coerceAtMost((620 * dm.density).toInt())
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
