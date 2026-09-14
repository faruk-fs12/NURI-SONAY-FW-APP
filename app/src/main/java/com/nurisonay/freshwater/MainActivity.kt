package com.nurisonay.freshwater

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

private val Navy = Color(0xFF073E68)
private val Blue = Color(0xFF0877C9)
private val Red = Color(0xFFD83434)
private val Teal = Color(0xFF0B9A7A)
private val LightBlue = Color(0xFFEAF5FC)
private val LightGreen = Color(0xFFE7F7EC)
private val TextDark = Color(0xFF0E2840)
private val Amber = Color(0xFFE6A400)

private const val LOCAL_APP_UNUSABLE_M3 = 5.0
private const val DEFAULT_DAILY_CONSUMPTION_M3 = 2.0
private val NOMINAL_FW_CAPACITY = 131.629
private val USABLE_FW_CAPACITY = NOMINAL_FW_CAPACITY - LOCAL_APP_UNUSABLE_M3

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FreshWaterApp() }
    }
}

data class TankDef(val id: String, val ad: String, val kapasite: Double)
data class Hydro(val draft: Double, val disp: Double, val lcf: Double, val mct: Double, val tpc: Double)
data class Record(val date: String, val total: Double, val eva: Double, val consumption: Double)

val tanks = listOf(
    TankDef("FW 1 PORT", "FW No.1 İskele", 23.285),
    TankDef("FW 1 STRBD", "FW No.1 Sancak", 23.285),
    TankDef("FW CENTER", "FW Center", 21.857),
    TankDef("FW 2 PORT", "FW No.2 İskele", 19.534),
    TankDef("FW 2 STRBD", "FW No.2 Sancak", 23.611),
    TankDef("DRINKING WATER PORT", "Drinking Water İskele", 9.384),
    TankDef("DRINKING WATER STRB", "Drinking Water Sancak", 10.673)
)

private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

class CalcEngine(private val context: Context) {
    private val fwRows: List<Pair<Double, Map<String, DoubleArray>>> by lazy { loadFw() }
    private val hydroRows: List<Hydro> by lazy { loadHydro() }

    private fun loadFw(): List<Pair<Double, Map<String, DoubleArray>>> {
        val lines = context.assets.open("fw_tables.csv").bufferedReader().readLines()
        if (lines.size < 2) return emptyList()
        val headers = lines[0].split(',')
        val tankIndex = mutableMapOf<String, Int>()
        tanks.forEach { t -> tankIndex[t.id] = headers.indexOfFirst { it.startsWith(t.id + "|") } }

        return lines.drop(1).mapNotNull { line ->
            val p = line.split(',')
            val snd = p.firstOrNull()?.toDoubleOrNull() ?: return@mapNotNull null
            val map = mutableMapOf<String, DoubleArray>()
            tanks.forEach { t ->
                val start = tankIndex[t.id] ?: -1
                if (start >= 0) {
                    map[t.id] = DoubleArray(8) { j -> p.getOrNull(start + j)?.toDoubleOrNull() ?: Double.NaN }
                }
            }
            snd to map
        }
    }

    private fun loadHydro(): List<Hydro> = context.assets.open("hydrostatic.csv")
        .bufferedReader().readLines().drop(1).mapNotNull { line ->
            val p = line.split(',').mapNotNull { it.toDoubleOrNull() }
            if (p.size < 5) null else Hydro(p[0], p[1], p[2], p[3], p[4])
        }

    /* FW TAKIP.xlsx'teki trim seçim/interpolasyon mantığının uygulama karşılığı. */
    private fun excelTrimPosition(trim: Double): Pair<Int, Double> {
        val t = trim.coerceIn(-1.0, 3.0)
        val baseTrim = when {
            t >= 3.0 -> 6
            t >= 2.5 -> 5
            t >= 2.0 -> 4
            t >= 1.5 -> 3
            t >= 1.0 -> 2
            t >= 0.5 -> 1
            t >= 0.0 -> 0
            else -> -1
        }
        val exactBreak = listOf(-1.0, 0.5, 1.5, 2.5, 3.0).any { abs(t - it) < 1e-9 }
        val fraction = if (exactBreak) 0.0 else t - floor(t)
        return baseTrim to fraction.coerceIn(0.0, 1.0)
    }

