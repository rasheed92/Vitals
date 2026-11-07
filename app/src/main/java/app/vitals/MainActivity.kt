package app.vitals

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

private val Bg = Color(0xFF0A0C10)
private val CardBg = Color(0xFF14181F)
private val Muted = Color(0xFF8B93A1)
private val Accent = Color(0xFF3B82F6)
private val Warn = Color(0xFFE5484D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, secondary = Warn, background = Bg, surface = CardBg,
                    onBackground = Color.White, onSurface = Color.White,
                ),
            ) { App() }
        }
    }
}

@Composable
fun App(vm: ObdViewModel = viewModel()) {
    var granted by remember { mutableStateOf(vm.hasBtPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
        if (granted) vm.startScan()
    }
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(containerColor = Bg, topBar = { Header(vm) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                !granted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { launcher.launch(requiredPermissions()) }) { Text("السماح بالبلوتوث") }
                }
                !vm.connected -> DevicePicker(vm)
                else -> {
                    TabRow(selectedTabIndex = tab, containerColor = Bg) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("البيانات") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("البانزين") })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("BMW") })
                        Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("الكونسول") })
                    }
                    when (tab) {
                        0 -> Dashboard(vm)
                        1 -> FuelScreen(vm.fuel)
                        2 -> BmwScanner(vm)
                        else -> Console(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(vm: ObdViewModel) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Vitals", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(vm.status, color = Muted, fontSize = 12.sp, maxLines = 2)
        }
        if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (vm.connected) {
            TextButton(onClick = { vm.resetMinMax() }) { Text("تصفير") }
            TextButton(onClick = { vm.disconnect() }) { Text("فصل", color = Warn) }
        }
    }
}

