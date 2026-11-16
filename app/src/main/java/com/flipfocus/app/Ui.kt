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
import android.app.usage.UsageStatsManager
import android.os.PowerManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")
private fun hhmm(m: Int): String {
    val h = m / 60
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = h % 12
    return "%d:%02d %s".format(if (h12 == 0) 12 else h12, m % 60, ampm)
}

@Composable
fun Tile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(C.surface, Color(0xFF090B0E))))
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
fun App(initialTab: Int = 0) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        FocusState.utilityApps.value = Prefs.utility(ctx)
        val rows = withContext(Dispatchers.IO) { Db.get(ctx).dao().untaggedPickups() }
        FocusState.pendingTags.value = rows.map { PickupTag(it.id, it.ts) }
    }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val labels = listOf("Focus", "Schedules", "Analytics")
    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            NavigationBar(containerColor = C.bg) {
                labels.forEachIndexed { i, l ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { TabIcon(i, tab == i) },
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
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(C.accent.copy(alpha = 0.055f), Color.Transparent))))
            Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { t ->
                when (t) { 0 -> FocusScreen(); 1 -> SchedulesScreen(); else -> StatsScreen() }
            }
        }
    }
}

@Composable
private fun TabIcon(index: Int, active: Boolean) {
    val tint by animateColorAsState(if (active) C.text else C.muted, tween(150), label = "tabTint")
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        when (index) {
            0 -> { // focus timer
                drawCircle(tint, s * 0.40f, Offset(s / 2, s / 2), style = Stroke(s * 0.07f))
                drawLine(tint, Offset(s / 2, s / 2), Offset(s / 2, s * 0.27f), s * 0.07f, cap = StrokeCap.Round)
                drawLine(tint, Offset(s / 2, s / 2), Offset(s * 0.68f, s * 0.57f), s * 0.07f, cap = StrokeCap.Round)
                drawLine(tint, Offset(s / 2, s * 0.13f), Offset(s / 2, s * 0.20f), s * 0.07f, cap = StrokeCap.Round)
            }
            1 -> { // calendar
                val left = s * 0.12f; val top = s * 0.18f; val w = s * 0.76f; val h = s * 0.70f
                drawRect(tint, Offset(left, top), Size(w, h), style = Stroke(s * 0.06f))
                drawLine(tint, Offset(left, top + s * 0.20f), Offset(left + w, top + s * 0.20f), s * 0.06f)
                drawLine(tint, Offset(left + s * 0.22f, top), Offset(left + s * 0.22f, top - s * 0.08f), s * 0.07f, cap = StrokeCap.Round)
                drawLine(tint, Offset(left + w - s * 0.22f, top), Offset(left + w - s * 0.22f, top - s * 0.08f), s * 0.07f, cap = StrokeCap.Round)
                drawCircle(tint, s * 0.035f, Offset(left + s * 0.28f, top + s * 0.40f))
                drawCircle(tint, s * 0.035f, Offset(left + s * 0.52f, top + s * 0.40f))
                drawCircle(tint, s * 0.035f, Offset(left + s * 0.28f, top + s * 0.60f))
            }
            else -> { // analytics bars
                val base = s * 0.82f
                drawLine(tint, Offset(s * 0.12f, s * 0.16f), Offset(s * 0.12f, base), s * 0.06f, cap = StrokeCap.Round)
                drawLine(tint, Offset(s * 0.12f, base), Offset(s * 0.88f, base), s * 0.06f, cap = StrokeCap.Round)
                drawRect(tint, Offset(s * 0.25f, s * 0.55f), Size(s * 0.13f, base - s * 0.55f))
                drawRect(tint, Offset(s * 0.48f, s * 0.36f), Size(s * 0.13f, base - s * 0.36f))
                drawRect(tint, Offset(s * 0.71f, s * 0.23f), Size(s * 0.13f, base - s * 0.23f))
            }
        }
    }
}