    fun tankVolume(tankId: String, sounding: Double, trim: Double): Double {
        if (fwRows.isEmpty()) return 0.0
        val row = fwRows.lastOrNull { it.first <= sounding } ?: fwRows.first()
        val arr = row.second[tankId] ?: return 0.0
        val (baseTrim, fraction) = excelTrimPosition(trim)
        val loIndex = (baseTrim + 1).coerceIn(0, 7)
        val hiIndex = (loIndex + 1).coerceAtMost(7)
        val a = arr.getOrNull(loIndex) ?: Double.NaN
        val b = arr.getOrNull(hiIndex) ?: a
        if (a.isNaN()) return 0.0
        if (fraction <= 0.0 || hiIndex == loIndex || b.isNaN()) return a
        return a + (b - a) * fraction
    }

    fun hydro(meanDraft: Double): Hydro? {
        if (hydroRows.isEmpty()) return null
        val lower = hydroRows.lastOrNull { it.draft <= meanDraft } ?: hydroRows.first()
        val upper = hydroRows.firstOrNull { it.draft >= meanDraft } ?: hydroRows.last()
        if (abs(upper.draft - lower.draft) < 1e-9) return lower
        val f = (meanDraft - lower.draft) / (upper.draft - lower.draft)
        fun i(a: Double, b: Double) = a + (b - a) * f
        return Hydro(meanDraft, i(lower.disp, upper.disp), i(lower.lcf, upper.lcf), i(lower.mct, upper.mct), i(lower.tpc, upper.tpc))
    }
}

@Composable
fun FreshWaterApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("fw", Context.MODE_PRIVATE) }
    var themeName by remember { mutableStateOf(prefs.getString("theme", "blue") ?: "blue") }
    var fontScale by remember { mutableFloatStateOf(prefs.getFloat("fontScale", 1.0f)) }
    val baseDensity = LocalDensity.current
    val primary = if (themeName == "red") Red else Navy
    val secondary = if (themeName == "red") Color(0xFFEF5350) else Blue

    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
        MaterialTheme(colorScheme = lightColorScheme(primary = primary, secondary = secondary)) {
            FreshWaterContent(
                prefs = prefs,
                themeName = themeName,
                fontScale = fontScale,
                onThemeChange = {
                    themeName = it
                    prefs.edit().putString("theme", it).apply()
                },
                onFontScaleChange = {
                    fontScale = it
                    prefs.edit().putFloat("fontScale", it).apply()
                }
            )
        }
    }
}