@Composable
private fun DevicePicker(vm: ObdViewModel) {
    LaunchedEffect(Unit) { vm.startScan() }
    Column(Modifier.padding(16.dp)) {
        Text(
            "المحرك لازم يكون شغّال، وسكّر تطبيقات OBDLink و BimmerCode حتى ما تحجز الأداپتر. " +
                "أول مرة راح يطلب اقتران (الرمز 123456)، وهذا يشتغل بس أول 5 دقايق بعد تركيب الأداپتر.",
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.startScan() }, enabled = !vm.scanning && !vm.busy) {
                Text(if (vm.scanning) "يدوّر…" else "دوّر من جديد")
            }
            Spacer(Modifier.width(12.dp))
            if (vm.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listItems(vm.sortedFound(), key = { it.address }) { d ->
                val isObd = d.name.contains("OBD", ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
                        .clickable(enabled = !vm.busy) { vm.connect(d.address) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = if (isObd) Accent else Color.White, fontSize = 16.sp, fontWeight = if (isObd) FontWeight.Bold else FontWeight.Normal)
                        Text(d.address, color = Muted, fontSize = 12.sp)
                    }
                    Text("${d.rssi} dBm", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Dashboard(vm: ObdViewModel) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        gridItems(vm.visibleKeys()) { key -> GaugeCard(Pids.def(key), vm.values[key]) }
    }
}

private fun fmt(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

@Composable
private fun GaugeCard(def: PidDef, r: Reading?) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(CardBg).padding(12.dp)) {
        Text(def.label, color = Muted, fontSize = 13.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r?.let { def.text?.invoke(it.value) ?: fmt(it.value, def.decimals) } ?: "—", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(def.unit, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        Sparkline(r?.history ?: emptyList(), Modifier.fillMaxWidth().height(34.dp))
        Text(
            if (r != null && def.text == null) "أقل ${fmt(r.min, def.decimals)} · أعلى ${fmt(r.max, def.decimals)}" else " ",
            color = Muted, fontSize = 11.sp,
        )
    }
}

@Composable
private fun Sparkline(h: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        if (h.size < 2) return@Canvas
        val mn = h.min()
        val span = (h.max() - mn).takeIf { it > 1e-6f } ?: 1f
        val path = Path()
        h.forEachIndexed { i, v ->
            val x = size.width * i / (h.size - 1)
            val y = size.height - (v - mn) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Accent, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun iqd(v: Double) = String.format(Locale.US, "%,d", Math.round(v / 250.0) * 250)

@Composable
private fun FuelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FuelCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(14.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun FuelScreen(f: FuelTracker) {
    var priceText by remember { mutableStateOf(if (f.price > 0) f.price.toString() else "") }
    var stationText by remember { mutableStateOf("") }
    val hasPrice = f.price > 0
    fun money(v: Double) = if (hasPrice) iqd(v) + " دينار" else "حط سعر اللتر"

    if (f.askStation) {
        AlertDialog(
            onDismissRequest = { f.dismissStation() },
            title = { Text("عبّيت بانزين؟") },
            text = {
                Column {
                    Text("اكتب اسم المحطة حتى تقارن جودة البانزين بين المحطات.", color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = stationText, onValueChange = { stationText = it }, singleLine = true, label = { Text("اسم المحطة") })
                }
            },
            confirmButton = { TextButton(onClick = { f.nameStation(stationText); stationText = "" }) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { f.dismissStation() }) { Text("بعدين") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = priceText,
            onValueChange = { t -> priceText = t.filter { it.isDigit() }.take(6); f.updatePrice(priceText.toIntOrNull() ?: 0) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("سعر لتر البانزين (دينار)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        val t = f.current
        FuelCard("جودة البانزين") {
            val station = t.station.ifEmpty { "بدون اسم" }
            FuelRow("التانكي الحالي", station)
            val sc = t.score
            if (sc == null) {
                FuelRow("الحالة", "يجمع بيانات ${t.n * 100 / FuelTracker.MIN_SAMPLES}%")
                Text("يحتاج سواقة عادية فيها دوس متوسط، مو سلانسيه بس.", color = Muted, fontSize = 12.sp)
            } else {
                val verdict = when {
                    sc > 0.5 -> "زين، أحسن من المعتاد"
                    sc < -0.5 -> "أردأ من المعتاد"
                    else -> "طبيعي"
                }
                FuelRow("التقييم", verdict)
                FuelRow("الفرق بالإشعال", String.format(Locale.US, "%+.1f°", sc))
                f.previous?.score?.let { ps ->
                    val d = sc - ps
                    val name = f.previous?.station?.ifEmpty { "التعبئة السابقة" } ?: "التعبئة السابقة"
                    FuelRow("مقارنة ويا $name", if (d >= 0) String.format(Locale.US, "أحسن بـ %.1f°", d) else String.format(Locale.US, "أردأ بـ %.1f°", -d))
                }
            }
            Row {
                TextButton(onClick = { stationText = ""; f.newTank() }) { Text("عبّيت هسه") }
                TextButton(onClick = { stationText = t.station; f.renameCurrent() }) { Text("سمّي المحطة") }
            }
        }

        FuelCard("هالمشوار") {
            FuelRow("المسافة", String.format(Locale.US, "%.1f كم", f.tripKm))
            FuelRow("البانزين", String.format(Locale.US, "%.2f لتر", f.tripLiters))
            if (f.tripKm > 1) FuelRow("المعدل", String.format(Locale.US, "%.1f لتر/100كم", f.tripLiters / f.tripKm * 100))
            f.instantL100?.let { FuelRow("هسه", String.format(Locale.US, "%.1f لتر/100كم", it)) }
            FuelRow("الكلفة", money(f.tripCost()))
        }

        FuelCard("الوقوف بالزحمة") {
            FuelRow("وقت الوقوف والمحرك شغّال", String.format(Locale.US, "%d دقيقة", (f.idleSeconds / 60).toInt()))
            FuelRow("بانزين صرفته وأنت واگف", String.format(Locale.US, "%.2f لتر", f.idleLiters))
            FuelRow("كلفته", money(f.idleCost()))
        }

        FuelCard("هالشهر") {
            FuelRow("المسافة", String.format(Locale.US, "%.0f كم", f.monthKm))
            FuelRow("البانزين", String.format(Locale.US, "%.1f لتر", f.monthLiters))
            FuelRow("الكلفة", money(f.monthCost()))
        }

        val past = f.tanks.dropLast(1).reversed().filter { it.score != null }
        if (past.isNotEmpty()) {
            FuelCard("التعبئات السابقة") {
                for (p in past.take(10)) {
                    FuelRow(p.station.ifEmpty { "بدون اسم" }, String.format(Locale.US, "%+.1f°", p.score ?: 0.0))
                }
            }
        }
        Text(
            "التقدير محسوب من كمية الهواء، فالكلفة تقريبية (±10%). جودة البانزين تنقاس بتأخير الإشعال: الرقم السالب يعني المحرك سحب الإشعال أكثر من عادته.",
            color = Muted, fontSize = 11.sp,
        )
    }
}

@Composable
private fun BmwScanner(vm: ObdViewModel) {
    var ecu by remember { mutableIntStateOf(0x12) }
    var from by remember { mutableStateOf("4000") }
    var to by remember { mutableStateOf("4FFF") }
    val clipboard = LocalClipboardManager.current
    val presets = listOf("4000" to "4FFF", "5000" to "5FFF", "D000" to "DFFF", "F100" to "F1FF")

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "يقرا قيم BMW الخاصة من الكمبيوتر مباشرة (قراءة بس، ما يغيّر شي). خلي المحرك شغّال، " +
                "والبيانات الحية تتوقف وقت الفحص. بعد الفحص اضغط مراقبة، وخلي السيارة P ودوس بنزين وعوف لحد ما تخلص.",
            color = Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((id, label) in listOf(0x12 to "المحرك DME", 0x18 to "الگير EGS")) {
                val sel = ecu == id
                TextButton(
                    onClick = { ecu = id },
                    enabled = !vm.bmwRunning,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Accent.copy(alpha = 0.25f) else Color.Transparent),
                ) { Text(label, color = if (sel) Color.White else Muted) }
                Spacer(Modifier.width(6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = from, onValueChange = { from = it.uppercase(Locale.US).take(4) },
                modifier = Modifier.weight(1f), singleLine = true, label = { Text("من") }, enabled = !vm.bmwRunning,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = to, onValueChange = { to = it.uppercase(Locale.US).take(4) },
                modifier = Modifier.weight(1f), singleLine = true, label = { Text("إلى") }, enabled = !vm.bmwRunning,
            )
        }
        Row {
            for ((a, b) in presets) {
                TextButton(onClick = { from = a; to = b }, enabled = !vm.bmwRunning) { Text(a.take(2) + "xx", fontSize = 12.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.bmwRunning) {
                Button(onClick = { vm.bmwStop() }) { Text("وگّف") }
            } else {
                Button(onClick = {
                    val f = from.toIntOrNull(16); val t = to.toIntOrNull(16)
                    if (f != null && t != null) vm.bmwScan(ecu, f, t)
                }) { Text("ابدأ الفحص") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { vm.bmwWatch(ecu) }, enabled = vm.canWatch(ecu)) { Text("مراقبة") }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { clipboard.setText(AnnotatedString(vm.bmwReport())) }, enabled = vm.bmwHits.isNotEmpty() || vm.watchReport.isNotEmpty()) {
                Text("نسخ النتائج")
            }
        }
        Text(vm.bmwProgress, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        if (vm.watchReport.isNotEmpty()) {
            Text(
                vm.watchReport, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Accent,
                modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
            )
        }
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            listItems(vm.bmwHits.reversed()) { h ->
                Text(h.line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun Console(vm: ObdViewModel) {
    var cmd by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd, onValueChange = { cmd = it }, modifier = Modifier.weight(1f),
                singleLine = true, placeholder = { Text("مثال: ATRV أو 0105") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.sendRaw(cmd) }) { Text("إرسال") }
        }
        Text("الأوامر تنرسل بين قراءات البيانات، فما تحتاج توقف شي.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            listItems(vm.log.reversed()) { line ->
                Text(
                    line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = if (line.startsWith("ERROR")) Warn else if (line.startsWith(">")) Accent else Color.White,
                )
            }
        }
    }
}
