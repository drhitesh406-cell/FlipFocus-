package com.flipfocus.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")
private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

@Composable
fun Tile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
        .border(1.dp, C.line, RoundedCornerShape(20.dp)).padding(18.dp),
    content = content,
)

@Composable
fun Title(t: String) = Text(t, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = C.text, modifier = Modifier.padding(bottom = 4.dp))

@Composable
fun Pill(label: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) = Box(
    modifier.clip(CircleShape).then(if (filled) Modifier.background(C.text) else Modifier.border(1.dp, C.line, CircleShape))
        .clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 16.dp),
    contentAlignment = Alignment.Center,
) { Text(label, color = if (filled) Color.Black else C.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }

@Composable
fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Focus", "Schedules", "Insights")
    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            NavigationBar(containerColor = C.bg) {
                labels.forEachIndexed { i, l ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Box(Modifier.size(6.dp).clip(CircleShape).background(if (tab == i) C.text else Color.Transparent)) },
                        label = { Text(l, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = C.text, unselectedTextColor = C.muted, indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> FocusScreen(); 1 -> SchedulesScreen(); else -> StatsScreen() }
        }
    }
}

// ---------------------------------------------------------------- Focus
@Composable
fun FocusScreen() {
    val ctx = LocalContext.current
    val phase by FocusState.phase.collectAsState()
    val rem by FocusState.remainingMs.collectAsState()
    val total by FocusState.totalMs.collectAsState()
    var mins by rememberSaveable { mutableIntStateOf(25) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        FocusService.start(ctx, mins) // proceed even if denied; call bypass needs READ_PHONE_STATE
    }
    val active = phase != Phase.IDLE
    val secs = ((if (active) rem else mins * 60_000L) / 1000).coerceAtLeast(0)
    val progress = if (active && total > 0) rem.toFloat() / total else 1f
    val (status, color) = when (phase) {
        Phase.IDLE -> "Ready" to C.muted
        Phase.ARMING -> "Place phone face-down" to C.text
        Phase.FOCUSED -> "Stay focused" to C.accent
        Phase.VIOLATION -> "Put it back face-down" to C.bad
        Phase.PAUSED_CALL -> "Paused for call" to C.muted
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(280.dp)) {
                val sw = 6.dp.toPx()
                val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
                drawArc(C.line, 0f, 360f, false, tl, sz, style = Stroke(sw))
                drawArc(color.takeIf { active } ?: C.line, -90f, 360f * progress, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Text("%02d:%02d".format(secs / 60, secs % 60), fontFamily = Mono, fontSize = 60.sp, color = C.text)
        }
        Spacer(Modifier.height(24.dp))
        Text(status, color = color, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        if (!active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60, 90).forEach { m ->
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (m == mins) C.text else C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp))
                            .clickable { mins = m }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) { Text("$m", color = if (m == mins) Color.Black else C.text, fontWeight = FontWeight.Medium) }
                }
            }
            Text("minutes", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            Pill("Start focus", true, {
                val need = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE)
                launcher.launch(need)
            }, Modifier.fillMaxWidth())
        } else {
            Pill("End session", false, { FocusService.stop(ctx) }, Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- Schedules
@Composable
fun SchedulesScreen() {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val list by dao.schedules().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var exact by remember { mutableStateOf(Scheduler.canExact(ctx)) }
    var a11y by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exact = Scheduler.canExact(ctx)
        a11y = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(ctx.packageName)
        scope.launch(Dispatchers.IO) { Scheduler.rescheduleAll(ctx) }
    }
    val e = editing
    if (e != null) {
        ScheduleEditor(e, { editing = null }, onDelete = {
            editing = null
            if (e.id != 0) scope.launch { Scheduler.cancel(ctx, e.id); dao.deleteSchedule(e) }
        }) { s ->
            editing = null
            scope.launch { val id = dao.upsertSchedule(s).toInt(); Scheduler.schedule(ctx, s.copy(id = if (s.id == 0) id else s.id)) }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Schedules") }
        if (!a11y) item {
            Tile {
                Text("Turn on the accessibility service so blocked apps can be closed during a schedule.", color = C.muted, fontSize = 14.sp)
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open settings", color = C.accent) }
            }
        }
        if (!exact) item {
            Tile {
                Text("Exact alarms are off, so schedules may start a few minutes late.", color = C.muted, fontSize = 14.sp)
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Allow exact alarms", color = C.accent) }
            }
        }
        items(list, key = { it.id }) { s ->
            val n = s.apps.split(",").count { it.isNotBlank() }
            Tile(Modifier.clickable { editing = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.text)
                        Text("${hhmm(s.startMin)} – ${hhmm(s.endMin)}", fontFamily = Mono, color = C.muted, fontSize = 14.sp)
                    }
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { on -> scope.launch { dao.setEnabled(s.id, on); Scheduler.schedule(ctx, s.copy(enabled = on)) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = Color.Black,
                            uncheckedTrackColor = C.line, uncheckedThumbColor = C.muted, uncheckedBorderColor = C.line),
                    )
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    DAYS.forEachIndexed { i, d ->
                        Text(d, color = if (s.days and (1 shl i) != 0) C.text else C.line, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("$n apps", color = C.muted, fontSize = 13.sp)
                }
            }
        }
        item {
            Pill("New schedule", false, {
                editing = ScheduleEntity(name = "", days = 0b0011111, startMin = 22 * 60, endMin = 6 * 60)
            }, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditor(init: ScheduleEntity, onClose: () -> Unit, onDelete: () -> Unit, onSave: (ScheduleEntity) -> Unit) {
    var name by remember { mutableStateOf(init.name) }
    var days by remember { mutableIntStateOf(init.days) }
    var apps by remember { mutableStateOf(init.apps.split(",").filter { it.isNotBlank() }.toSet()) }
    var picking by remember { mutableStateOf(false) }
    val st = rememberTimePickerState(init.startMin / 60, init.startMin % 60, true)
    val en = rememberTimePickerState(init.endMin / 60, init.endMin % 60, true)
    BackHandler { if (picking) picking = false else onClose() }
    if (picking) { AppPicker(apps, { apps = it }) { picking = false }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(if (init.id == 0) "New schedule" else "Edit schedule")
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DAYS.forEachIndexed { i, d ->
                val on = days and (1 shl i) != 0
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (on) C.text else C.surface)
                        .border(1.dp, C.line, CircleShape).clickable { days = days xor (1 shl i) },
                    contentAlignment = Alignment.Center,
                ) { Text(d, color = if (on) Color.Black else C.muted, fontWeight = FontWeight.Medium) }
            }
        }
        Tile { Text("Start", color = C.muted); TimeInput(st) }
        Tile { Text("End", color = C.muted); TimeInput(en) }
        Tile(Modifier.clickable { picking = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Blocked apps", color = C.text, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text("${apps.size} selected", color = C.muted)
            }
        }
        Text("During the schedule, keep your phone face-down. Picking it up makes it vibrate until you flip it back.", color = C.muted, fontSize = 13.sp)
        Pill("Save", true, {
            onSave(init.copy(name = name.ifBlank { "Focus" }, days = days, startMin = st.hour * 60 + st.minute,
                endMin = en.hour * 60 + en.minute, apps = apps.joinToString(",")))
        }, Modifier.fillMaxWidth())
        if (init.id != 0) Pill("Delete", false, onDelete, Modifier.fillMaxWidth())
    }
}

@Composable
fun AppPicker(selected: Set<String>, onChange: (Set<String>) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(q, PackageManager.ResolveInfoFlags.of(0L))
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != ctx.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Title("Select apps") }
            items(apps, key = { it.first }) { (pkg, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = C.text, modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Switch(
                        checked = pkg in selected,
                        onCheckedChange = { onChange(if (it) selected + pkg else selected - pkg) },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = Color.Black,
                            uncheckedTrackColor = C.line, uncheckedThumbColor = C.muted, uncheckedBorderColor = C.line),
                    )
                }
            }
        }
        Pill("Done (${selected.size})", true, onDone, Modifier.fillMaxWidth().padding(20.dp))
    }
}

