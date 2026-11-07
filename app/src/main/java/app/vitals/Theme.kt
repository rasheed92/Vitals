package app.vitals

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** BMW drive modes the dashboard restyles itself for. */
enum class DriveMode(val label: String) { ECO("ECO PRO"), COMFORT("COMFORT"), SPORT("SPORT") }

/**
 * One palette per drive mode, as ARGB longs so the phone (Compose) and the car screen
 * (android.graphics) share the exact same values.
 */
data class Palette(
    val bg: Long,
    val card: Long,
    val muted: Long,
    val label: Long,
    val accent: Long,      // "normal" state, main bars
    val light: Long,       // cold / below target
    val warn: Long,        // hot
    val track: Long,
    val lightDim: Long,
    val accentDim: Long,
    val warnDim: Long,
    val stripLit: List<Long>,  // rpm strip, three zones
    val stripDim: List<Long>,
    val shift: Long,           // whole strip at the shift point
    val glow: Boolean,         // red glow (Sport only)
)

object Theme {
    private val ECO = Palette(
        bg = 0xFF0A1426, card = 0xFF111D36, muted = 0xFF8A99B8, label = 0xFFC4CFE4,
        accent = 0xFF3B7BFF, light = 0xFF86B6FF, warn = 0xFFF2A93B, track = 0xFF152341,
        lightDim = 0xFF16294A, accentDim = 0xFF18254D, warnDim = 0xFF3A3018,
        stripLit = listOf(0xFF86B6FF, 0xFF3B7BFF, 0xFFF2A93B),
        stripDim = listOf(0xFF16294A, 0xFF18254D, 0xFF3A3018),
        shift = 0xFFE8352B, glow = false,
    )
    private val COMFORT = Palette(
        bg = 0xFF15100A, card = 0xFF211812, muted = 0xFFB29C82, label = 0xFFE6D5C1,
        accent = 0xFFFF8A1F, light = 0xFFFFC27A, warn = 0xFFE8352B, track = 0xFF2A1F13,
        lightDim = 0xFF3A2D1A, accentDim = 0xFF3A2715, warnDim = 0xFF3A1A1E,
        stripLit = listOf(0xFFFFC27A, 0xFFFF8A1F, 0xFFE8352B),
        stripDim = listOf(0xFF3A2D1A, 0xFF3A2715, 0xFF3A1A1E),
        shift = 0xFFE8352B, glow = false,
    )
    private val SPORT = Palette(
        bg = 0xFF09090C, card = 0xFF141418, muted = 0xFF8E8E99, label = 0xFFD8D8DE,
        accent = 0xFFE1141E, light = 0xFFFF7A7A, warn = 0xFFFFD23F, track = 0xFF1C1C22,
        lightDim = 0xFF26161A, accentDim = 0xFF2C1418, warnDim = 0xFF3A3018,
        stripLit = listOf(0xFF9C1A22, 0xFFE1141E, 0xFFFF4B4B),
        stripDim = listOf(0xFF26161A, 0xFF2C1418, 0xFF361418),
        shift = 0xFFFF2A2A, glow = true,
    )

    var mode by mutableStateOf(DriveMode.COMFORT); private set

    val p: Palette get() = when (mode) {
        DriveMode.ECO -> ECO
        DriveMode.COMFORT -> COMFORT
        DriveMode.SPORT -> SPORT
    }

    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val name = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("mode", null)
        mode = DriveMode.entries.firstOrNull { it.name == name } ?: DriveMode.COMFORT
    }

    fun set(ctx: Context, m: DriveMode) {
        mode = m
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("mode", m.name).apply()
    }

    /** Manual switch until the car's own drive-mode value is found: ECO → COMFORT → SPORT. */
    fun next(ctx: Context) = set(ctx, DriveMode.entries[(mode.ordinal + 1) % DriveMode.entries.size])
}
