package com.mmc.patcher.mmc_patcher

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mmc.patcher.mmc_patcher.MemoryPatcher.applyGroup
import com.mmc.patcher.mmc_patcher.MemoryPatcher.toggleAimlock

/**
 * Draggable badge plus a scrollable control panel, drawn as a standalone
 * WindowManager overlay so it stays on top of the game.
 */
internal class FloatingMenu(private val activity: MainActivity) {
    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var icon: View? = null
    private var panel: View? = null
    private var expanded = false
    private var pid: Int? = null
    private var base: Long? = null
    private var espPid: Int? = null
    private var espBase: Long? = null
    private var espBoxes = false
    private var espLines = false
    private var debug: DebugOverlay? = null

    /** Remembered so reopening the panel shows the real state instead of resetting switches. */
    private val states = LinkedHashMap<String, Boolean>()

    private val accent = Color.rgb(255, 92, 92)
    private val bg = Color.argb(248, 17, 18, 25)
    private val cardOff = Color.rgb(31, 33, 43)
    private val cardPressed = Color.rgb(43, 46, 59)
    private val cardOn = Color.rgb(58, 27, 32)
    private val stroke = Color.rgb(55, 58, 74)
    private val textPrimary = Color.rgb(238, 240, 248)
    private val textDim = Color.rgb(148, 154, 176)
    private val sectionColor = Color.rgb(255, 168, 102)
    private val online = Color.rgb(96, 226, 162)

