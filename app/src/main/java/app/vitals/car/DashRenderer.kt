package app.vitals.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import app.vitals.ObdViewModel
import app.vitals.Pids
import java.util.Locale

/**
 * Draws the car-screen dashboard from the Vitals design: rpm strip across the top,
 * boost on the right (RTL reading start), gear + rpm + speed in the middle, temperature
 * rails on the left, and a row of small readings along the bottom.
 */
class DashRenderer(@Suppress("unused") private val ctx: Context, private val engine: ObdViewModel) {
    private val bg = Color.parseColor("#0E1322")
    private val line = Color.parseColor("#222B44")
    private val text = Color.parseColor("#E9EDF5")
    private val muted = Color.parseColor("#8B95AD")
    private val label = Color.parseColor("#C4CBDA")
    private val blue = Color.parseColor("#3B63E0")
    private val cold = Color.parseColor("#4FB3FF")
    private val red = Color.parseColor("#E8352B")
    private val track = Color.parseColor("#1C2440")
    private val dims = intArrayOf(Color.parseColor("#1D3550"), Color.parseColor("#1E2A55"), Color.parseColor("#3A1E2A"))
    private val lits = intArrayOf(cold, blue, red)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }

    private fun v(key: Int) = engine.values[key]?.value
    private fun fmt(x: Double, d: Int) = String.format(Locale.US, "%.${d}f", x)

    fun draw(c: Canvas, w: Int, h: Int, area: Rect) {
        c.drawColor(bg)
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
        for (i in 0 until n) {
            val z = if (i < 16) 0 else if (i < 23) 1 else 2
            fill.color = if (shift) red else if (i < lit) lits[z] else dims[z]
            val x = left + i * (segW + gap)
            c.drawRoundRect(RectF(x, top, x + segW, top + 14 * s), 2 * s, 2 * s, fill)
        }

        val mainTop = top + 40 * s
        val mainBottom = bottom - 90 * s
        val colBoostL = right - width * 0.36f
        val colMidL = colBoostL - width * 0.24f

        fill.color = line
        c.drawRect(colBoostL, mainTop, colBoostL + 1, mainBottom, fill)
        c.drawRect(colMidL, mainTop, colMidL + 1, mainBottom, fill)
        c.drawRect(left, mainBottom + 14 * s, right, mainBottom + 15 * s, fill)

        // --- boost (right column) ---
        val boost = v(Pids.BMW_BOOST) ?: v(Pids.BOOST)
        val peak = (engine.values[Pids.BMW_BOOST] ?: engine.values[Pids.BOOST])?.max
        txt.color = label; txt.textSize = 18 * s; txt.textAlign = Paint.Align.RIGHT
        c.drawText("البوست", right, mainTop + 24 * s, txt)
        num.color = text; num.textSize = 120 * s; num.textAlign = Paint.Align.RIGHT
        c.drawText(boost?.let { fmt(it, 2) } ?: "—", right, mainTop + 160 * s, num)
        txt.color = muted; txt.textSize = 20 * s; txt.textAlign = Paint.Align.LEFT
        c.drawText("bar", colBoostL + 30 * s, mainTop + 160 * s, txt)
        val barL = colBoostL + 30 * s
        val barT = mainTop + 190 * s
        fill.color = track
        c.drawRoundRect(RectF(barL, barT, right, barT + 16 * s), 3 * s, 3 * s, fill)
        val f = ((boost ?: 0.0).coerceIn(0.0, 1.5) / 1.5).toFloat()
        fill.color = blue
        c.drawRoundRect(RectF(barL, barT, barL + (right - barL) * f, barT + 16 * s), 3 * s, 3 * s, fill)
        peak?.let {
            val px = barL + (right - barL) * (it.coerceIn(0.0, 1.5) / 1.5).toFloat()
            fill.color = text
            c.drawRect(px - 1.5f * s, barT - 7 * s, px + 1.5f * s, barT + 23 * s, fill)
            txt.color = muted; txt.textSize = 16 * s; txt.textAlign = Paint.Align.RIGHT
            c.drawText("أعلى قراءة " + fmt(it, 2), right, barT + 52 * s, txt)
        }

        // --- gear / rpm / speed (middle) ---
        val mid = (colMidL + colBoostL) / 2
        txt.color = label; txt.textSize = 18 * s; txt.textAlign = Paint.Align.CENTER
        c.drawText("الگير", mid, mainTop + 24 * s, txt)
        val gear = engine.values[0x2020]?.let { Pids.def(0x2020).text?.invoke(it.value) } ?: "—"
        num.color = text; num.textSize = 110 * s; num.textAlign = Paint.Align.CENTER
        c.drawText(gear, mid, mainTop + 150 * s, num)
        num.textSize = 30 * s
        c.drawText("${(rpm / 10).toInt() * 10} rpm", mid, mainTop + 200 * s, num)
        num.color = muted
        c.drawText("${v(0x0D)?.toInt() ?: "—"} km/h", mid, mainTop + 240 * s, num)

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
        val cells = listOf(
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