@Composable
private fun FreshWaterContent(
    prefs: SharedPreferences,
    themeName: String,
    fontScale: Float,
    onThemeChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit
) {
    val ctx = LocalContext.current
    val engine = remember { CalcEngine(ctx) }
    var screen by remember { mutableStateOf("home") }
    var recordsVersion by remember { mutableIntStateOf(0) }
    var fwd by remember { mutableStateOf(prefs.getString("fwd", "6.90") ?: "6.90") }
    var aft by remember { mutableStateOf(prefs.getString("aft", "8.15") ?: "8.15") }
    var eva by remember { mutableStateOf(prefs.getString("eva", "0.0") ?: "0.0") }
    var received by remember { mutableStateOf(prefs.getString("received", "0.0") ?: "0.0") }
    var crew by remember { mutableStateOf(prefs.getString("crew", "19") ?: "19") }
    var soundings by remember {
        mutableStateOf(tanks.associate { it.id to (prefs.getString("snd_${it.id}", "0.00") ?: "0.00") })
    }

    val fwdD = num(fwd) ?: 0.0
    val aftD = num(aft) ?: 0.0
    val trim = aftD - fwdD
    val mean = (aftD + fwdD) / 2.0
    val volumes = tanks.associate { t -> t.id to engine.tankVolume(t.id, num(soundings[t.id] ?: "0") ?: 0.0, trim) }
    val total = volumes.values.sum()
    val localAppHeld = min(LOCAL_APP_UNUSABLE_M3, volumes["FW 1 STRBD"] ?: 0.0)
    val usableTotal = (total - localAppHeld).coerceAtLeast(0.0)
    val oldRecords = remember(screen, total, recordsVersion) { loadRecords(prefs) }
    val yesterday = oldRecords.firstOrNull()?.total ?: 0.0
    val evaD = num(eva) ?: 0.0
    val receivedD = num(received) ?: 0.0
    val calculatedConsumption = (yesterday + evaD + receivedD - total).coerceAtLeast(0.0)
    val consumption = if (oldRecords.isEmpty()) DEFAULT_DAILY_CONSUMPTION_M3 else calculatedConsumption
    val crewCount = crew.toIntOrNull() ?: 0
    val perPerson = if (crewCount > 0) consumption / crewCount else 0.0
    val historyConsumption = oldRecords.take(7).map { it.consumption }.filter { it > 0.0 }
    val forecastDaily = if (historyConsumption.isEmpty()) DEFAULT_DAILY_CONSUMPTION_M3 else historyConsumption.average()
    val days = if (forecastDaily > 0.0) usableTotal / forecastDaily else 0.0

    Scaffold(
        topBar = {
            TopBar(
                screen = screen,
                onHome = { screen = "home" },
                onSettings = { screen = "settings" }
            )
        },
        bottomBar = { BottomNav(screen = screen, onGo = { screen = it }) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                "home" -> HomeScreen(total, usableTotal, yesterday, evaD, consumption, forecastDaily, days, crewCount) { screen = it }
                "tanks" -> TankListScreen(tanks, soundings, volumes, trim, usableTotal) { id, v ->
                    soundings = soundings.toMutableMap().also { it[id] = v }
                    prefs.edit().putString("snd_$id", v).apply()
                }
                "detail" -> TankDetailScreen(tanks, volumes, soundings, total, usableTotal)
                "report" -> DailyReportScreen(
                    fwd, aft, eva, received, crew, total, usableTotal, yesterday, trim,
                    consumption, perPerson, forecastDaily, days,
                    onFwd = { fwd = it }, onAft = { aft = it }, onEva = { eva = it },
                    onReceived = { received = it }, onCrew = { crew = it },
                    onSave = {
                        prefs.edit()
                            .putString("fwd", fwd).putString("aft", aft).putString("eva", eva)
                            .putString("received", received).putString("crew", crew).apply()
                        saveRecord(prefs, Record(LocalDate.now().toString(), total, evaD, consumption))
                        recordsVersion++
                        screen = "history"
                    }
                )
                "hydro" -> HydroScreen(fwd, aft, trim, mean, engine.hydro(mean))
                "history" -> HistoryScreen(loadRecords(prefs)) { index ->
                    deleteRecordAt(prefs, index)
                    recordsVersion++
                }
                "settings" -> SettingsScreen(
                    themeName = themeName,
                    fontScale = fontScale,
                    onThemeChange = onThemeChange,
                    onFontScaleChange = onFontScaleChange,
                    onAbout = { screen = "about" }
                )
                "about" -> AboutScreen()
            }
        }
    }
}

@Composable
fun TopBar(screen: String, onHome: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (screen != "home") {
                Text("‹", fontSize = 34.sp, color = Color.White, modifier = Modifier.clickable { onHome() }.padding(end = 10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("M/V NURI SONAY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("Tatlı Su Yönetimi", color = Color.White.copy(.85f), fontSize = 12.sp)
            }
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Ayarlar",
                tint = Color.White,
                modifier = Modifier.size(28.dp).clickable { onSettings() }.padding(2.dp)
            )
        }
    }
}

