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

    // --- BMW UDS values found by the watch on this car (DME 0x12, EGS 0x18) ---
    const val BMW_BOOST = 0x2001

    /** [calc] gets the raw value (big-endian, signed if [signed]); [test] = not confirmed yet. */
    class BmwPid(
        val ecu: Int, val did: Int, val def: PidDef,
        val signed: Boolean = false, val firstByteOnly: Boolean = false, val test: Boolean = false,
        val calc: (Double) -> Double,
    )

    private fun d(key: Int, label: String, unit: String, dec: Int, fast: Boolean) =
        PidDef(key, label, unit, 0, dec, fast) { 0.0 }

    private fun kelvin(raw: Double) = raw / 10.0 - 273.15

    val bmw: List<BmwPid> = listOf(
        BmwPid(0x12, 0x4202, d(BMW_BOOST, "البوست · BMW", "bar", 2, true)) { it * 0.004249 - 8.276 }, // kPa, boost computed later
        BmwPid(0x12, 0x4300, d(0x2002, "حرارة الماي · BMW", "°C", 1, false)) { kelvin(it) },
        BmwPid(0x12, 0x4408, d(0x2003, "حرارة الزيت · BMW", "°C", 1, false)) { kelvin(it) },
        BmwPid(0x12, 0x4A2B, d(0x2004, "هواء السحب · BMW", "°C", 1, false)) { kelvin(it) },
        BmwPid(0x12, 0x4206, d(0x2005, "الهواء MAF · BMW", "g/s", 1, true)) { it / 32.0 / 3.6 },
        BmwPid(0x12, 0x4A49, d(0x2011, "توقيت سلندر 1 (اختبار)", "°", 1, true), signed = true, test = true) { it * 0.075 },
        BmwPid(0x12, 0x4A4A, d(0x2012, "توقيت سلندر 2 (اختبار)", "°", 1, true), signed = true, test = true) { it * 0.075 },
        BmwPid(0x12, 0x4A4C, d(0x2013, "توقيت سلندر 3 (اختبار)", "°", 1, true), signed = true, test = true) { it * 0.075 },
        BmwPid(0x12, 0x4A4D, d(0x2014, "توقيت سلندر 4 (اختبار)", "°", 1, true), signed = true, test = true) { it * 0.075 },
        BmwPid(0x18, 0x4605, d(0x2020, "وضع الگير · خام (اختبار)", "", 0, true), test = true) { it },
        BmwPid(0x18, 0x4301, d(0x2021, "زيت الگير؟ 4301 (اختبار)", "", 0, false), test = true) { it },
        BmwPid(0x18, 0x41B4, d(0x2022, "زيت الگير؟ 41B4 (اختبار)", "", 0, false), firstByteOnly = true, test = true) { it },
        BmwPid(0x18, 0x41A1, d(0x2023, "زيت الگير؟ 41A1 (اختبار)", "", 0, false), firstByteOnly = true, test = true) { it },
        BmwPid(0x18, 0x4431, d(0x2024, "الگير 4431 (اختبار)", "", 0, true), test = true) { it },
    )

    fun def(key: Int): PidDef = if (key == BOOST) boost else
        bmw.firstOrNull { it.def.pid == key }?.def ?: all.first { it.pid == key }
}
