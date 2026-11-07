package app.vitals.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import app.vitals.DriveMode
import app.vitals.ObdViewModel
import app.vitals.Pids
import app.vitals.Theme
import java.util.Locale

/**
 * Draws the car-screen dashboard from the Vitals design: rpm strip across the top,
 * boost on the right (RTL reading start), gear + rpm + speed in the middle, temperature
 * rails on the left, and a row of small readings along the bottom.
 */
class DashRenderer(@Suppress("unused") private val ctx: Context, private val engine: ObdViewModel) {
    // Palette is re-read every frame so the car screen follows the drive mode immediately.
    private var bg = 0
    private var line = 0
    private val text = Color.parseColor("#E9EDF5")
    private var muted = 0
    private var label = 0
    private var blue = 0   // the mode's accent ("normal")
    private var cold = 0
    private var red = 0    // the mode's "hot" colour
    private var track = 0
    private var dims = IntArray(3)
    private var stripLit = IntArray(3)
    private var stripDim = IntArray(3)
    private var shiftColor = 0
    private var glow = false

    private fun loadPalette() {
        val p = Theme.p
        bg = p.bg.toInt(); muted = p.muted.toInt(); label = p.label.toInt()
        blue = p.accent.toInt(); cold = p.light.toInt(); red = p.warn.toInt(); track = p.track.toInt()
        line = blend(p.card.toInt(), p.label.toInt(), 0.12f)
        dims = intArrayOf(p.lightDim.toInt(), p.accentDim.toInt(), p.warnDim.toInt())
        stripLit = p.stripLit.map { it.toInt() }.toIntArray()
        stripDim = p.stripDim.map { it.toInt() }.toIntArray()
        shiftColor = p.shift.toInt(); glow = p.glow
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }

    private fun v(key: Int) = engine.values[key]?.value
    private fun fmt(x: Double, d: Int) = String.format(Locale.US, "%.${d}f", x)

