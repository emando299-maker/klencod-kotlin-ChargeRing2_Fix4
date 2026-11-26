package com.floatingchargering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val KIND_WHITE = 0
private const val KIND_TEMP = 1
private const val KIND_ACCENT = 2
private const val KIND_MUTED = 3

/**
 * Round neon charging ring. All content is drawn inside the view's own square bounds
 * (the glow fades to transparent before the edge), so it looks like a round window.
 *
 * Exactly one value is shown at a time, with its Arabic name above it. [setMode] chooses it:
 * 0 time, 1 date, 2 watt, 3 phone (battery sensor) temperature, 4 battery percent, 5 current.
 */
class RingView(context: Context) : View(context) {

    private val reader = BatteryReader(context)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var charging = false
    private var levelFloat = -1f
    private var tempValue: Double? = null
    private var lastSnapshot: BatterySnapshot? = null

    // Display settings.
    private var mode = FloatingRingService.DEFAULT_MODE
    private var hour24 = false
    private var decPct = 2
    private var decOther = 1
    private var fontFace: Typeface = Fonts.byId(null).typeface()

    // What is drawn (rebuilt by rebuild()).
    private var titleText = "\u0646\u0633\u0628\u0629 \u0627\u0644\u0628\u0637\u0627\u0631\u064A\u0629"
    private var bigInt = "--"
    private var bigDec: String? = null
    private var bigUnit = "%"
    private var bigKind = KIND_WHITE

    private var lastReadMs = 0L
    private var lastFrameMs = 0L
    private var phase = 0f
    private var ringR = 0f
    private var auraShader: Shader? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val path = Path()

    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tfBold = Typeface.create("sans-serif-medium", Typeface.BOLD)

