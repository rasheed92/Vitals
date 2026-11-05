package app.vitals

data class PidDef(
    val pid: Int,
    val label: String,
    val unit: String,
    val bytes: Int,
    val decimals: Int,
    val fast: Boolean,
    val decode: (IntArray) -> Double,
)

data class Reading(val value: Double, val min: Double, val max: Double, val history: List<Float>)

/** Standard OBD-II mode 01 PIDs. BMW-specific values (gearbox, misfires) come in phase 2. */
object Pids {
    const val BOOST = 0x1000 // derived: MAP - baro

    val all: List<PidDef> = listOf(
        PidDef(0x0C, "دورات المحرك", "rpm", 2, 0, true) { (it[0] * 256 + it[1]) / 4.0 },
        PidDef(0x0D, "السرعة", "km/h", 1, 0, true) { it[0].toDouble() },
        PidDef(0x0B, "ضغط المانيفولد", "kPa", 1, 0, true) { it[0].toDouble() },
        PidDef(0x11, "الثروتل", "%", 1, 0, true) { it[0] * 100.0 / 255 },
        PidDef(0x04, "حمل المحرك", "%", 1, 0, true) { it[0] * 100.0 / 255 },
        PidDef(0x0E, "توقيت الإشعال", "°", 1, 1, true) { it[0] / 2.0 - 64 },
        PidDef(0x23, "ضغط الوقود العالي", "bar", 2, 0, true) { (it[0] * 256 + it[1]) / 10.0 },
        PidDef(0x10, "تدفق الهواء MAF", "g/s", 2, 1, true) { (it[0] * 256 + it[1]) / 100.0 },
        PidDef(0x62, "العزم الفعلي", "%", 1, 0, true) { it[0] - 125.0 },
        PidDef(0x0F, "حرارة هواء السحب", "°C", 1, 0, false) { it[0] - 40.0 },
        PidDef(0x05, "حرارة الماي", "°C", 1, 0, false) { it[0] - 40.0 },
        PidDef(0x5C, "حرارة زيت المحرك", "°C", 1, 0, false) { it[0] - 40.0 },
        PidDef(0x46, "حرارة الجو", "°C", 1, 0, false) { it[0] - 40.0 },
        PidDef(0x33, "الضغط الجوي", "kPa", 1, 0, false) { it[0].toDouble() },
        PidDef(0x42, "فولتية البطارية", "V", 2, 1, false) { (it[0] * 256 + it[1]) / 1000.0 },
        PidDef(0x2F, "مستوى البانزين", "%", 1, 0, false) { it[0] * 100.0 / 255 },
    )

    private val boost = PidDef(BOOST, "البوست", "bar", 0, 2, true) { 0.0 }

    val displayOrder = listOf(
        BOOST, 0x0C, 0x0D, 0x0F, 0x05, 0x5C, 0x23, 0x0E,
        0x04, 0x11, 0x10, 0x62, 0x42, 0x46, 0x2F, 0x0B, 0x33,
    )

    fun def(key: Int): PidDef = if (key == BOOST) boost else all.first { it.pid == key }
}