    fun draw(c: Canvas, w: Int, h: Int, area: Rect) {
        loadPalette()
        val mode = Theme.mode
        c.drawColor(bg)
        if (glow) {
            // Sport: red light bleeding in from both sides, like M clusters.
            val r = h * 0.9f
            for (cx in floatArrayOf(0f, w.toFloat())) {
                fill.shader = RadialGradient(cx, h * 0.55f, r, Color.argb(80, 225, 20, 30), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
            }
            fill.shader = null
        }
        val pad = area.height() * 0.05f
        val left = area.left + pad
        val right = area.right - pad
        val top = area.top + pad
        val bottom = area.bottom - pad
        val width = right - left
        val height = bottom - top
        val s = height / 460f // scale: design is ~460 px of content height

        if (!engine.connected) {
            txt.color = muted; txt.textSize = 30 * s; txt.textAlign = Paint.Align.CENTER
            c.drawText(engine.status, left + width / 2, top + height / 2, txt)
            return
        }

        // --- rpm strip ---
        val rpm = v(0x0C) ?: 0.0
        val n = 28
        val gap = 4 * s
        val segW = (width - gap * (n - 1)) / n
        val lit = (rpm.coerceIn(0.0, 7000.0) / 7000.0 * n).toInt()
        val shift = rpm >= 6200
        val stripH = when (mode) { DriveMode.ECO -> 8f; DriveMode.COMFORT -> 14f; DriveMode.SPORT -> 24f } * s
        for (i in 0 until n) {
            val z = if (i < 16) 0 else if (i < 23) 1 else 2
            val on = shift || i < lit
            fill.color = if (shift) shiftColor else if (on) stripLit[z] else stripDim[z]
            if (on && glow) fill.setShadowLayer(10 * s, 0f, 0f, fill.color)
            val x = left + i * (segW + gap)
            c.drawRoundRect(RectF(x, top, x + segW, top + stripH), 2 * s, 2 * s, fill)
            fill.clearShadowLayer()
        }

        val mainTop = top + 26 * s + stripH
        val mainBottom = bottom - 90 * s
        val colBoostL = right - width * 0.36f
        val colMidL = colBoostL - width * 0.24f

        fill.color = line
        c.drawRect(colBoostL, mainTop, colBoostL + 1, mainBottom, fill)
        c.drawRect(colMidL, mainTop, colMidL + 1, mainBottom, fill)
        c.drawRect(left, mainBottom + 14 * s, right, mainBottom + 15 * s, fill)

        // --- boost (right column) ---
        // ECO PRO puts consumption in the hero spot; Comfort and Sport put boost there.
        val eco = mode == DriveMode.ECO
        val f0 = engine.fuel
        val heroValue: Double?
        val heroMark: Double?
        val heroMax: Double
        val heroLabel: String
        val heroUnit: String
        val heroNote: String?
        if (eco) {
            heroValue = f0.instantL100?.coerceAtMost(99.9)
            heroMark = if (f0.tripKm > 0.5) f0.tripLiters / f0.tripKm * 100 else null
            heroMax = 25.0; heroLabel = "الاستهلاك هسه"; heroUnit = "L/100"
            heroNote = if (heroValue == null) "واكف" else heroMark?.let { "متوسط المشوار " + fmt(it, 1) }
        } else {
            heroValue = v(Pids.BMW_BOOST) ?: v(Pids.BOOST)
            heroMark = (engine.values[Pids.BMW_BOOST] ?: engine.values[Pids.BOOST])?.max
            heroMax = 1.5; heroLabel = "البوست"; heroUnit = "bar"
            heroNote = heroMark?.let { "أعلى قراءة " + fmt(it, 2) }
        }
        txt.color = label; txt.textSize = 18 * s; txt.textAlign = Paint.Align.RIGHT
        c.drawText(heroLabel, right, mainTop + 24 * s, txt)
        num.color = text; num.textSize = (if (mode == DriveMode.SPORT) 132 else 120) * s; num.textAlign = Paint.Align.RIGHT
        c.drawText(heroValue?.let { fmt(it, if (eco) 1 else 2) } ?: "—", right, mainTop + 160 * s, num)
        txt.color = muted; txt.textSize = 20 * s; txt.textAlign = Paint.Align.LEFT
        c.drawText(heroUnit, colBoostL + 30 * s, mainTop + 160 * s, txt)
        val barL = colBoostL + 30 * s
        val barT = mainTop + 190 * s
        val barH = when (mode) { DriveMode.ECO -> 10f; DriveMode.COMFORT -> 16f; DriveMode.SPORT -> 20f } * s
        fill.color = track
        c.drawRoundRect(RectF(barL, barT, right, barT + barH), 3 * s, 3 * s, fill)
        val f = ((heroValue ?: 0.0).coerceIn(0.0, heroMax) / heroMax).toFloat()
        val fillR = RectF(barL, barT, barL + (right - barL) * f, barT + barH)
        if (glow) {
            fill.shader = LinearGradient(barL, 0f, right, 0f, intArrayOf(Color.parseColor("#5C0A10"), blue, Color.parseColor("#FF4B4B")), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            fill.setShadowLayer(14 * s, 0f, 0f, blue)
        } else fill.color = blue
        c.drawRoundRect(fillR, 3 * s, 3 * s, fill)
        fill.shader = null; fill.clearShadowLayer()
        heroMark?.let {
            val px = barL + (right - barL) * (it.coerceIn(0.0, heroMax) / heroMax).toFloat()
            fill.color = text
            c.drawRect(px - 1.5f * s, barT - 7 * s, px + 1.5f * s, barT + barH + 7 * s, fill)
        }
        heroNote?.let {
            txt.color = muted; txt.textSize = 16 * s; txt.textAlign = Paint.Align.RIGHT
            c.drawText(it, right, barT + barH + 36 * s, txt)
        }

        // --- gear / rpm / speed (middle) ---
        val mid = (colMidL + colBoostL) / 2
        txt.color = label; txt.textSize = 18 * s; txt.textAlign = Paint.Align.CENTER
        c.drawText("الگير", mid, mainTop + 24 * s, txt)
        val gear = engine.values[0x2020]?.let { Pids.def(0x2020).text?.invoke(it.value) } ?: "—"
        num.color = text; num.textSize = (if (mode == DriveMode.SPORT) 130 else 110) * s; num.textAlign = Paint.Align.CENTER
        c.drawText(gear, mid, mainTop + 150 * s, num)
        num.textSize = 30 * s
        c.drawText("${(rpm / 10).toInt() * 10} rpm", mid, mainTop + 200 * s, num)
        num.color = muted
        c.drawText("${v(0x0D)?.toInt() ?: "—"} km/h", mid, mainTop + 240 * s, num)
        txt.color = blue; txt.textSize = 15 * s; txt.textAlign = Paint.Align.CENTER
        txt.isFakeBoldText = true
        c.drawText(mode.label, mid, mainTop + 276 * s, txt)
        txt.isFakeBoldText = false

        // --- temperature rails (left column) ---
        val rails = listOf(
            Triple("حرارة الماي", v(0x2002) ?: v(0x05), doubleArrayOf(40.0, 80.0, 115.0, 130.0)),
            Triple("حرارة الزيت", v(0x2003) ?: v(0x5C), doubleArrayOf(40.0, 90.0, 125.0, 150.0)),
            Triple("هواء السحب", v(0x2004) ?: v(0x0F), doubleArrayOf(-10.0, 0.0, 45.0, 80.0)),
        )
        val railL = left
        val railR = colMidL - 30 * s
        var y = mainTop + 30 * s
        for ((name, value, r) in rails) {
            val z = if (value == null) 1 else if (value < r[1]) 0 else if (value <= r[2]) 1 else 2
            txt.color = label; txt.textSize = 18 * s; txt.textAlign = Paint.Align.RIGHT
            c.drawText(name + " · " + listOf("بارد", "طبيعي", "حار")[z], railR, y, txt)
            num.color = if (z == 2) red else text; num.textSize = 30 * s; num.textAlign = Paint.Align.LEFT
            c.drawText((value?.let { fmt(it, 0) } ?: "—") + "°", railL, y + 4 * s, num)
            val ry = y + 16 * s
            val span = r[3] - r[0]
            val g = 3 * s
            val tw = railR - railL - 2 * g
            val w1 = ((r[1] - r[0]) / span).toFloat() * tw
            val w2 = ((r[2] - r[1]) / span).toFloat() * tw
            // Rails read left to right (cold to hot), like the cluster gauges.
            fill.color = if (z == 0) cold else dims[0]; c.drawRect(railL, ry, railL + w1, ry + 6 * s, fill)
            fill.color = if (z == 1) blue else dims[1]; c.drawRect(railL + w1 + g, ry, railL + w1 + g + w2, ry + 6 * s, fill)
            fill.color = if (z == 2) red else dims[2]; c.drawRect(railL + w1 + w2 + 2 * g, ry, railR, ry + 6 * s, fill)
            value?.let {
                val mx = railL + ((it - r[0]) / span).coerceIn(0.0, 1.0).toFloat() * (railR - railL)
                fill.color = text; c.drawRect(mx - 1 * s, ry - 5 * s, mx + 1 * s, ry + 11 * s, fill)
            }
            y += 78 * s
        }

        // --- bottom row ---
        val cost = engine.values[Pids.TRIP_COST]?.let { Pids.def(Pids.TRIP_COST).text?.invoke(it.value) } ?: "—"
        val knock = engine.values[Pids.KNOCK]?.let { Pids.def(Pids.KNOCK).text?.invoke(it.value) } ?: "يحتاج دوس"
        val misfire = (1..4).sumOf { (v(Pids.MISFIRE + it) ?: 0.0).toInt() }
        val cells = if (eco) listOf(
            "كلفة المشوار" to "$cost د",
            "المسافة" to fmt(f0.tripKm, 1) + " km",
            "الوقوف بالزحمة" to "${(f0.idleSeconds / 60).toInt()} د",
            "جودة البانزين" to knock,
            "البطارية" to (v(0x42)?.let { fmt(it, 1) + " V" } ?: "—"),
        ) else listOf(
            "كلفة المشوار" to "$cost د",
            "علامة الـ Knock" to knock,
            "Misfire" to misfire.toString(),
            "ضغط الرايل" to (v(0x2016)?.let { fmt(it, 0) + " bar" } ?: "—"),
            "البطارية" to (v(0x42)?.let { fmt(it, 1) + " V" } ?: "—"),
        )
        val cw = width / cells.size
        cells.forEachIndexed { i, (k, value) ->
            val cr = right - i * cw - 16 * s
            txt.color = muted; txt.textSize = 15 * s; txt.textAlign = Paint.Align.RIGHT
            c.drawText(k, cr, mainBottom + 44 * s, txt)
            num.color = text; num.textSize = 24 * s; num.textAlign = Paint.Align.RIGHT
            c.drawText(value, cr, mainBottom + 80 * s, num)
        }
    }
}