    private var theme: RingTheme = Themes.byId(null)
    private var colBlue = theme.primary
    private var colViolet = theme.secondary
    private var colCore = theme.core
    private var colYellow = theme.accent
    private var colHlA = theme.highlightA
    private var colHlB = theme.highlightB
    private var dynColor = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (lastReadMs == 0L || now - lastReadMs >= 1000L) readBattery(now)
            val dtMs = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1L, 200L)
            lastFrameMs = now
            phase = (phase + dtMs / 1000f * (if (charging) 90f else 24f)) % 360f
            invalidate()
            handler.postDelayed(this, if (charging) 33L else 100L)
        }
    }

    // ---- settings ----------------------------------------------------------------------

    /** Applies a colour theme immediately. */
    fun setTheme(value: RingTheme) {
        theme = value
        dynColor = 0
        colBlue = value.primary
        colViolet = value.secondary
        colCore = value.core
        colYellow = value.accent
        colHlA = value.highlightA
        colHlB = value.highlightB
        updateDynamic()
        buildAura(width, height)
        invalidate()
    }

    /** 0 time, 1 date, 2 watt, 3 temperature, 4 battery percent, 5 current (mA). */
    fun setMode(value: Int) {
        mode = value.coerceIn(0, FloatingRingService.MODE_COUNT - 1)
        refreshTexts()
    }

    /** 24-hour clock instead of 12-hour with a ص / م suffix. */
    fun setHour24(value: Boolean) {
        hour24 = value
        refreshTexts()
    }

    /** Decimal digits for the percentage (0..3) and for power and temperature (0..2). */
    fun setDecimals(percent: Int, other: Int) {
        decPct = percent.coerceIn(0, 3)
        decOther = other.coerceIn(0, 2)
        refreshTexts()
    }

    fun setFont(id: String?) {
        fontFace = Fonts.byId(id).typeface()
        invalidate()
    }

    private fun refreshTexts() {
        rebuild(lastSnapshot)
        invalidate()
    }

    // ---- colours -------------------------------------------------------------------------

    /** For the "follow level / temperature" themes: recompute all colours from the current value. */
    private fun updateDynamic() {
        if (theme.dynamicSource == Themes.DYN_NONE) return
        val base: Int = when (theme.dynamicSource) {
            Themes.DYN_LEVEL -> if (levelFloat >= 0f) Themes.rampLevel(levelFloat) else theme.primary
            else -> tempValue?.let { Themes.rampTemp(it.toFloat()) } ?: theme.primary
        }
        if (base == dynColor) return
        dynColor = base
        colBlue = base
        colViolet = Themes.mix(base, Color.BLACK, 0.45f)
        colCore = Themes.mix(base, Color.WHITE, 0.75f)
        colYellow = Themes.mix(base, Color.WHITE, 0.5f)
        colHlA = Themes.mix(base, Color.WHITE, 0.6f)
        colHlB = Themes.mix(base, Color.WHITE, 0.3f)
        buildAura(width, height)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun buildAura(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val s = min(w, h).toFloat()
        auraShader = RadialGradient(
            w / 2f, h / 2f, max(s / 2f, 1f),
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(colViolet, 0),
                withAlpha(colViolet, 120),
                withAlpha(colBlue, 55),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.60f, 0.76f, 0.90f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun tempColor(): Int {
        val t = tempValue
        return when {
            t == null -> Color.rgb(150, 155, 175)
            t >= 42.0 -> Color.rgb(255, 80, 70)
            t >= 38.0 -> Color.rgb(255, 170, 50)
            else -> colCore
        }
    }

    private fun kindColor(kind: Int): Int = when (kind) {
        KIND_TEMP -> tempColor()
        KIND_ACCENT -> colYellow
        KIND_MUTED -> Color.rgb(190, 195, 215)
        else -> Color.WHITE
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** Starts or stops animation and battery polling (stop it when hidden or screen is off). */
    fun setRunning(value: Boolean) {
        if (value == running) return
        running = value
        handler.removeCallbacks(tick)
        if (value) {
            lastReadMs = 0L
            lastFrameMs = 0L
            handler.post(tick)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ---- data -------------------------------------------------------------------------------

    private fun readBattery(now: Long) {
        val s = reader.read()
        lastReadMs = now
        lastSnapshot = s
        applySnapshot(s)
    }

    private fun fmt(v: Double, digits: Int): String =
        if (digits <= 0) Math.round(v).toString() else String.format(Locale.US, "%.${digits}f", v)

    private fun splitNum(text: String?): Pair<String, String?> {
        if (text == null) return Pair("--", null)
        val i = text.indexOf('.')
        return if (i < 0) Pair(text, null) else Pair(text.substring(0, i), text.substring(i + 1))
    }

    private fun applySnapshot(s: BatterySnapshot) {
        rebuild(s)
    }

    /** Builds the text of the selected mode. Time and date do not need a battery reading. */
    private fun rebuild(s: BatterySnapshot?) {
        if (s != null) {
            charging = s.state == PowerState.CHARGING
            levelFloat = s.levelPrecise?.toFloat() ?: s.levelPercent.toFloat()
            tempValue = s.temperatureC
        }
        bigDec = null
        bigKind = KIND_WHITE

        when (mode) {
            0 -> {
                val cal = Calendar.getInstance()
                val h24 = cal.get(Calendar.HOUR_OF_DAY)
                val minute = cal.get(Calendar.MINUTE)
                titleText = "\u0627\u0644\u0648\u0642\u062A"
                if (hour24) {
                    bigInt = String.format(Locale.US, "%02d:%02d", h24, minute)
                    bigUnit = ""
                } else {
                    val h12 = if (h24 % 12 == 0) 12 else h24 % 12
                    bigInt = String.format(Locale.US, "%d:%02d", h12, minute)
                    bigUnit = if (h24 < 12) " \u0635" else " \u0645"
                }
            }
            1 -> {
                val cal = Calendar.getInstance()
                titleText = "\u0627\u0644\u062A\u0627\u0631\u064A\u062E"
                bigInt = String.format(
                    Locale.US, "%04d/%02d/%02d",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
                )
                bigUnit = ""
            }
            2 -> {
                // Measured battery-side power, not the charger's rating.
                val p = splitNum(s?.powerW?.let { fmt(it, decOther) })
                titleText = "\u0627\u0644\u0648\u0627\u0637"
                bigInt = p.first
                bigDec = p.second
                bigUnit = " W"
            }
            3 -> {
                // Battery-sensor temperature: the closest value normal apps can read.
                val p = splitNum(s?.temperatureC?.let { fmt(it, decOther) })
                titleText = "\u062F\u0631\u062C\u0629 \u062D\u0631\u0627\u0631\u0629 \u0627\u0644\u0647\u0627\u062A\u0641"
                bigInt = p.first
                bigDec = p.second
                bigUnit = "\u00B0C"
                bigKind = KIND_TEMP
            }
            5 -> {
                titleText = "\u0627\u0644\u062A\u064A\u0627\u0631"
                bigInt = s?.currentMa?.let { Math.round(it).toString() } ?: "--"
                bigUnit = " mA"
            }
            else -> {
                // Percentage: decimals only when a reliable precise value exists.
                titleText = "\u0646\u0633\u0628\u0629 \u0627\u0644\u0628\u0637\u0627\u0631\u064A\u0629"
                val precise = s?.levelPrecise
                if (precise != null && decPct > 0) {
                    val parts = splitNum(String.format(Locale.US, "%.${decPct}f", precise))
                    bigInt = parts.first
                    bigDec = parts.second
                } else {
                    bigInt = if (s != null && s.levelPercent >= 0) s.levelPercent.toString() else "--"
                }
                bigUnit = "%"
            }
        }
        updateDynamic()
    }

    // ---- drawing ------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = min(w, h).toFloat()
        ringR = s * 0.36f
        buildAura(w, h)
    }

    override fun onDraw(c: Canvas) {
        val r = ringR
        if (r <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val glow = if (charging) 1f else 0.45f
        val pulse = if (charging) 0.75f + 0.25f * sin(Math.toRadians((phase * 4f).toDouble())).toFloat() else 0.7f

        // Aura.
        paint.style = Paint.Style.FILL
        paint.shader = auraShader
        paint.alpha = (255 * pulse).toInt()
        c.drawCircle(cx, cy, min(width, height) / 2f, paint)
        paint.shader = null

        // Dark translucent disc so text stays readable on any app.
        paint.color = Color.argb(235, 2, 3, 12)
        c.drawCircle(cx, cy, r, paint)

        // Radial light streaks around the ring.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1f, r * 0.012f)
        for (i in 0 until 40) {
            val a = Math.toRadians((i * 9f + phase * 0.25f).toDouble())
            val k = ((i * 53) % 11) / 10f
            val flicker = 0.5f + 0.5f * sin(Math.toRadians((phase * 3f + i * 47f).toDouble())).toFloat()
            val r0 = r * 1.10f
            val r1 = r * (1.14f + 0.20f * k)
            paint.color = if (i % 2 == 0) colBlue else colViolet
            paint.alpha = ((60 + 140 * flicker * (0.4f + 0.6f * k)) * glow).toInt().coerceIn(0, 255)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT

        // Layered neon ring: haze, glow, bright core.
        paint.strokeWidth = r * 0.26f
        paint.color = colViolet
        paint.alpha = (36 * glow * pulse).toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.strokeWidth = r * 0.13f
        paint.color = colBlue
        paint.alpha = (95 * glow * pulse + 25).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r, paint)
        paint.strokeWidth = max(1.5f, r * 0.045f)
        paint.color = colCore
        paint.alpha = (255 * (0.8f + 0.2f * pulse)).toInt()
        c.drawCircle(cx, cy, r, paint)

        // Rotating inner arcs.
        for (i in 0..2) {
            val rr = r * (0.84f - i * 0.12f)
            paint.strokeWidth = r * 0.055f
            paint.color = if (i == 1) colViolet else colBlue
            paint.alpha = (115 * glow).toInt() + 20
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(oval, phase * (if (i % 2 == 0) 1f else -1.3f) + i * 40f, 200f, false, paint)
        }

        // Moving highlights on the ring.
        paint.style = Paint.Style.FILL
        for (i in 0..3) {
            val a = Math.toRadians((phase * 1.7f + i * 90f).toDouble())
            paint.color = if (i % 2 == 0) colHlA else colHlB
            paint.alpha = 220
            c.drawCircle(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r,
                max(1.5f, if (i == 0) r * 0.035f else r * 0.02f), paint)
        }
        paint.alpha = 255

        drawTexts(c, cx, cy, r)
    }

    private fun drawTexts(c: Canvas, cx: Float, cy: Float, r: Float) {
        val decText = bigDec?.let { ".$it" }
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.letterSpacing = 0f

        // Value: measure first so wide values (a date, 1476 mA) are scaled to fit the disc.
        textPaint.typeface = fontFace
        textPaint.textSize = r * 0.60f
        val wInt = textPaint.measureText(bigInt)
        textPaint.typeface = tfMedium
        textPaint.textSize = r * 0.24f
        val wDec = if (decText != null) textPaint.measureText(decText) else 0f
        val wUnit = textPaint.measureText(bigUnit)
        val total = wInt + wDec + wUnit
        val fit = min(1f, r * 1.55f / max(total, 1f))

        var x = cx - total * fit / 2f
        val baseY = cy + r * 0.16f

        textPaint.typeface = fontFace
        textPaint.textSize = r * 0.60f * fit
        textPaint.color = kindColor(bigKind)
        c.drawText(bigInt, x, baseY, textPaint)
        x += wInt * fit

        textPaint.typeface = tfMedium
        textPaint.textSize = r * 0.24f * fit
        if (decText != null) {
            c.drawText(decText, x, baseY, textPaint)
            x += wDec * fit
        }
        textPaint.color = Color.rgb(190, 195, 215)
        c.drawText(bigUnit, x, baseY, textPaint)

        // Arabic name of the value above it (no letter spacing: it would break Arabic joining).
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = tfMedium
        textPaint.letterSpacing = 0f
        textPaint.textSize = r * 0.18f
        val titleWidth = textPaint.measureText(titleText)
        if (titleWidth > r * 1.5f) textPaint.textSize = r * 0.18f * (r * 1.5f / titleWidth)
        textPaint.color = Color.rgb(170, 175, 200)
        c.drawText(titleText, cx, cy - r * 0.42f, textPaint)

        // Bolt while charging.
        if (charging) {
            val bs = r * 0.30f
            val bx = cx - bs * 0.5f
            val by = cy + r * 0.40f
            path.reset()
            path.moveTo(bx + bs * 0.62f, by)
            path.lineTo(bx + bs * 0.18f, by + bs * 0.58f)
            path.lineTo(bx + bs * 0.46f, by + bs * 0.58f)
            path.lineTo(bx + bs * 0.34f, by + bs)
            path.lineTo(bx + bs * 0.82f, by + bs * 0.38f)
            path.lineTo(bx + bs * 0.54f, by + bs * 0.38f)
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = colYellow
            c.drawPath(path, paint)
        }
    }
}