// ---------------------------------------------------------------- Stats
@Composable
fun StatsScreen() {
    val dao = Db.get(LocalContext.current).dao()
    val sessions by dao.sessions().collectAsState(emptyList())
    val bypass by dao.count("CALL_BYPASS").collectAsState(0)
    val blocks by dao.count("BLOCK").collectAsState(0)
    val early by dao.count("EARLY_END").collectAsState(0)
    val pct = sessions.adherencePct() / 100f
    val focusMin = sessions.sumOf { (it.endMs - it.startMs - it.faceUpMs).coerceAtLeast(0) } / 60_000
    val week = remember(sessions) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        (6 downTo 0).map { i ->
            val d = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
            val s0 = d.timeInMillis; val e0 = s0 + 86_400_000L
            val m = sessions.filter { it.startMs in s0 until e0 }
                .sumOf { (it.endMs - it.startMs - it.faceUpMs).coerceAtLeast(0) } / 60_000
            "SMTWTFS"[d.get(Calendar.DAY_OF_WEEK) - 1].toString() to m
        }
    }
    val max = (week.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Insights") }
        item {
            Tile {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(170.dp)) {
                        val sw = 10.dp.toPx()
                        val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
                        drawArc(C.line, 0f, 360f, false, tl, sz, style = Stroke(sw))
                        drawArc(C.accent, -90f, 360f * pct, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(pct * 100).toInt()}%", fontFamily = Mono, fontSize = 40.sp, color = C.text)
                        Text("Adherence", color = C.muted, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Sessions", "${sessions.size}", Modifier.weight(1f))
                Stat("Focused", "${focusMin / 60}h ${focusMin % 60}m", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Pickups", "${sessions.sumOf { it.pickups }}", Modifier.weight(1f))
                Stat("Call bypass", "$bypass", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Interruptions", "$early", Modifier.weight(1f))
                Stat("Apps blocked", "$blocks", Modifier.weight(1f))
            }
        }
        item {
            Tile {
                Text("Last 7 days", color = C.muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().height(110.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    week.forEach { (d, m) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.width(22.dp).height((70f * m / max).coerceAtLeast(3f).dp).clip(RoundedCornerShape(6.dp)).background(if (m > 0) C.accent else C.line))
                            Text(d, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        item { Text("Recent sessions", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        items(sessions.take(10), key = { it.id }) { s ->
            val f = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.format(java.util.Date(s.startMs)), color = C.text)
                    Text("${s.plannedMs / 60_000} min · ${s.pickups} pickups", color = C.muted, fontSize = 13.sp)
                }
                Text("${(s.adherence() * 100).toInt()}%", fontFamily = Mono, color = if (s.adherence() >= 0.8f) C.accent else C.bad)
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) = Tile(modifier) {
    Text(value, fontFamily = Mono, fontSize = 24.sp, color = C.text)
    Text(label, color = C.muted, fontSize = 13.sp)
}
