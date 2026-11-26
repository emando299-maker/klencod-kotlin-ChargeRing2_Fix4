package com.floatingchargering

import android.graphics.Color
import android.graphics.Typeface

/**
 * Colours of one ring theme.
 * primary/secondary: ring glow and arcs, core: bright inner line of the ring,
 * highlightA/B: the two moving dots, accent: power text and charging bolt.
 */
class RingTheme(
    val id: String,
    val name: String,
    val primary: Int,
    val secondary: Int,
    val core: Int,
    val highlightA: Int,
    val highlightB: Int,
    val accent: Int,
    /** 0 = fixed colours, 1 = colours follow the battery level, 2 = colours follow the temperature. */
    val dynamicSource: Int = 0
)

/** A system font family offered for the big number in the ring. */
class RingFont(val id: String, val name: String, val family: String) {
    fun typeface(): Typeface = Typeface.create(family, Typeface.NORMAL)
}

object Fonts {
    val all: List<RingFont> = listOf(
        RingFont("light", "خفيف", "sans-serif-light"),
        RingFont("regular", "عادي", "sans-serif"),
        RingFont("thin", "رفيع", "sans-serif-thin"),
        RingFont("black", "عريض", "sans-serif-black"),
        RingFont("condensed", "ضيق", "sans-serif-condensed"),
        RingFont("serif", "كلاسيكي", "serif"),
        RingFont("mono", "آلة", "monospace"),
        RingFont("casual", "يدوي", "casual")
    )

    fun byId(id: String?): RingFont = all.firstOrNull { it.id == id } ?: all[0]
}

object Themes {
    val all: List<RingTheme> = listOf(
        RingTheme("neon_blue", "أزرق نيون",
            Color.rgb(45, 120, 255), Color.rgb(125, 55, 255), Color.rgb(205, 218, 255),
            Color.CYAN, Color.MAGENTA, Color.rgb(255, 200, 50)),
        RingTheme("emerald", "زمردي",
            Color.rgb(30, 220, 110), Color.rgb(0, 150, 130), Color.rgb(200, 255, 225),
            Color.rgb(150, 255, 200), Color.rgb(80, 255, 255), Color.rgb(230, 255, 120)),
        RingTheme("crimson", "قرمزي",
            Color.rgb(255, 60, 70), Color.rgb(200, 20, 100), Color.rgb(255, 215, 215),
            Color.rgb(255, 160, 120), Color.rgb(255, 80, 160), Color.rgb(255, 200, 70)),
        RingTheme("gold", "ذهبي",
            Color.rgb(255, 170, 30), Color.rgb(255, 100, 20), Color.rgb(255, 236, 200),
            Color.rgb(255, 240, 150), Color.rgb(255, 150, 60), Color.rgb(255, 245, 200)),
        RingTheme("violet", "بنفسجي",
            Color.rgb(170, 80, 255), Color.rgb(255, 60, 200), Color.rgb(240, 215, 255),
            Color.rgb(220, 150, 255), Color.rgb(255, 100, 220), Color.rgb(255, 200, 50)),
        RingTheme("ice", "جليدي",
            Color.rgb(60, 215, 255), Color.rgb(30, 120, 255), Color.rgb(225, 248, 255),
            Color.rgb(200, 250, 255), Color.rgb(100, 180, 255), Color.WHITE),
        RingTheme("sunset", "غروب",
            Color.rgb(255, 110, 60), Color.rgb(200, 50, 180), Color.rgb(255, 225, 215),
            Color.rgb(255, 190, 90), Color.rgb(255, 90, 170), Color.rgb(255, 220, 120)),
        RingTheme("silver", "فضي",
            Color.rgb(230, 230, 235), Color.rgb(130, 130, 150), Color.WHITE,
            Color.WHITE, Color.rgb(190, 190, 205), Color.WHITE),
        // Colours change with the battery level (red -> orange -> green).
        RingTheme("dyn_level", "حسب النسبة",
            Color.rgb(40, 220, 100), Color.rgb(255, 150, 40), Color.rgb(215, 255, 225),
            Color.rgb(170, 255, 190), Color.rgb(255, 210, 120), Color.rgb(200, 255, 200),
            dynamicSource = Themes.DYN_LEVEL),
        // Colours change with the temperature (blue -> green -> orange -> red).
        RingTheme("dyn_temp", "حسب الحرارة",
            Color.rgb(60, 200, 255), Color.rgb(255, 80, 70), Color.rgb(220, 245, 255),
            Color.rgb(170, 230, 255), Color.rgb(255, 160, 140), Color.rgb(220, 240, 255),
            dynamicSource = Themes.DYN_TEMP)
    )

    const val DYN_NONE = 0
    const val DYN_LEVEL = 1
    const val DYN_TEMP = 2

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    private fun ramp(value: Float, stops: FloatArray, colors: IntArray): Int {
        if (value <= stops[0]) return colors[0]
        for (i in 1 until stops.size) {
            if (value <= stops[i]) {
                return mix(colors[i - 1], colors[i], (value - stops[i - 1]) / (stops[i] - stops[i - 1]))
            }
        }
        return colors[colors.size - 1]
    }

    private val levelStops = floatArrayOf(0f, 15f, 35f, 60f, 100f)
    private val levelColors = intArrayOf(
        Color.rgb(255, 60, 60), Color.rgb(255, 140, 40), Color.rgb(240, 210, 60),
        Color.rgb(150, 225, 70), Color.rgb(40, 220, 100)
    )
    private val tempStops = floatArrayOf(20f, 32f, 38f, 42f, 46f)
    private val tempColors = intArrayOf(
        Color.rgb(60, 170, 255), Color.rgb(50, 220, 150), Color.rgb(255, 190, 50),
        Color.rgb(255, 120, 40), Color.rgb(255, 60, 60)
    )

    fun rampLevel(level: Float): Int = ramp(level, levelStops, levelColors)
    fun rampTemp(celsius: Float): Int = ramp(celsius, tempStops, tempColors)

    fun byId(id: String?): RingTheme = all.firstOrNull { it.id == id } ?: all[0]
}
