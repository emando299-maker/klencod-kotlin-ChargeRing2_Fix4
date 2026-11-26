package com.floatingchargering

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // Palette.
    private val colBg = Color.rgb(7, 7, 15)
    private val colCard = Color.rgb(17, 17, 28)
    private val colStroke = Color.rgb(35, 35, 58)
    private val colText = Color.rgb(242, 243, 248)
    private val colMuted = Color.rgb(154, 157, 181)
    private val colGreen = Color.rgb(61, 220, 132)
    private val colRed = Color.rgb(255, 92, 92)
    private val colIdle = Color.rgb(107, 110, 133)

    private class Chip(val root: LinearLayout, val dot: GradientDrawable, val label: TextView)
    private class Seg(val row: LinearLayout, val views: List<TextView>, var selected: Int)

    // Display / behaviour cards.
    private val segs = ArrayList<Seg>()
    private lateinit var modeSeg: Seg
    private val fontFrames = LinkedHashMap<String, GradientDrawable>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // The ring window changes the mode when it is tapped; keep the app in sync.
        if (key == FloatingRingService.KEY_MODE && ::modeSeg.isInitialized) {
            modeSeg.selected = currentMode()
            styleSeg(modeSeg)
            applyPreviewAppearance()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var reader: BatteryReader
    private lateinit var preview: RingView
    private lateinit var permChip: Chip
    private lateinit var runChip: Chip
    private lateinit var mainButton: TextView
    private lateinit var permButton: TextView
    private lateinit var sizeValue: TextView
    private lateinit var seek: SeekBar
    private lateinit var diagnostics: TextView
    private lateinit var diagArrow: TextView

    // Alerts card.
    private val alertSwitches = ArrayList<Switch>()
    private val alertToggles = ArrayList<Triple<Switch, String, Boolean>>()
    private val alertSeeks = ArrayList<SeekBar>()
    private val soundNameViews = HashMap<String, TextView>()
    private val soundTypes = HashMap<String, Int>()
    private val pickerKeys = HashMap<Int, String>()

    // "Most used apps" card.
    private lateinit var usagePrompt: LinearLayout
    private lateinit var usageBody: LinearLayout
    private lateinit var usageList: LinearLayout
    private lateinit var usageStatus: TextView
    private val windowChips = ArrayList<TextView>()
    private val windowMs = longArrayOf(3L * 3600_000L, 24L * 3600_000L, 7L * 24L * 3600_000L)
    private val windowNames = arrayOf("3 ساعات", "24 ساعة", "7 أيام")
    private var windowIndex = 1
    private var usageItems: List<AppUsageItem> = emptyList()
    private var usageLoading = false

    private val swatchRings = LinkedHashMap<String, GradientDrawable>()
    private val swatchLabels = LinkedHashMap<String, TextView>()
    private var theme: RingTheme = Themes.byId(null)
    private var styledKey = ""

    private val refresh = object : Runnable {
        override fun run() {
            reader.read()
            if (diagnostics.visibility == View.VISIBLE) diagnostics.text = reader.diagnosticText()
            updateStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    // ---- small view helpers ----------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun pressable(content: GradientDrawable, radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, mask)
    }

    private fun label(value: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = shape(colCard, 24, colStroke, 1)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun chip(): Chip {
        val dotDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colIdle) }
        val dot = View(this).apply {
            background = dotDrawable
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
        }
        val text = label("", 12f, colText)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(14), dp(7))
            background = shape(Color.argb(255, 24, 24, 40), 20, colStroke, 1)
            addView(dot)
            addView(text)
        }
        return Chip(root, dotDrawable, text)
    }

    private fun setChip(chip: Chip, color: Int, text: String) {
        chip.dot.setColor(color)
        chip.label.text = text
    }

    /** Readable text colour on top of the theme's gradient. */
    private fun onAccent(t: RingTheme): Int {
        val r = (Color.red(t.primary) + Color.red(t.secondary)) / 2
        val g = (Color.green(t.primary) + Color.green(t.secondary)) / 2
        val b = (Color.blue(t.primary) + Color.blue(t.secondary)) / 2
        return if (0.299 * r + 0.587 * g + 0.114 * b > 150) colBg else Color.WHITE
    }

    // ---- UI ----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = colBg
        window.navigationBarColor = colBg

        prefs = getSharedPreferences(FloatingRingService.PREFS, MODE_PRIVATE)
        reader = BatteryReader(this)
        theme = Themes.byId(prefs.getString(FloatingRingService.KEY_THEME, null))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
            setBackgroundColor(colBg)
        }

        // Header.
        content.addView(label("Charge Ring 2", 30f, colText, true))
        content.addView(label("نافذة شحن عائمة بتأثيرات نيون", 14f, colMuted).apply {
            setPadding(0, dp(2), 0, dp(20))
        })

        // Hero card: live preview + status chips.
        val hero = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(20, 20, 38), Color.rgb(9, 9, 18))
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), colStroke)
            }
        }
        preview = RingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(250))
            setTheme(theme)
        }
        permChip = chip()
        runChip = chip()
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(permChip.root)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(10), 1))
            addView(runChip.root)
        }
        hero.addView(preview)
        hero.addView(chipRow)
        content.addView(hero)

        // Actions.
        mainButton = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { toggleService() }
        }
        permButton = TextView(this).apply {
            text = "منح إذن العرض فوق التطبيقات"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(colText)
            background = pressable(shape(Color.TRANSPARENT, 18, colStroke, 2), 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { bottomMargin = dp(14) }
            setOnClickListener { openOverlaySettings() }
        }
        content.addView(mainButton)
        content.addView(permButton)

        // Themes card.
        val themeCard = card()
        themeCard.addView(label("المظهر", 16f, colText, true))
        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        for (t in Themes.all) {
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.TRANSPARENT)
            }
            val circle = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(t.primary, t.secondary)
                ).apply { shape = GradientDrawable.OVAL }
            }
            val frame = FrameLayout(this).apply {
                background = ring
                addView(circle, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            }
            val name = label(t.name, 11f, colMuted).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(4), 0, 0)
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(5), 0, dp(5), 0)
                addView(frame)
                addView(name)
                setOnClickListener { selectTheme(t, true) }
            }
            swatchRow.addView(item)
            swatchRings[t.id] = ring
            swatchLabels[t.id] = name
        }
        themeCard.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatchRow)
        })
        content.addView(themeCard)

        // Size card.
        val sizeCard = card()
        val sizeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizeHeader.addView(label("الحجم", 16f, colText, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sizeValue = label("", 13f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        sizeHeader.addView(sizeValue)
        sizeCard.addView(sizeHeader)

        seek = SeekBar(this).apply {
            max = FloatingRingService.MAX_SIZE
            progress = prefs.getInt(FloatingRingService.KEY_SIZE, FloatingRingService.DEFAULT_SIZE)
                .coerceIn(0, FloatingRingService.MAX_SIZE)
            setPadding(dp(8), dp(16), dp(8), dp(8))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 42, 64))
        }
        showSize(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                showSize(value)
                if (fromUser) prefs.edit().putInt(FloatingRingService.KEY_SIZE, value).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        sizeCard.addView(seek)
        sizeCard.addView(label("اسحب الدائرة على الشاشة لتحريكها. القيمة 0 تخفيها والحد الأقصى 1000 بكسل.",
            12f, colMuted))
        content.addView(sizeCard)

        content.addView(buildDisplayCard())
        content.addView(buildBehaviorCard())
        content.addView(buildAutostartCard())
        content.addView(buildCapacityCard())
        content.addView(buildAlertsCard())
        content.addView(buildUsageCard())

        // Diagnostics card (collapsed by default).
        val diagCard = card()
        val diagHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggleDiagnostics() }
        }
        diagHeader.addView(label("التشخيص", 16f, colText, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        diagArrow = label("\u25BE", 18f, colMuted)
        diagHeader.addView(diagArrow)
        diagnostics = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Color.rgb(150, 220, 170))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        diagCard.addView(diagHeader)
        diagCard.addView(diagnostics)
        content.addView(diagCard)

        applyPreviewAppearance()
        selectTheme(theme, false)
        askNotificationPermission()

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(colBg)
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        preview.setRunning(true)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (::modeSeg.isInitialized) {
            modeSeg.selected = currentMode()
            styleSeg(modeSeg)
        }
        applyPreviewAppearance()
        for ((toggle, key, default) in alertToggles) {
            val value = prefs.getBoolean(key, default)
            if (toggle.isChecked != value) toggle.isChecked = value
        }
        ensureService()
        refreshBatteryOptStatus()
        refreshUsage()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        AlertPlayer.stopIfNotLooping()
        preview.setRunning(false)
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    // ---- state -------------------------------------------------------------------

    private fun showSize(v: Int) {
        sizeValue.text = if (v == 0) "مخفية" else "$v px"
    }

    private fun toggleDiagnostics() {
        val show = diagnostics.visibility != View.VISIBLE
        diagnostics.visibility = if (show) View.VISIBLE else View.GONE
        diagArrow.text = if (show) "\u25B4" else "\u25BE"
        if (show) diagnostics.text = reader.diagnosticText()
    }

    private fun selectTheme(t: RingTheme, save: Boolean) {
        theme = t
        if (save) prefs.edit().putString(FloatingRingService.KEY_THEME, t.id).apply()
        preview.setTheme(t)
        for ((id, ring) in swatchRings) {
            ring.setStroke(dp(2), if (id == t.id) Color.WHITE else Color.TRANSPARENT)
        }
        for ((id, name) in swatchLabels) {
            name.setTextColor(if (id == t.id) colText else colMuted)
        }
        sizeValue.background = shape(Color.argb(60, Color.red(t.primary), Color.green(t.primary), Color.blue(t.primary)), 14)
        sizeValue.setTextColor(t.primary)
        seek.progressTintList = ColorStateList.valueOf(t.primary)
        seek.thumbTintList = ColorStateList.valueOf(t.primary)
        styledKey = ""
        updateStatus()
        styleAlertControls()
        for (seg in segs) styleSeg(seg)
        styleFontChips()
        if (::usageList.isInitialized) {
            styleWindowChips()
            renderUsage()
        }
    }

    private fun updateStatus() {
        val granted = Settings.canDrawOverlays(this)
        val overlayOn = prefs.getBoolean(FloatingRingService.KEY_OVERLAY_ON, false)
        val shown = FloatingRingService.overlayShown
        val running = FloatingRingService.running

        setChip(permChip, if (granted) colGreen else colRed,
            if (granted) "الإذن ممنوح" else "الإذن غير ممنوح")
        when {
            shown -> setChip(runChip, colGreen, "النافذة تعمل")
            running -> setChip(runChip, colGreen, "التنبيهات تعمل (بدون نافذة)")
            else -> setChip(runChip, colIdle, "متوقف")
        }
        permButton.visibility = if (granted) View.GONE else View.VISIBLE

        // Restyle the main button only when something changed (keeps the ripple intact).
        val key = "$overlayOn-${theme.id}"
        if (key != styledKey) {
            styledKey = key
            if (overlayOn) {
                mainButton.background = pressable(shape(Color.TRANSPARENT, 18, colRed, 2), 18)
                mainButton.setTextColor(colRed)
                mainButton.text = "إيقاف النافذة العائمة"
            } else {
                val bg = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(theme.primary, theme.secondary)
                ).apply { cornerRadius = dp(18).toFloat() }
                mainButton.background = pressable(bg, 18)
                mainButton.setTextColor(onAccent(theme))
                mainButton.text = "تشغيل النافذة العائمة"
            }
        }
    }

    // ---- display and behaviour -----------------------------------------------------------

    private fun currentMode(): Int =
        prefs.getInt(FloatingRingService.KEY_MODE, FloatingRingService.DEFAULT_MODE)
            .coerceIn(0, FloatingRingService.MODE_COUNT - 1)

    /** A row (or several rows of [perRow]) of choice buttons. */
    private fun segmented(
        options: List<String>, selected: Int, perRow: Int = 100, onSelect: (Int) -> Unit
    ): Seg {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val views = ArrayList<TextView>()
        val seg = Seg(box, views, selected)
        var line: LinearLayout? = null
        for ((i, name) in options.withIndex()) {
            if (i % perRow == 0) {
                line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                box.addView(line)
            }
            val tv = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                setOnClickListener {
                    seg.selected = i
                    styleSeg(seg)
                    onSelect(i)
                }
            }
            views.add(tv)
            line?.addView(tv)
        }
        segs.add(seg)
        styleSeg(seg)
        return seg
    }

    private fun styleSeg(seg: Seg) {
        val p = theme.primary
        for ((i, tv) in seg.views.withIndex()) {
            val sel = i == seg.selected
            tv.background = if (sel) shape(Color.argb(60, Color.red(p), Color.green(p), Color.blue(p)), 14)
            else shape(Color.TRANSPARENT, 14, colStroke, 1)
            tv.setTextColor(if (sel) p else colMuted)
        }
    }

    private fun applyPreviewAppearance() {
        preview.setMode(currentMode())
        preview.setHour24(prefs.getBoolean(FloatingRingService.KEY_HOUR24, false))
        preview.setDecimals(
            prefs.getInt(FloatingRingService.KEY_DEC_PCT, FloatingRingService.DEFAULT_DEC_PCT),
            prefs.getInt(FloatingRingService.KEY_DEC_OTHER, FloatingRingService.DEFAULT_DEC_OTHER)
        )
        preview.setFont(prefs.getString(FloatingRingService.KEY_FONT, null))
    }

    private fun styleFontChips() {
        val selectedId = Fonts.byId(prefs.getString(FloatingRingService.KEY_FONT, null)).id
        for ((id, bg) in fontFrames) {
            bg.setStroke(dp(2), if (id == selectedId) theme.primary else Color.TRANSPARENT)
        }
    }

    private fun buildDisplayCard(): LinearLayout {
        val c = card()
        c.addView(label("ما تعرضه الدائرة", 16f, colText, true))
        c.addView(label(
            "كل عرض يظهر وحده في الدائرة وفوقه اسمه. اضغط على الدائرة نفسها على الشاشة للتبديل بين العروض.",
            12f, colMuted
        ).apply { setPadding(0, dp(4), 0, 0) })
        modeSeg = segmented(
            listOf("الوقت", "التاريخ", "الواط", "درجة الحرارة", "نسبة البطارية", "التيار"),
            currentMode(), 3
        ) { i ->
            prefs.edit().putInt(FloatingRingService.KEY_MODE, i).apply()
            applyPreviewAppearance()
        }
        c.addView(modeSeg.row)

        c.addView(label("نظام الوقت", 14f, colText, true).apply { setPadding(0, dp(14), 0, 0) })
        c.addView(segmented(
            listOf("12 ساعة", "24 ساعة"),
            if (prefs.getBoolean(FloatingRingService.KEY_HOUR24, false)) 1 else 0
        ) { i ->
            prefs.edit().putBoolean(FloatingRingService.KEY_HOUR24, i == 1).apply()
            applyPreviewAppearance()
        }.row)

        // Font.
        c.addView(label("الخط", 14f, colText, true).apply { setPadding(0, dp(20), 0, dp(8)) })
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in Fonts.all) {
            val bg = shape(Color.rgb(24, 24, 40), 16, Color.TRANSPARENT, 2)
            val sample = TextView(this).apply {
                text = "23.9"
                textSize = 22f
                typeface = f.typeface()
                setTextColor(colText)
                gravity = Gravity.CENTER
            }
            val name = label(f.name, 11f, colMuted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = bg
                setPadding(dp(16), dp(10), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                addView(sample)
                addView(name)
                setOnClickListener {
                    prefs.edit().putString(FloatingRingService.KEY_FONT, f.id).apply()
                    styleFontChips()
                    applyPreviewAppearance()
                }
            }
            fontRow.addView(item)
            fontFrames[f.id] = bg
        }
        c.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(fontRow)
        })

        // Decimal digits.
        c.addView(label("الخانات العشرية للنسبة", 14f, colText, true).apply { setPadding(0, dp(20), 0, 0) })
        c.addView(segmented(
            listOf("0", "1", "2", "3"),
            prefs.getInt(FloatingRingService.KEY_DEC_PCT, FloatingRingService.DEFAULT_DEC_PCT).coerceIn(0, 3)
        ) { i ->
            prefs.edit().putInt(FloatingRingService.KEY_DEC_PCT, i).apply()
            applyPreviewAppearance()
        }.row)
        c.addView(label("الخانات العشرية للواط والحرارة", 14f, colText, true).apply { setPadding(0, dp(18), 0, 0) })
        c.addView(segmented(
            listOf("0", "1", "2"),
            prefs.getInt(FloatingRingService.KEY_DEC_OTHER, FloatingRingService.DEFAULT_DEC_OTHER).coerceIn(0, 2)
        ) { i ->
            prefs.edit().putInt(FloatingRingService.KEY_DEC_OTHER, i).apply()
            applyPreviewAppearance()
        }.row)
        c.addView(label(
            "كسور النسبة تظهر فقط عند توفر قراءة دقيقة موثوقة، وإلا يظهر الرقم الصحيح.",
            11f, colMuted
        ).apply { setPadding(0, dp(10), 0, 0) })
        return c
    }

    private fun buildBehaviorCard(): LinearLayout {
        val c = card()
        c.addView(label("سلوك النافذة", 16f, colText, true))

        val snapRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        snapRow.addView(label("الالتصاق بحافة الشاشة", 14f, colText),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val snapSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(FloatingRingService.KEY_SNAP, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(FloatingRingService.KEY_SNAP, on).apply()
            }
        }
        alertSwitches.add(snapSwitch)
        snapRow.addView(snapSwitch)
        c.addView(snapRow)
        c.addView(label(
            "اسحب الدائرة إلى أي مكان بحرية. عند التفعيل تنزلق إلى أقرب حافة عندما ترفع إصبعك، وعند الإيقاف تبقى حيث تتركها.",
            12f, colMuted
        ).apply { setPadding(0, dp(6), 0, 0) })

        val opacityLabel = label("", 14f, colText, true).apply { setPadding(0, dp(20), 0, 0) }
        c.addView(opacityLabel)
        val opacitySeek = SeekBar(this).apply {
            max = 80
            progress = (prefs.getInt(FloatingRingService.KEY_OPACITY, FloatingRingService.DEFAULT_OPACITY) - 20)
                .coerceIn(0, 80)
            setPadding(dp(8), dp(12), dp(8), dp(6))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 42, 64))
        }
        opacityLabel.text = "الوضوح: ${20 + opacitySeek.progress}%"
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                opacityLabel.text = "الوضوح: ${20 + value}%"
                if (fromUser) prefs.edit().putInt(FloatingRingService.KEY_OPACITY, 20 + value).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        alertSeeks.add(opacitySeek)
        c.addView(opacitySeek)
        c.addView(label("أقل وضوح يعني نافذة أكثر شفافية فوق التطبيقات (الحد الأدنى 20%).", 12f, colMuted))
        return c
    }

    // ---- alerts ----------------------------------------------------------------------

    private fun buildAlertsCard(): LinearLayout {
        val c = card()
        c.addView(label("التنبيهات", 16f, colText, true))
        c.addView(label(
            "تعمل التنبيهات ما دامت النافذة العائمة قيد التشغيل، حتى مع إطفاء الشاشة (يمكنك إخفاء الدائرة بجعل الحجم 0). " +
                "تنبيه اكتمال الشحن يستمر بالرنين والاهتزاز حتى تفصل الشاحن. " +
                "قد يتأخر التنبيه بضع ثوانٍ لأن النظام يرسل تحديثات البطارية متأخرة أحيانًا.",
            12f, colMuted
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        addAlertSection(
            c, "تنبيه اكتمال الشحن",
            AlertSettings.KEY_FULL_ON, AlertSettings.KEY_FULL_LEVEL,
            50, 100, AlertSettings.DEFAULT_FULL_LEVEL,
            AlertSettings.KEY_FULL_SOUND, 21, RingtoneManager.TYPE_NOTIFICATION
        ) { v -> "التنبيه عند الوصول إلى $v%" }

        c.addView(View(this).apply {
            setBackgroundColor(colStroke)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(16); bottomMargin = dp(16) }
        })

        addAlertSection(
            c, "تنبيه انخفاض الشحن",
            AlertSettings.KEY_LOW_ON, AlertSettings.KEY_LOW_LEVEL,
            5, 50, AlertSettings.DEFAULT_LOW_LEVEL,
            AlertSettings.KEY_LOW_SOUND, 22, RingtoneManager.TYPE_ALARM
        ) { v -> "التنبيه عند الانخفاض إلى $v% أو أقل" }

        // Vibration switch (shared by both alerts).
        val vibrateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        vibrateRow.addView(label("الاهتزاز مع التنبيه", 14f, colText),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val vibrateSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(AlertSettings.KEY_VIBRATE, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(AlertSettings.KEY_VIBRATE, on).apply() }
        }
        alertSwitches.add(vibrateSwitch)
        vibrateRow.addView(vibrateSwitch)
        c.addView(vibrateRow)
        return c
    }

    private fun addAlertSection(
        card: LinearLayout, title: String,
        enabledKey: String, levelKey: String,
        minLevel: Int, maxLevel: Int, defaultLevel: Int,
        soundKey: String, requestCode: Int, defaultSoundType: Int,
        levelText: (Int) -> String
    ) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label(title, 14f, colText, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enabled = Switch(this).apply {
            isChecked = prefs.getBoolean(enabledKey, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(enabledKey, on).apply()
                if (on) {
                    askNotificationPermission()
                    ensureService()
                }
            }
        }
        alertSwitches.add(enabled)
        alertToggles.add(Triple(enabled, enabledKey, true))
        header.addView(enabled)
        card.addView(header)

        val levelLabel = label("", 13f, colMuted).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(levelLabel)
        val seekBar = SeekBar(this).apply {
            max = maxLevel - minLevel
            progress = (prefs.getInt(levelKey, defaultLevel) - minLevel).coerceIn(0, maxLevel - minLevel)
            setPadding(dp(8), dp(10), dp(8), dp(6))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 42, 64))
        }
        levelLabel.text = levelText(minLevel + seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                levelLabel.text = levelText(minLevel + value)
                if (fromUser) prefs.edit().putInt(levelKey, minLevel + value).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        alertSeeks.add(seekBar)
        card.addView(seekBar)

        // Sound row: name, choose, test.
        soundTypes[soundKey] = defaultSoundType
        pickerKeys[requestCode] = soundKey
        val nameView = label("", 13f, colText).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        soundNameViews[soundKey] = nameView
        updateSoundName(soundKey)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(smallButton("اختيار") { pickSound(soundKey, requestCode) })
        row.addView(smallButton("تجربة") {
            AlertPlayer.play(this, AlertSettings.soundUri(prefs, soundKey, defaultSoundType),
                prefs.getBoolean(AlertSettings.KEY_VIBRATE, true), 4000L)
        })
        card.addView(row)
    }

    private fun smallButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(colText)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = pressable(shape(Color.TRANSPARENT, 14, colStroke, 1), 14)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun updateSoundName(key: String) {
        val type = soundTypes[key] ?: RingtoneManager.TYPE_ALARM
        val uri = AlertSettings.soundUri(prefs, key, type)
        val title = if (uri == null) "صامت" else try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "غير معروف"
        } catch (e: Exception) {
            "غير معروف"
        }
        soundNameViews[key]?.text = "النغمة: $title"
    }

    private fun pickSound(key: String, requestCode: Int) {
        val type = soundTypes[key] ?: RingtoneManager.TYPE_ALARM
        val current = AlertSettings.soundUri(prefs, key, type)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح اختيار النغمات", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val key = pickerKeys[requestCode] ?: return
        if (resultCode != RESULT_OK || data == null) return
        val picked = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // null means "silent" was chosen; stored as an empty string.
        prefs.edit().putString(key, picked?.toString() ?: "").apply()
        updateSoundName(key)
    }

    private fun styleAlertControls() {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val thumb = ColorStateList(states, intArrayOf(theme.primary, colMuted))
        val track = ColorStateList(
            states,
            intArrayOf(
                Color.argb(110, Color.red(theme.primary), Color.green(theme.primary), Color.blue(theme.primary)),
                Color.rgb(42, 42, 64)
            )
        )
        for (sw in alertSwitches) {
            sw.thumbTintList = thumb
            sw.trackTintList = track
        }
        for (bar in alertSeeks) {
            bar.progressTintList = ColorStateList.valueOf(theme.primary)
            bar.thumbTintList = ColorStateList.valueOf(theme.primary)
        }
    }

    // ---- most used apps ------------------------------------------------------------

    private fun buildUsageCard(): LinearLayout {
        val c = card()
        c.addView(label("التطبيقات الأكثر استخدامًا", 16f, colText, true))
        c.addView(label(
            "مرتبة حسب وقت الاستخدام أمام الشاشة، وهي ليست استهلاك البطارية الفعلي. " +
                "استهلاك الخلفية (الموقع والمزامنة وغيرها) لا يظهر هنا لأن أندرويد لا يتيحه للتطبيقات العادية.",
            12f, colMuted
        ).apply { setPadding(0, dp(4), 0, dp(12)) })

        // Shown until usage access is granted.
        usagePrompt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        usagePrompt.addView(label("لعرض القائمة امنح التطبيق إذن الوصول إلى بيانات الاستخدام.", 13f, colText)
            .apply { setPadding(0, 0, 0, dp(10)) })
        usagePrompt.addView(outlinedButton("منح إذن الوصول للاستخدام") { openUsageAccessSettings() })

        // Shown once access is granted.
        usageBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in windowNames.indices) {
            val chip = TextView(this).apply {
                text = windowNames[i]
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    windowIndex = i
                    styleWindowChips()
                    refreshUsage()
                }
            }
            windowChips.add(chip)
            chipRow.addView(chip)
        }
        usageStatus = label("", 12f, colMuted).apply { setPadding(0, dp(12), 0, 0) }
        usageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        usageBody.addView(chipRow)
        usageBody.addView(usageStatus)
        usageBody.addView(usageList)
        usageBody.addView(outlinedButton("فتح استهلاك البطارية الفعلي في إعدادات النظام") { openSystemBatteryUsage() })

        c.addView(usagePrompt)
        c.addView(usageBody)
        styleWindowChips()
        return c
    }

    private fun outlinedButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(colText)
        background = pressable(shape(Color.TRANSPARENT, 16, colStroke, 2), 16)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun styleWindowChips() {
        for ((i, chip) in windowChips.withIndex()) {
            val selected = i == windowIndex
            chip.background = if (selected)
                shape(Color.argb(60, Color.red(theme.primary), Color.green(theme.primary), Color.blue(theme.primary)), 16)
            else shape(Color.TRANSPARENT, 16, colStroke, 1)
            chip.setTextColor(if (selected) theme.primary else colMuted)
        }
    }

    private fun refreshUsage() {
        val granted = AppUsage.hasAccess(this)
        usagePrompt.visibility = if (granted) View.GONE else View.VISIBLE
        usageBody.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted || usageLoading) return

        usageLoading = true
        if (usageItems.isEmpty()) {
            usageStatus.visibility = View.VISIBLE
            usageStatus.text = "جارٍ التحميل…"
        }
        val requested = windowIndex
        val ms = windowMs[requested]
        Thread {
            val result = try {
                AppUsage.query(this, ms, 8)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                usageLoading = false
                if (!isDestroyed) {
                    usageItems = result
                    renderUsage()
                    if (requested != windowIndex) refreshUsage()
                }
            }
        }.start()
    }

    private fun renderUsage() {
        usageList.removeAllViews()
        if (usageItems.isEmpty()) {
            usageStatus.visibility = View.VISIBLE
            usageStatus.text = if (usageLoading) "جارٍ التحميل…" else "لا توجد بيانات استخدام لهذه الفترة."
            return
        }
        usageStatus.visibility = View.GONE
        for (item in usageItems) usageList.addView(usageRow(item))
    }

    private fun usageRow(item: AppUsageItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val icon = ImageView(this).apply {
            setImageDrawable(item.icon)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(label(item.label, 14f, colText).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label(formatDuration(item.foregroundMs), 12f, colMuted))

        val bar = LinearLayout(this).apply {
            weightSum = 1000f
            background = shape(Color.rgb(30, 30, 48), 4)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(6) }
        }
        val fill = (item.share * 1000f).toInt().coerceIn(15, 1000)
        bar.addView(View(this).apply { background = shape(theme.primary, 4) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fill.toFloat()))

        column.addView(top)
        column.addView(bar)
        val percent = label("${(item.share * 100f).roundToInt()}%", 13f, colText, true).apply {
            gravity = Gravity.END
            minWidth = dp(46)
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(icon)
        row.addView(column)
        row.addView(percent)
        return row
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000L
        val h = minutes / 60L
        val m = minutes % 60L
        return when {
            h > 0L -> "${h}س ${m}د"
            minutes > 0L -> "${minutes}د"
            else -> "أقل من 1د"
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "تعذر فتح إعدادات الوصول للاستخدام", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSystemBatteryUsage() {
        try {
            startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح شاشة استهلاك البطارية في النظام", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    /** The button controls only the ring window; alerts keep running on their own. */
    private fun toggleService() {
        if (prefs.getBoolean(FloatingRingService.KEY_OVERLAY_ON, false)) {
            prefs.edit().putBoolean(FloatingRingService.KEY_OVERLAY_ON, false).apply()
            handler.postDelayed({ updateStatus() }, 300L)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "امنح إذن العرض فوق التطبيقات أولًا", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        askNotificationPermission()
        prefs.edit().putBoolean(FloatingRingService.KEY_OVERLAY_ON, true).apply()
        ensureService()
        handler.postDelayed({ updateStatus() }, 500L)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * Starts the background service when the ring or any alert is wanted. When nothing is wanted
     * any more, the running service stops itself (it watches the same settings).
     */
    private fun ensureService() {
        if (!FloatingRingService.isWanted(prefs) || FloatingRingService.running) return
        val intent = Intent(this, FloatingRingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            // Starting is refused only when the app is not in the foreground; the next open retries.
        }
    }

    // ---- battery capacity ----------------------------------------------------------------

    private fun buildCapacityCard(): LinearLayout {
        val readerPrefs = getSharedPreferences(BatteryReader.PREFS, MODE_PRIVATE)
        val c = card()
        c.addView(label("سعة البطارية", 16f, colText, true))
        c.addView(label(
            "تُستخدم لحساب نسبة الشحن بالكسور (مثل 22.545%) منذ البداية، ولتحديد القياسات غير المعقولة. " +
                "السعة المكتوبة في مواصفات الهاتف 4860 mAh. اتركه 0 ليعتمد التطبيق على ما يتعلمه فقط.",
            12f, colMuted
        ).apply { setPadding(0, dp(4), 0, dp(10)) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            setText(readerPrefs.getInt(BatteryReader.KEY_CAPACITY_MAH, BatteryReader.DEFAULT_CAPACITY_MAH).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setTextColor(colText)
            textDirection = View.TEXT_DIRECTION_LTR
            background = shape(Color.rgb(24, 24, 40), 14, colStroke, 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    val mah = e?.toString()?.trim()?.toIntOrNull() ?: 0
                    readerPrefs.edit().putInt(BatteryReader.KEY_CAPACITY_MAH, mah).apply()
                }
            })
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("mAh", 14f, colMuted).apply { setPadding(dp(12), 0, 0, 0) })
        c.addView(row)
        return c
    }

    // ---- automatic start ---------------------------------------------------------------

    private lateinit var batteryOptStatus: TextView

    private fun buildAutostartCard(): LinearLayout {
        val c = card()
        c.addView(label("التشغيل التلقائي", 16f, colText, true))
        c.addView(label(
            "يبدأ التطبيق وحده بعد إعادة تشغيل الهاتف إن كانت النافذة أو أحد التنبيهات مفعّلًا. " +
                "يلزم أن تكون قد فتحت التطبيق مرة قبل ذلك، ولا يعمل إن أوقفته بـ «إيقاف إجباري».",
            12f, colMuted
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        batteryOptStatus = label("", 13f, colText)
        c.addView(batteryOptStatus)
        c.addView(outlinedButton("استثناء التطبيق من توفير البطارية") { requestIgnoreBatteryOptimization() })
        c.addView(label(
            "على Samsung أضف التطبيق أيضًا إلى «التطبيقات التي لا تنام أبدًا»: الإعدادات > العناية بالبطارية والجهاز > " +
                "البطارية > حدود استخدام الخلفية > التطبيقات التي لا تنام أبدًا. وإلا قد يمنع النظام بدءه بعد إعادة التشغيل.",
            12f, colMuted
        ).apply { setPadding(0, dp(12), 0, 0) })
        return c
    }

    private fun isIgnoringBatteryOptimization(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryOptStatus() {
        if (!::batteryOptStatus.isInitialized) return
        val ok = isIgnoringBatteryOptimization()
        batteryOptStatus.text = if (ok) "توفير البطارية: التطبيق مستثنى ✓" else "توفير البطارية: التطبيق غير مستثنى"
        batteryOptStatus.setTextColor(if (ok) colGreen else colMuted)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isIgnoringBatteryOptimization()) {
            Toast.makeText(this, "التطبيق مستثنى بالفعل", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "تعذر فتح إعدادات توفير البطارية", Toast.LENGTH_LONG).show()
            }
        }
    }
}