private data class NavItem(val id: String, val label: String, val icon: ImageVector)

@Composable
fun BottomNav(screen: String, onGo: (String) -> Unit) {
    val items = listOf(
        NavItem("home", "Ana Sayfa", Icons.Filled.Home),
        NavItem("tanks", "Tanklar", Icons.Filled.WaterDrop),
        NavItem("history", "Geçmiş", Icons.Filled.History),
        NavItem("report", "Rapor", Icons.Filled.Assessment),
        NavItem("settings", "Ayarlar", Icons.Filled.Settings)
    )
    NavigationBar(containerColor = Color.White) {
        items.forEach { item ->
            NavigationBarItem(
                selected = screen == item.id,
                onClick = { onGo(item.id) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
fun HomeScreen(
    total: Double,
    usable: Double,
    yest: Double,
    eva: Double,
    cons: Double,
    forecastDaily: Double,
    days: Double,
    crew: Int,
    onGo: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Image(
            painter = painterResource(R.drawable.nuri_sonay),
            contentDescription = "M/V NURI SONAY",
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentScale = ContentScale.Crop
        )
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")), Modifier.padding(16.dp), fontWeight = FontWeight.Bold, color = TextDark)
        Row(Modifier.padding(horizontal = 12.dp)) {
            Kpi("Toplam Tatlı Su", "%.1f m³".format(total), Blue, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Kpi("Kullanılabilir FW", "%.1f m³".format(usable), Navy, Modifier.weight(1f))
        }
        Row(Modifier.padding(12.dp)) {
            Kpi("Dünkü Toplam", "%.1f m³".format(yest), Color(0xFF45627D), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Kpi("EVA Üretimi", "%.1f m³".format(eva), Teal, Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            Kpi("Günlük Sarfiyat", "%.1f m³".format(cons), Color(0xFF3F6AA0), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Kpi("Tahmini Kalan", "%.1f gün".format(days), Color(0xFF4D65A8), Modifier.weight(1f))
        }
        Text("Tahmin sarfiyatı: %.2f m³/gün • Personel: %d".format(forecastDaily, crew), Modifier.padding(14.dp), color = Color.Gray, fontSize = 12.sp)
        LocalAppInfo()
        Action("Günlük Giriş / Rapor") { onGo("report") }
        Action("Tank Sounding ve Miktarlar") { onGo("tanks") }
        Action("Tank Detayları") { onGo("detail") }
        Action("Hidrostatik & Trim") { onGo("hydro") }
        Action("Geçmiş Kayıtlar") { onGo("history") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun Kpi(title: String, value: String, color: Color, mod: Modifier = Modifier) {
    Card(mod, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color.White.copy(.9f), fontSize = 12.sp)
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
    }
}

@Composable
fun Action(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) { Text(text) }
}

@Composable
fun InputHint() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(LightBlue, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text("Değerleri girerken ondalık ayırıcı olarak nokta (.) kullanın.", color = Navy, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

@Composable
fun LocalAppInfo() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Color(0xFFFFECEC), RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text("FW No.1 Sancak: LOCAL APPLICATION nedeniyle ilk 5.00 m³ su kullanılamaz.", color = Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun TankListScreen(
    defs: List<TankDef>,
    snd: Map<String, String>,
    vol: Map<String, Double>,
    trim: Double,
    usableTotal: Double,
    onSnd: (String, String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text("Tüm Tatlı Su Tankları", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextDark)
        Text("Trim: %.2f m %s".format(abs(trim), if (trim >= 0) "Kıça" else "Başa"), fontSize = 12.sp, color = Color.Gray)
        InputHint()
        if (abs(trim) > 3) WarningBox("UYARI: Trim 3.00 m'den büyük. FW TAKIP çalışma notuna göre sounding değerleri doğrulanmalıdır.")
        Spacer(Modifier.height(4.dp))
        defs.forEach { t ->
            val v = vol[t.id] ?: 0.0
            val pct = (v / t.kapasite * 100).coerceIn(0.0, 100.0)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.ad, fontWeight = FontWeight.Bold)
                            Text("Kapasite %.3f m³".format(t.kapasite), fontSize = 12.sp, color = Color.Gray)
                            if (t.id == "FW 1 STRBD") {
                                Text("LOCAL APP.: 5.00 m³ kullanılamaz", fontSize = 11.sp, color = Red, fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedTextField(
                            value = snd[t.id] ?: "",
                            onValueChange = { onSnd(t.id, it) },
                            label = { Text("Sounding (m)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(130.dp),
                            singleLine = true
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (pct / 100).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(9.dp),
                        color = if (pct < 30) Red else if (pct < 60) Amber else Teal
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("%.2f m³".format(v), fontWeight = FontWeight.Bold, color = Blue)
                        Text("%.0f%%".format(pct), fontWeight = FontWeight.Bold)
                    }
                    if (t.id == "FW 1 STRBD") {
                        Text("Kullanılabilir: %.2f m³".format((v - min(LOCAL_APP_UNUSABLE_M3, v)).coerceAtLeast(0.0)), fontSize = 12.sp, color = Navy)
                    }
                }
            }
        }
        val tot = vol.values.sum()
        Text("Toplam Tatlı Su: %.2f m³".format(tot), Modifier.fillMaxWidth().background(LightGreen).padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Text("Kullanılabilir Tatlı Su: %.2f m³".format(usableTotal), Modifier.fillMaxWidth().background(LightBlue).padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun TankDetailScreen(defs: List<TankDef>, vol: Map<String, Double>, snd: Map<String, String>, total: Double, usableTotal: Double) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text("Tank Detayları", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Image(painterResource(R.drawable.fw_tank_duzeni), "FW tank düzeni", Modifier.fillMaxWidth().height(300.dp), contentScale = ContentScale.Fit)
        defs.forEach { t ->
            val v = vol[t.id] ?: 0.0
            val p = (v / t.kapasite * 100).coerceIn(0.0, 100.0)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.ad, fontWeight = FontWeight.Bold)
                        Text("%.0f%%".format(p), fontWeight = FontWeight.Bold, color = if (p < 30) Red else Teal)
                    }
                    LinearProgressIndicator(
                        progress = { (p / 100).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sounding: ${snd[t.id]} m", fontSize = 12.sp)
                        Text("%.2f / %.3f m³".format(v, t.kapasite), fontSize = 12.sp)
                    }
                    if (t.id == "FW 1 STRBD") Text("LOCAL APPLICATION: 5.00 m³ kullanılamaz.", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("GENEL TOPLAM: %.2f m³ / %.3f m³".format(total, NOMINAL_FW_CAPACITY), Modifier.fillMaxWidth().background(LightGreen).padding(12.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("KULLANILABİLİR: %.2f m³ / %.3f m³".format(usableTotal, USABLE_FW_CAPACITY), Modifier.fillMaxWidth().background(LightBlue).padding(12.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun DailyReportScreen(
    fwd: String, aft: String, eva: String, received: String, crew: String,
    total: Double, usableTotal: Double, yest: Double, trim: Double, cons: Double, pp: Double,
    forecastDaily: Double, days: Double,
    onFwd: (String) -> Unit, onAft: (String) -> Unit, onEva: (String) -> Unit,
    onReceived: (String) -> Unit, onCrew: (String) -> Unit, onSave: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Günlük Tatlı Su Raporu", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        InputHint()
        Text("Draft ve Trim", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        TwoFields("Baş Draft (m)", fwd, onFwd, "Kıç Draft (m)", aft, onAft)
        Text(
            "Trim: %.2f m %s".format(abs(trim), if (trim >= 0) "Kıça" else "Başa"),
            Modifier.fillMaxWidth().background(if (abs(trim) > 3) Color(0xFFFFE1E1) else LightGreen).padding(10.dp),
            fontWeight = FontWeight.Bold
        )
        if (abs(trim) > 3) WarningBox("Trim > 3.00 m. Sounding sonuçlarını gemideki onaylı program/tablo ile doğrulayın.")
        Text("Günlük Değerler", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        TwoFields("EVA Üretimi (m³)", eva, onEva, "Alınan FW (m³)", received, onReceived)
        NumField("Personel Sayısı", crew, onCrew)
        SummaryRow("Dünkü FW ROB", yest)
        SummaryRow("Bugünkü FW ROB", total)
        SummaryRow("Kullanılabilir FW ROB", usableTotal)
        SummaryRow("Günlük Sarfiyat", cons, highlight = true)
        SummaryRow("Tahmin Sarfiyat Ortalaması", forecastDaily)
        SummaryRow("Kişi Başı Sarfiyat", pp)
        SummaryRow("Tahmini Kalan Gün", days)
        LocalAppInfo()
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) { Text("Günlük Raporu Kaydet") }
    }
}

@Composable
fun TwoFields(l1: String, v1: String, c1: (String) -> Unit, l2: String, v2: String, c2: (String) -> Unit) {
    Row {
        OutlinedTextField(
            value = v1, onValueChange = c1, label = { Text(l1) }, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = v2, onValueChange = c2, label = { Text(l2) }, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
        )
    }
}

@Composable
fun NumField(label: String, value: String, on: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = on,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
fun SummaryRow(label: String, v: Double, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(if (highlight) Color(0xFFFFE2E2) else Color.Transparent).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text("%.2f".format(v), fontWeight = FontWeight.Bold, color = if (highlight) Red else TextDark)
    }
}

@Composable
fun HydroScreen(fwd: String, aft: String, trim: Double, mean: Double, h: Hydro?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Hidrostatik & Trim", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Image(painterResource(R.drawable.fw_tank_duzeni), "Tank düzeni", Modifier.fillMaxWidth().height(210.dp), contentScale = ContentScale.Fit)
        Info("Baş Draft", "$fwd m")
        Info("Kıç Draft", "$aft m")
        Info("Ortalama Draft", "%.3f m".format(mean))
        Info("Trim", "%.3f m %s".format(abs(trim), if (trim >= 0) "Kıça" else "Başa"))
        if (h != null) {
            Text("Interpolasyonlu Hidrostatik Değerler", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Info("Deplasman", "%.2f t".format(h.disp))
            Info("TPC", "%.3f t/cm".format(h.tpc))
            Info("MCT 1 cm", "%.3f t·m/cm".format(h.mct))
            Info("LCF (AP'den)", "%.3f m".format(h.lcf))
        }
        if (abs(trim) > 3) {
            WarningBox("FW çalışma notu: Trim 3.00 m'den fazla olduğunda sounding sonuçları doğrulanmalıdır.")
        } else {
            Box(Modifier.fillMaxWidth().background(LightGreen).padding(12.dp)) {
                Text("Trim kabul edilen çalışma aralığında (|Trim| ≤ 3.00 m).", color = Color(0xFF126A31))
            }
        }
    }
}

@Composable
fun Info(a: String, b: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(a)
        Text(b, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun WarningBox(s: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFFFFE3E3), RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text(s, color = Red, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HistoryScreen(recs: List<Record>, onDelete: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Geçmiş Kayıtlar", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        if (recs.isEmpty()) {
            Text("Henüz kayıt yok.", Modifier.padding(16.dp), color = Color.Gray)
        } else {
            recs.forEachIndexed { index, r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.date, fontWeight = FontWeight.Bold)
                            Text("Toplam FW %.2f m³".format(r.total), fontSize = 12.sp)
                            Text("EVA %.2f m³ • Sarfiyat %.2f m³".format(r.eva, r.consumption), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onDelete(index) }) { Text("Sil", color = Red, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    themeName: String,
    fontScale: Float,
    onThemeChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onAbout: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Ayarlar ve Gemi Bilgileri", fontSize = 21.sp, fontWeight = FontWeight.Bold)

        Text("Renk Teması", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth()) {
            ChoiceButton("Mavi", themeName == "blue", Blue, Modifier.weight(1f)) { onThemeChange("blue") }
            Spacer(Modifier.width(8.dp))
            ChoiceButton("Kırmızı", themeName == "red", Red, Modifier.weight(1f)) { onThemeChange("red") }
        }

        Text("Yazı Boyutu", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth()) {
            FontButton("Küçük", 0.90f, fontScale, Modifier.weight(1f), onFontScaleChange)
            Spacer(Modifier.width(6.dp))
            FontButton("Normal", 1.00f, fontScale, Modifier.weight(1f), onFontScaleChange)
            Spacer(Modifier.width(6.dp))
            FontButton("Büyük", 1.15f, fontScale, Modifier.weight(1f), onFontScaleChange)
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Info("Gemi", "M/V NURI SONAY")
                Info("IMO", "9310202")
                Info("Tür", "General Cargo")
                Info("LBP", "134 m")
                Info("FW Nominal Kapasite", "%.3f m³".format(NOMINAL_FW_CAPACITY))
                Info("Kullanılabilir Kapasite", "%.3f m³".format(USABLE_FW_CAPACITY))
            }
        }
        LocalAppInfo()
        InputHint()
        Action("Hakkında") { onAbout() }
        WarningBox("Bu uygulamadaki sonuçlar operasyonel kolaylık içindir. Gemideki onaylı programlar ve resmi tablolar esas alınmalıdır.")
    }
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) color else Color(0xFFE2E6EA), contentColor = if (selected) Color.White else TextDark)
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

@Composable
private fun FontButton(text: String, value: Float, current: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val selected = abs(current - value) < 0.01f
    OutlinedButton(onClick = { onChange(value) }, modifier = modifier) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else TextDark)
    }
}

@Composable
fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Hakkında", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text("M/V NURI SONAY\nTatlı Su Yönetimi", textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = LightBlue)) {
            Column(Modifier.padding(18.dp)) {
                Text("Hazırlayan", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Chf. Off. Ömer Sav", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text("Amaç", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Bu program gemide tatlı su miktarlarının günlük takibini, tank sounding değerlerinin hesaplanmasını, EVA üretiminin ve günlük sarfiyatın pratik şekilde izlenmesini kolaylaştırmak amacıyla hazırlanmıştır.")
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE4E4))) {
            Column(Modifier.padding(18.dp)) {
                Text("ÖNEMLİ UYARI", color = Red, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("Bu uygulama tamamen kolaylık amacıyla hazırlanmıştır. Hiçbir resmi özelliği yoktur ve herhangi bir sorumluluk doğurmaz. Hesaplar ve sonuçlar tek başına resmi/operasyonel karar için kullanılmamalıdır. Gemide bulunan onaylı programlar, resmi tank tabloları, draft survey/hidrostatik dokümanları ve şirket prosedürleri esas alınmalıdır.", fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("FW hesabı veri kaynağı: FW TAKIP.xlsx. Hidrostatik kaynak: 06-26 BANDIRMA BB+BULK.xlsx.", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
    }
}

fun saveRecord(p: SharedPreferences, r: Record) {
    val old = p.getString("records", "") ?: ""
    val line = "${r.date}|${r.total}|${r.eva}|${r.consumption}"
    p.edit().putString("records", (line + "\n" + old).trim()).putString("yesterday", r.total.toString()).apply()
}

fun deleteRecordAt(p: SharedPreferences, index: Int) {
    val raw = p.getString("records", "") ?: ""
    val lines = raw.lines().filter { it.isNotBlank() }.toMutableList()
    if (index in lines.indices) lines.removeAt(index)
    p.edit().putString("records", lines.joinToString("\n")).apply()
}

fun loadRecords(p: SharedPreferences): List<Record> {
    val raw = p.getString("records", "") ?: ""
    return raw.lines().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 4) null else try {
            Record(parts[0], parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble())
        } catch (_: Exception) {
            null
        }
    }
}