@Composable
private fun UtilityStrip(apps: Set<String>, active: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val labels by produceState(emptyMap<String, String>(), apps) {
        value = withContext(Dispatchers.IO) { apps.associateWith { labelOf(ctx, it) } }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface)
            .border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Utility apps", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(if (active != null) "Vibration paused" else "Resumes within 3s", color = C.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (apps.isEmpty()) {
            Text("None selected", color = C.muted, fontSize = 13.sp)
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                apps.sortedBy { (labels[it] ?: it).lowercase() }.forEach { pkg ->
                    val label = labels[pkg] ?: pkg
                    val on = active == pkg
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (on) C.accent else C.bg)
                            .border(1.dp, C.line, RoundedCornerShape(12.dp))
                            .clickable {
                                try {
                                    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                                        ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                } catch (_: Exception) {}
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) C.text else C.accent), contentAlignment = Alignment.Center) {
                            Text(label.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = C.bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = if (on) Color.Black else C.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.widthIn(max = 100.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Focus
private val PRESETS = listOf(15, 25, 45, 60, 90)

@Composable
fun FocusScreen() {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val phase by FocusState.phase.collectAsState()
    val rem by FocusState.remainingMs.collectAsState()
    val total by FocusState.totalMs.collectAsState()
    val zv by FocusState.z.collectAsState()
    val nr by FocusState.near.collectAsState()
    val ar by FocusState.armed.collectAsState()
    val scheduled by FocusState.scheduled.collectAsState()
    val failed by FocusState.failed.collectAsState()
    val utilityApps by FocusState.utilityApps.collectAsState()
    val activeUtility by FocusState.activeUtility.collectAsState()
    val pendingTags by FocusState.pendingTags.collectAsState()
    val customTemplates by dao.templates().collectAsState(emptyList())
    var mins by rememberSaveable { mutableIntStateOf(25) }
    var customDialog by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf("") }
    var showTemplates by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        FocusService.start(ctx, mins)
    }

    fun startWithTemplate(t: TemplateEntity) {
        mins = t.minutes
        if (t.apps.isNotBlank() || t.utilities.isNotBlank()) {
            val u = t.utilities.split(",").filter { it.isNotBlank() }.take(MAX_UTILITY_APPS).toSet()
            Prefs.setUtility(ctx, u)
            FocusState.utilityApps.value = u
        }
        launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE))
    }

    val active = phase != Phase.IDLE
    if (showTemplates) {
        BackHandler { showTemplates = false }
        TemplatesScreen(mins, utilityApps, { t ->
            showTemplates = false
            startWithTemplate(t)
        }, { showTemplates = false })
        return
    }
    if (picking == "utility") {
        BackHandler { picking = "" }
        AppPicker(utilityApps, { set ->
            Prefs.setUtility(ctx, set)
            FocusState.utilityApps.value = set
        }, MAX_UTILITY_APPS, "Utility apps (max $MAX_UTILITY_APPS)") { picking = "" }
        return
    }
    if (!active && pendingTags.isNotEmpty()) {
        TaggingScreen(pendingTags) { FocusState.clearPendingTags() }
        return
    }
    var liftedWhileConfirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming, phase) {
        if (!confirming) liftedWhileConfirming = false
        else if (phase == Phase.VIOLATION || phase == Phase.ARMING) liftedWhileConfirming = true
        else if (phase == Phase.FOCUSED && liftedWhileConfirming) confirming = false
    }
    if (active && confirming && phase != Phase.BREAK) {
        RethinkScreen({ confirming = false }) { confirming = false; FocusService.stop(ctx) }
        return
    }

    val secs = ((if (active) rem else mins * 60_000L) / 1000).coerceAtLeast(0)
    val progress = if (active && total > 0) rem.toFloat() / total else 1f
    val animatedProgress by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "progress")
    val (baseStatus, baseColor) = when (phase) {
        Phase.IDLE -> "Ready" to C.muted
        Phase.ARMING -> "Place phone face-down" to C.text
        Phase.FOCUSED -> "Stay focused" to C.accent
        Phase.VIOLATION -> "Put it back face-down" to C.bad
        Phase.PAUSED_CALL -> "Paused for call" to C.muted
        Phase.UTILITY -> "Utility app in use" to C.muted
        Phase.BREAK -> "Break · vibration paused" to C.accent
    }
    val status = if (failed) "Failed session · not counted" else baseStatus
    val color = if (failed) C.bad else baseColor
    val animatedColor by animateColorAsState(color, tween(200), label = "status")
    val originLabel = if (scheduled) "scheduled" else "manual"

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            val ringSize = if (active) 215.dp else 185.dp
            if (active) Box(Modifier.size(ringSize + 22.dp).background(Brush.radialGradient(listOf(animatedColor.copy(alpha = 0.10f), Color.Transparent))))
            Canvas(Modifier.size(ringSize)) {
                val sw = 6.dp.toPx()
                val tl = Offset(sw / 2, sw / 2); val sz = Size(this.size.width - sw, this.size.height - sw)
                drawArc(C.line, 0f, 360f, false, tl, sz, style = Stroke(sw))
                if (active) {
                    drawArc(animatedColor.copy(alpha = 0.16f), -90f, 360f * animatedProgress, false, tl, sz, style = Stroke(sw * 2.6f, cap = StrokeCap.Round))
                    drawArc(animatedColor, -90f, 360f * animatedProgress, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                } else {
                    drawArc(C.line, -90f, 360f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            Text("%02d:%02d".format(secs / 60, secs % 60), fontFamily = Mono, fontSize = 50.sp, color = C.text)
        }
        Spacer(Modifier.height(16.dp))
        Text(status, color = animatedColor, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (active && phase != Phase.BREAK) Text("$originLabel · z=${zv?.let { "%.1f".format(it) } ?: "no data"} · prox=${when (nr) { true -> "near"; false -> "far"; null -> "none" }} · monitoring=$ar",
            color = C.muted, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))
        AnimatedVisibility(visible = active && phase != Phase.BREAK, enter = fadeIn(), exit = fadeOut()) {
            UtilityStrip(utilityApps, activeUtility)
        }
        Spacer(Modifier.weight(1f))
        when {
            phase == Phase.BREAK -> {
                Text("Session complete · 5-minute break", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                Pill("Skip break", false, { FocusService.skipBreak(ctx) }, Modifier.fillMaxWidth())
            }
            !active -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (PRESETS + 0).forEach { m ->
                        val custom = m == 0
                        val sel = if (custom) mins !in PRESETS else m == mins
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .background(if (sel) C.text else C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp))
                                .clickable { if (custom) customDialog = true else mins = m }.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) { Text(if (custom) (if (sel) "$mins · Custom" else "Custom") else "$m", color = if (sel) Color.Black else C.text, fontWeight = FontWeight.Medium) }
                    }
                }
                Text("minutes", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                Text("Quick start", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (BUILT_IN_TEMPLATES + customTemplates).forEach { t ->
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(C.surface)
                                .border(1.dp, C.line, RoundedCornerShape(14.dp))
                                .clickable { startWithTemplate(t) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        ) { Text("${t.name} · ${t.minutes}m", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(C.surface)
                            .border(1.dp, C.accent, RoundedCornerShape(14.dp))
                            .clickable { showTemplates = true }.padding(horizontal = 14.dp, vertical = 9.dp),
                    ) { Text("Edit", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Test vibration", color = C.muted, fontSize = 14.sp, modifier = Modifier.clickable { testVibrate(ctx) })
                    Text("Utility apps · ${utilityApps.size}/$MAX_UTILITY_APPS", color = C.accent, fontSize = 14.sp, modifier = Modifier.clickable { picking = "utility" })
                }
                Pill("Start focus", true, {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE))
                }, Modifier.fillMaxWidth())
            }
            else -> Pill("End session", false, {
                if (total - rem < 60_000L) FocusService.stop(ctx) else confirming = true
            }, Modifier.fillMaxWidth())
        }
    }
    if (customDialog) {
        var pick by remember { mutableIntStateOf(mins) }
        AlertDialog(
            onDismissRequest = { customDialog = false }, containerColor = C.surface,
            title = { Text("Custom duration (hours · minutes)") },
            text = {
                DurationWheel(mins) { pick = it }
            },
            confirmButton = {
                TextButton(onClick = { mins = pick.coerceIn(1, 720); customDialog = false }) { Text("Set", color = C.accent) }
            },
            dismissButton = { TextButton(onClick = { customDialog = false }) { Text("Cancel", color = C.muted) } },
        )
    }
}

@Composable
fun RethinkScreen(onKeep: () -> Unit, onEnd: () -> Unit) {
    var left by remember { mutableIntStateOf(15) }
    LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Take a breath", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = C.text)
        Spacer(Modifier.height(12.dp))
        Text("You started this for a reason. Is ending it now really worth it?", color = C.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Text(if (left > 0) "$left" else "\u2713", fontFamily = Mono, fontSize = 56.sp, color = C.text)
        Spacer(Modifier.height(32.dp))
        Pill("Keep focusing", true, onKeep, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (left > 0) Text("You can end the session in ${left}s", color = C.muted, fontSize = 14.sp)
        else Pill("End session", false, onEnd, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- Schedules
@Composable
fun SchedulesScreen() {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val list by dao.schedules().collectAsState(emptyList())
    val runningId by FocusState.runningScheduleId.collectAsState()
    val scope = rememberCoroutineScope()
    var exact by remember { mutableStateOf(Scheduler.canExact(ctx)) }
    var usage by remember { mutableStateOf(ctx.hasUsageAccess()) }
    var notif by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    var fsi by remember { mutableStateOf(ctx.canFsi()) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it }
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exact = Scheduler.canExact(ctx)
        usage = ctx.hasUsageAccess()
        notif = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fsi = ctx.canFsi()
        scope.launch(Dispatchers.IO) { Scheduler.rescheduleAll(ctx) }
    }
    val e = editing
    if (e != null) {
        if (e.id != 0 && runningId == e.id) {
            ScheduleLockedScreen(e) { editing = null }
            return
        }
        ScheduleEditor(e, list, { editing = null }, onDelete = {
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
        if (!usage) item {
            Tile {
                Text("Allow usage access so FlipFocus can see which app is open: utility apps pause vibration, and any other app you open during a session triggers a buzz.", color = C.muted, fontSize = 14.sp)
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open settings", color = C.accent) }
            }
        }
        if (!notif) item {
            Tile {
                Text("Allow notifications so the running-session notification shows what each vibration is for.", color = C.muted, fontSize = 14.sp)
                TextButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications", color = C.accent) }
            }
        }
        if (!fsi) item {
            Tile {
                Text("Allow full-screen notifications so a schedule that starts while the phone is locked opens the Focus screen over the lock screen.", color = C.muted, fontSize = 14.sp)
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Allow", color = C.accent) }
            }
        }
        if (!exact) item {
            Tile {
                Text("Allow exact alarms for the most reliable schedule starts on Android.", color = C.muted, fontSize = 14.sp)
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Allow exact alarms", color = C.accent) }
            }
        }
        item { BatteryCard() }
        items(list, key = { it.id }) { s ->
            val locked = runningId == s.id
            val conflict = Scheduler.conflict(s, list)
            Tile(Modifier.clickable(enabled = !locked) { editing = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.text)
                        Text("${hhmm(s.startMin)} – ${hhmm(s.endMin)}", fontFamily = Mono, color = C.muted, fontSize = 14.sp)
                    }
                    Switch(
                        enabled = !locked,
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
                }
                if (conflict != null) Text("Overlaps ${conflict.name}", color = C.bad, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (locked) Text("Running · editing locked", color = C.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Pill("New schedule", false, {
                val nh = (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) % 24
                editing = ScheduleEntity(name = "", days = 0b1111111, startMin = nh * 60, endMin = ((nh + 1) % 24) * 60)
            }, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditor(init: ScheduleEntity, all: List<ScheduleEntity>, onClose: () -> Unit, onDelete: () -> Unit, onSave: (ScheduleEntity) -> Unit) {
    var name by remember { mutableStateOf(init.name) }
    var days by remember { mutableIntStateOf(init.days) }
    var conflictName by remember { mutableStateOf<String?>(null) }
    var startMin by remember { mutableIntStateOf(init.startMin) }
    var endMin by remember { mutableIntStateOf(init.endMin) }
    LaunchedEffect(name, days, startMin, endMin) { conflictName = null }
    BackHandler { onClose() }
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
        Tile {
            Text("Start · ${hhmm(startMin)}", color = C.muted)
            TimeWheel(init.startMin) { startMin = it }
        }
        Tile {
            Text("End · ${hhmm(endMin)}", color = C.muted)
            TimeWheel(init.endMin) { endMin = it }
        }
        Text("12-hour clock. Overlapping enabled schedules are blocked. Monitoring starts even when the app is closed or locked.", color = C.muted, fontSize = 13.sp)
        if (conflictName != null) Text("Overlaps “$conflictName”. Adjust the days or times.", color = C.bad, fontSize = 13.sp)
        Pill("Save", true, {
            val candidate = init.copy(
                name = name.ifBlank { "Focus" }, days = days,
                startMin = startMin, endMin = endMin,
            )
            val c = Scheduler.conflict(candidate, all)
            if (c == null) onSave(candidate) else conflictName = c.name
        }, Modifier.fillMaxWidth())
        if (init.id != 0) Pill("Delete", false, onDelete, Modifier.fillMaxWidth())
    }
}

@Composable
fun AppPicker(selected: Set<String>, onChange: (Set<String>) -> Unit, max: Int = Int.MAX_VALUE, title: String = "Select apps", onDone: () -> Unit) {
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
    var q by remember { mutableStateOf("") }
    val shown = if (q.isBlank()) apps else apps.filter { it.second.contains(q.trim(), true) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Title(title) }
            item { OutlinedTextField(q, { q = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            items(shown, key = { it.first }) { (pkg, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = C.text, modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Switch(
                        checked = pkg in selected,
                        onCheckedChange = { if (!it) onChange(selected - pkg) else if (selected.size < max) onChange(selected + pkg) },
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
    val opens by dao.count("APP_OPEN").collectAsState(0)
    val early by dao.count("EARLY_END").collectAsState(0)
    val top by dao.topOpened().collectAsState(emptyList())
    val tagCounts by dao.tagCounts().collectAsState(emptyList())
    val pct = sessions.adherencePct() / 100f
    val animatedPct by animateFloatAsState(pct.coerceIn(0f, 1f), tween(300), label = "adherence")
    val totalMs = sessions.sumOf { it.effectiveMs() }
    val plannedMin = sessions.filter { !it.failed }.sumOf { it.plannedMs } / 60_000
    val failedCount = sessions.count { it.failed }
    val streak = sessions.currentStreak()
    val goodDays = sessions.last7GoodDays()
    val heatRows = sessions.heatmapRows()
    val week = remember(sessions) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        (6 downTo 0).map { i ->
            val d = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
            val s0 = d.timeInMillis; val e0 = s0 + 86_400_000L
            val m = sessions.filter { it.startMs in s0 until e0 }.sumOf { it.effectiveMs() } / 60_000
            "SMTWTFS"[d.get(Calendar.DAY_OF_WEEK) - 1].toString() to m
        }
    }
    val max = (week.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Analytics") }
        item {
            Tile {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(170.dp)) {
                        val sw = 10.dp.toPx()
                        val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
                        drawArc(C.line, 0f, 360f, false, tl, sz, style = Stroke(sw))
                        drawArc(C.accent.copy(alpha = 0.14f), -90f, 360f * animatedPct, false, tl, sz, style = Stroke(sw * 2.2f, cap = StrokeCap.Round))
                        drawArc(C.accent, -90f, 360f * animatedPct, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(pct * 100).toInt()}%", fontFamily = Mono, fontSize = 40.sp, color = C.text)
                        Text("Adherence", color = C.muted, fontSize = 13.sp)
                    }
                }
            }
        }
        item { StreakCard(streak, goodDays) }
        item {
            Tile {
                Text("Total time", color = C.muted, fontSize = 13.sp)
                Text(dur(totalMs), fontFamily = Mono, fontSize = 32.sp, color = C.text, modifier = Modifier.padding(top = 6.dp))
                Text("${plannedMin / 60}h ${plannedMin % 60}m planned · failed sessions excluded", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Sessions", "${sessions.size}", Modifier.weight(1f))
                Stat("Failed", "$failedCount", Modifier.weight(1f))
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
                Stat("App opens", "$opens", Modifier.weight(1f))
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
        item { HeatmapCard(heatRows) }
        item { InterruptionTagsCard(tagCounts) }
        item { UsageCard() }
        item { OpenedAppsCard(top) }
        item { Text("Recent sessions", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        items(sessions.take(10), key = { it.id }) { s ->
            val f = java.text.SimpleDateFormat("EEE d MMM, hh:mm a", java.util.Locale.getDefault())
            val origin = if (s.scheduled) "scheduled" else "manual"
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.format(java.util.Date(s.startMs)), color = C.text)
                    Text("${s.plannedMs / 60_000} min · $origin · ${s.pickups} pickups", color = C.muted, fontSize = 13.sp)
                }
                Text(
                    if (s.failed) "FAILED" else "${(s.adherence() * 100).toInt()}%",
                    fontFamily = Mono,
                    color = if (s.failed || s.adherence() < 0.8f) C.bad else C.accent,
                )
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) = Tile(modifier) {
    Text(value, fontFamily = Mono, fontSize = 24.sp, color = C.text)
    Text(label, color = C.muted, fontSize = 13.sp)
}

private fun labelOf(ctx: android.content.Context, pkg: String) = try {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
} catch (e: Exception) { pkg }

private fun dur(ms: Long) = "${ms / 3_600_000}h ${ms / 60_000 % 60}m"

private fun isBatteryExempt(ctx: android.content.Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private fun requestBatteryExemption(ctx: android.content.Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
    }
}

@Composable
fun BatteryCard() {
    val ctx = LocalContext.current
    var exempt by remember { mutableStateOf(isBatteryExempt(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = isBatteryExempt(ctx) }
    if (exempt) return
    Tile {
        Text("Keep locked sessions alive", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Allow FlipFocus to ignore battery optimization so alarms, sensors and vibration are less likely to be paused while the screen is off.",
            color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Row {
            TextButton(onClick = { requestBatteryExemption(ctx) }) { Text("Allow", color = C.accent) }
            TextButton(onClick = {
                try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }) { Text("Battery settings", color = C.muted) }
        }
    }
}

@Composable
fun UsageCard() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.hasUsageAccess()) }
    var days by rememberSaveable { mutableIntStateOf(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = ctx.hasUsageAccess() }
    val rows by produceState(emptyList<Pair<String, Long>>(), granted, days) {
        value = if (!granted) emptyList() else withContext(Dispatchers.IO) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -(days - 1))
            }
            val pm = ctx.packageManager
            ctx.getSystemService(UsageStatsManager::class.java)
                .queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis()).values
                .filter { it.totalTimeInForeground > 60_000 && it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedByDescending { it.totalTimeInForeground }.take(8)
                .map { labelOf(ctx, it.packageName) to it.totalTimeInForeground }
        }
    }
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("App usage", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(1 to "Today", 7 to "7 days").forEach { (d, l) ->
                Text(l, color = if (days == d) C.text else C.muted, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 14.dp).clickable { days = d })
            }
        }
        if (!granted) {
            Text("Allow usage access for app usage and more reliable utility detection while locked.", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access", color = C.accent) }
        } else if (rows.isEmpty()) {
            Text("No usage yet.", color = C.muted, modifier = Modifier.padding(top = 8.dp))
        } else {
            val max = rows.first().second.toFloat()
            rows.forEach { (label, ms) ->
                Column(Modifier.padding(top = 12.dp)) {
                    Row { Text(label, color = C.text, modifier = Modifier.weight(1f), maxLines = 1); Text(dur(ms), fontFamily = Mono, color = C.muted, fontSize = 13.sp) }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(ms / max).height(4.dp).clip(CircleShape).background(C.accent))
                }
            }
        }
    }
}

@Composable
fun OpenedAppsCard(top: List<PkgCount>) {
    val ctx = LocalContext.current
    if (top.isEmpty()) return
    Tile {
        Text("Most opened apps", color = C.muted, fontSize = 13.sp)
        top.forEach {
            Row(Modifier.padding(top = 10.dp)) {
                Text(labelOf(ctx, it.pkg), color = C.text, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${it.n}", fontFamily = Mono, color = C.muted)
            }
        }
    }
}

@Suppress("DEPRECATION")
fun testVibrate(ctx: android.content.Context) {
    val v = ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
    val e = android.os.VibrationEffect.createOneShot(600, 255)
    val aa = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
    try { v.vibrate(e, aa) } catch (t: Throwable) { v.vibrate(e) }
    android.widget.Toast.makeText(ctx, "hasVibrator=${v.hasVibrator()}", android.widget.Toast.LENGTH_SHORT).show()
}