    private val density: Float get() = activity.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    /** Live window bounds — displayMetrics can stay portrait despite a rotation. */
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            return (b.right - b.left) to (b.bottom - b.top)
        }
        val dm = activity.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun panelSize(): Pair<Int, Int> {
        val (screenW, screenH) = screenSize()
        val width = dp(300).coerceAtMost((screenW - dp(16)).coerceAtLeast(dp(200)))
        val maxHeight = (screenH - dp(32)).coerceAtLeast(dp(160))
        return width to minOf(maxHeight, dp(660))
    }

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
            y = 160
        }
    }

    private var iconParams = overlayParams()
    private var panelParams = overlayParams()

    /** Application-level so rotation is still seen while the patcher activity is backgrounded. */
    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = this@FloatingMenu.onConfigurationChanged()
        override fun onLowMemory() = Unit
    }

    fun show() {
        if (icon != null) return
        activity.application.registerComponentCallbacks(configCallback)
        icon = buildIcon()
        wm.addView(icon, iconParams)
    }

    fun hide() {
        runCatching { activity.application.unregisterComponentCallbacks(configCallback) }
        icon?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        icon = null
        panel = null
        expanded = false
        espPid?.let { p -> espBase?.let { b -> MemoryPatcher.setEspFlags(p, b, false, false) } }
        debug?.hide()
        debug = null
    }

    /** Called from MainActivity after a rotation so the overlay follows the new bounds. */
    fun onConfigurationChanged() {
        val (screenW, screenH) = screenSize()
        PatchLogger.log("UI", "config change ${screenW}x$screenH expanded=$expanded")
        if (expanded && panel != null) {
            val (width, height) = panelSize()
            panelParams.width = width
            panelParams.height = height
            panelParams.x = panelParams.x.coerceIn(0, (screenW - width).coerceAtLeast(0))
            panelParams.y = panelParams.y.coerceIn(0, (screenH - height).coerceAtLeast(0))
            iconParams.x = panelParams.x
            iconParams.y = panelParams.y
            runCatching { wm.updateViewLayout(panel, panelParams) }
        } else {
            val badge = dp(56)
            iconParams.x = iconParams.x.coerceIn(0, (screenW - badge).coerceAtLeast(0))
            iconParams.y = iconParams.y.coerceIn(0, (screenH - badge).coerceAtLeast(0))
            icon?.let { runCatching { wm.updateViewLayout(it, iconParams) } }
        }
    }

    // ---- drawables -------------------------------------------------------

    private fun rounded(color: Int, radius: Float, outline: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
            if (outline != null) setStroke(dp(1), outline)
        }

    private fun pressable(base: Int, pressed: Int, radius: Float, outline: Int? = null): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius, outline))
            addState(intArrayOf(), rounded(base, radius, outline))
        }

    private fun TextView.caption(size: Float, color: Int, bold: Boolean = false) {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
    }

    // ---- badge -----------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun buildIcon(): TextView {
        val live = states.values.any { it }
        return TextView(activity).apply {
            text = "M"
            gravity = Gravity.CENTER
            caption(20f, Color.WHITE, bold = true)
            background = rounded(if (live) accent else Color.rgb(38, 40, 51), dp(28).toFloat(), accent)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            minimumWidth = dp(56)
            minimumHeight = dp(56)
            makeDraggable(this, { iconParams }, { iconParams = it })
            setOnClickListener { togglePanel() }
        }
    }

    // ---- panel -----------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun buildPanel(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bg, dp(20).toFloat(), stroke)
            setPadding(dp(12), 0, dp(12), dp(10))
        }

        root.addView(buildHeader())
        root.addView(buildScroll(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    @SuppressLint("SetTextI18n")
    private fun buildHeader(): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val handle = View(activity).apply {
            background = rounded(Color.rgb(78, 82, 100), dp(2).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        header.addView(handle)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }

        val mark = TextView(activity).apply {
            text = "M"
            gravity = Gravity.CENTER
            caption(12f, Color.WHITE, bold = true)
            background = rounded(accent, dp(11).toFloat())
            minimumWidth = dp(34)
            minimumHeight = dp(34)
        }
        row.addView(mark)

        val titles = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            }
        }
        titles.addView(TextView(activity).apply {
            text = "MMC PATCHER"
            caption(15f, textPrimary, bold = true)
            letterSpacing = 0.06f
        })
        titles.addView(TextView(activity).apply {
            text = if (pid != null) "GAME LIVE  ·  PID $pid" else "GAME NOT FOUND"
            caption(10f, if (pid != null) online else textDim)
            typeface = Typeface.MONOSPACE
        })
        row.addView(titles)

        row.addView(TextView(activity).apply {
            text = "✕"
            gravity = Gravity.CENTER
            caption(16f, Color.rgb(255, 140, 140), bold = true)
            background = pressable(Color.rgb(44, 46, 58), Color.rgb(70, 48, 52), dp(14).toFloat())
            minimumWidth = dp(30)
            minimumHeight = dp(30)
            setOnClickListener { togglePanel() }
        })
        header.addView(row)

        val divider = View(activity).apply {
            setBackgroundColor(Color.rgb(44, 47, 60))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(2)
            }
        }
        header.addView(divider)

        makeDraggable(header, { panelParams }, { panelParams = it })
        return header
    }

    @SuppressLint("SetTextI18n")
    private fun buildScroll(): ScrollView {
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isScrollbarFadingEnabled = false
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, dp(2), 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }

        container.addView(statusCard())
        container.addView(sectionTitle("COMBAT"))
        container.addView(groupRow("god", "HP", "God Mode", "Ignore all incoming damage", PATCH_GROUPS[0]))
        container.addView(groupRow("ammo", "∞", "Infinite Ammo", "Clip and reserve never run dry", PATCH_GROUPS[1]))
        container.addView(groupRow("security", "SH", "Security Bypass", "Hide H4X alerts · ballistics", PATCH_GROUPS[2]))

        container.addView(sectionTitle("AIM  /  ESP"))
        container.addView(toggleRow("aim", "◎", "Aimlock", "Snap to the nearest enemy") { checked ->
            toggleAimlock(checked)
        })
        container.addView(toggleRow("espBoxes", "▣", "ESP Boxes", "Outline targets through walls") { checked ->
            setEsp(boxes = checked)
            true
        })
        container.addView(toggleRow("espLines", "╱", "ESP Lines", "Draw lines to every target") { checked ->
            setEsp(lines = checked)
            true
        })

        container.addView(sectionTitle("DIAGNOSTICS"))
        container.addView(toggleRow("debug", "i", "Live Debug Data", "Show pointers and target values") { checked ->
            if (checked) {
                if (debug == null) debug = DebugOverlay(activity)
                debug?.show()
            } else {
                debug?.hide()
                debug = null
            }
            true
        })

        container.addView(TextView(activity).apply {
            text = "MEMORY PATCHER  ·  LOCAL DEVICE"
            caption(9f, Color.rgb(96, 101, 122))
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        })

        container.addView(TextView(activity).apply {
            text = "⧉  COPY LOGS"
            caption(12f, Color.WHITE, bold = true)
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            minimumHeight = dp(42)
            background = pressable(Color.rgb(40, 43, 56), Color.rgb(58, 62, 78), dp(12).toFloat(), stroke)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            setOnClickListener { copyLogs() }
        })

        scroll.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    @SuppressLint("SetTextI18n")
    private fun statusCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(25, 27, 36), dp(12).toFloat(), Color.rgb(45, 48, 61))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(2) }
        }
        val live = pid != null && base != null
        card.addView(infoRow("STATUS", if (live) "CONNECTED" else "WAITING FOR GAME", if (live) online else textDim))
        card.addView(infoRow(
            "PID / BASE",
            if (live) "$pid  ·  0x${base?.toString(16)}" else "—",
            if (live) textPrimary else textDim,
        ))
        return card
    }

    @SuppressLint("SetTextI18n")
    private fun infoRow(label: String, value: String, valueColor: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
        addView(TextView(activity).apply {
            text = label
            caption(9.5f, textDim, bold = true)
            letterSpacing = 0.08f
        })
        addView(TextView(activity).apply {
            text = value
            caption(10f, valueColor)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        caption(10.5f, sectionColor, bold = true)
        letterSpacing = 0.12f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(14)
            bottomMargin = dp(7)
        }
    }

    // ---- toggle rows -----------------------------------------------------

    /**
     * The game can be relaunched while the panel is open (especially inside a
     * virtual container), so cached pid/base go stale. Re-resolve before any
     * memory write instead of trusting what was captured when the panel opened.
     */
    private fun ensureAttached(): Boolean {
        MemoryPatcher.attach()?.let {
            if (it.first != pid || it.second != base) {
                PatchLogger.log("UI", "re-attached pid=${it.first} base=0x${it.second.toString(16)}")
            }
            pid = it.first
            base = it.second
        }
        return pid != null && base != null
    }

    private fun groupRow(
        key: String,
        glyph: String,
        label: String,
        subtitle: String,
        group: PatchGroup,
    ): View = toggleRow(key, glyph, label, subtitle) { checked ->
        if (!ensureAttached()) {
            toast("Launch MMC first")
            false
        } else {
            applyGroup(pid!!, base!!, group, checked)
        }
    }

    /**
     * A whole-row tap target with an explicit ON/OFF pill — no tiny stock
     * switch, and the active state is obvious at a glance.
     */
    @SuppressLint("SetTextI18n")
    private fun toggleRow(
        key: String,
        glyph: String,
        label: String,
        subtitle: String,
        action: (Boolean) -> Boolean,
    ): View {
        var on = states[key] == true

        val icon = TextView(activity).apply {
            text = glyph
            gravity = Gravity.CENTER
            caption(12f, Color.WHITE, bold = true)
            minimumWidth = dp(32)
            minimumHeight = dp(32)
        }
        val name = TextView(activity).apply {
            text = label
            caption(13f, textPrimary, bold = true)
        }
        val desc = TextView(activity).apply {
            text = subtitle
            caption(10f, textDim)
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(11)
            }
            addView(name)
            addView(desc)
        }
        val pill = TextView(activity).apply {
            gravity = Gravity.CENTER
            caption(10.5f, Color.WHITE, bold = true)
            minimumWidth = dp(54)
            minimumHeight = dp(26)
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            minimumHeight = dp(54)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) }
            addView(icon)
            addView(texts)
            addView(pill)
        }

        fun paint() {
            icon.background = rounded(if (on) accent else Color.rgb(52, 55, 70), dp(9).toFloat())
            pill.text = if (on) "ON" else "OFF"
            pill.background = rounded(if (on) accent else Color.rgb(56, 59, 74), dp(13).toFloat())
            desc.setTextColor(if (on) Color.rgb(255, 176, 176) else textDim)
            row.background = pressable(
                if (on) cardOn else cardOff,
                if (on) Color.rgb(78, 36, 42) else cardPressed,
                dp(13).toFloat(),
                if (on) Color.rgb(150, 60, 66) else Color.rgb(48, 51, 64),
            )
        }

        row.setOnClickListener {
            val next = !on
            if (action(next)) {
                on = next
                states[key] = next
                paint()
                PatchLogger.log("TOGGLE", "$label -> ${if (next) "ON" else "OFF"}")
                toast("$label: ${if (next) "ON" else "OFF"}")
            } else {
                toast("Could not toggle $label")
            }
        }

        paint()
        return row
    }

    private fun setEsp(boxes: Boolean? = null, lines: Boolean? = null) {
        if (boxes != null) espBoxes = boxes
        if (lines != null) espLines = lines
        if (!ensureAttached()) {
            toast("Launch MMC first")
            return
        }
        val p = pid!!
        val b = base!!
        if (espPid != p || espBase != b) {
            if (!MemoryPatcher.installInternalEsp(p, b)) {
                PatchLogger.log("ESP", "install failed pid=$p base=0x${b.toString(16)}")
                toast("ESP install failed")
                return
            }
            PatchLogger.log("ESP", "hook installed pid=$p base=0x${b.toString(16)}")
            espPid = p
            espBase = b
        }
        PatchLogger.log("ESP", "flags boxes=$espBoxes lines=$espLines")
        MemoryPatcher.setEspFlags(p, b, espBoxes, espLines)
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Clipboard writes are blocked for background apps on newer Android, so if the
     * direct copy fails, bring the launcher forward and retry once.
     */
    private fun copyLogs() {
        PatchLogger.log("UI", "copy logs tapped")
        if (PatchLogger.copyToClipboard(activity)) {
            toast("Logs copied")
            return
        }
        activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        Handler(Looper.getMainLooper()).postDelayed({
            toast(if (PatchLogger.copyToClipboard(activity)) "Logs copied" else "Copy failed - see files/mmc_patcher_log.txt")
        }, 400)
    }

    // ---- open / close ----------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun togglePanel() {
        if (!expanded) {
            if (pid == null || base == null) {
                MemoryPatcher.attach()?.let {
                    pid = it.first
                    base = it.second
                }
            }
            icon?.let { runCatching { wm.removeView(it) } }

            panel = buildPanel()
            panelParams.x = iconParams.x
            panelParams.y = iconParams.y
            val (screenW, screenH) = screenSize()
            val (width, height) = panelSize()
            panelParams.width = width
            panelParams.height = height
            panelParams.x = panelParams.x.coerceIn(0, (screenW - width).coerceAtLeast(0))
            panelParams.y = panelParams.y.coerceIn(0, (screenH - height).coerceAtLeast(0))
            wm.addView(panel, panelParams)
            expanded = true
            PatchLogger.log("UI", "panel opened pid=$pid base=0x${base?.toString(16)}")
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
