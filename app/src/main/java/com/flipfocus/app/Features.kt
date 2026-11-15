package com.flipfocus.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private fun fmt12(m: Int): String {
    val h = m / 60
    val h12 = h % 12
    return "%d:%02d %s".format(if (h12 == 0) 12 else h12, m % 60, if (h < 12) "AM" else "PM")
}

@Composable
fun TemplatesScreen(
    minutes: Int,
    blocked: Set<String>,
    utilities: Set<String>,
    onStart: (TemplateEntity) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val custom by dao.templates().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    BackHandler { onClose() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Title("Templates")
        Text(
            "Quick-start a session from Focus. Built-in presets keep your current blocked and utility apps; custom templates restore the saved setup.",
            color = C.muted, fontSize = 14.sp,
        )
        Tile {
            Text("Save current setup", color = C.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text("$minutes min · ${blocked.size} blocked · ${utilities.size} utility apps", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                name, { name = it.take(30) }, label = { Text("Template name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Pill("Save template", true, {
                val n = name.trim()
                if (n.isNotEmpty()) scope.launch {
                    dao.upsertTemplate(
                        TemplateEntity(
                            name = n, minutes = minutes,
                            apps = blocked.joinToString(","), utilities = utilities.joinToString(","),
                        ),
                    )
                    name = ""
                }
            }, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Text("Built-in", color = C.muted, fontSize = 13.sp)
        BUILT_IN_TEMPLATES.forEach { TemplateRow(it, onStart) }
        Text("Custom", color = C.muted, fontSize = 13.sp)
        if (custom.isEmpty()) Text("No custom templates yet.", color = C.muted, fontSize = 13.sp)
        else custom.forEach { t -> TemplateRow(t, onStart) { scope.launch { dao.deleteTemplate(t) } } }
        Pill("Back", false, onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun TemplateRow(t: TemplateEntity, onStart: (TemplateEntity) -> Unit, onDelete: (() -> Unit)? = null) {
    val blocked = t.apps.split(",").count { it.isNotBlank() }
    val utils = t.utilities.split(",").count { it.isNotBlank() }
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("${t.minutes} min · $blocked blocked · $utils utility", color = C.muted, fontSize = 12.sp)
            }
            TextButton(onClick = { onStart(t) }) { Text("Start", color = C.accent) }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = C.bad) }
        }
    }
}

@Composable
fun TaggingScreen(pickups: List<PickupTag>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    var index by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    BackHandler {
        scope.launch {
            dao.skipUntaggedPickups()
            FocusState.clearPendingTags()
            onDone()
        }
    }
    if (pickups.isEmpty() || index >= pickups.size) {
        LaunchedEffect(Unit) { FocusState.clearPendingTags(); onDone() }
        return
    }
    val item = pickups[index]
    val f = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Title("Tag interruptions")
        Text("One quick reason per pickup helps Analytics show patterns.", color = C.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Crossfade(targetState = index, animationSpec = tween(180), label = "tag") { i ->
            Tile {
                Text("Pickup ${i + 1} of ${pickups.size}", color = C.muted, fontSize = 13.sp)
                Text(f.format(java.util.Date(item.ts)), fontFamily = Mono, fontSize = 28.sp, color = C.text, modifier = Modifier.padding(top = 6.dp))
                Text("Why did you pick up the phone?", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TAG_REASONS.forEach { reason ->
                Pill(reason, false, {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            dao.tagEvent(item.eventId, reason)
                            saving = false
                            if (index + 1 >= pickups.size) {
                                FocusState.clearPendingTags(); onDone()
                            } else index++
                        }
                    }
                })
            }
        }
        TextButton(onClick = {
            scope.launch {
                dao.skipUntaggedPickups()
                FocusState.clearPendingTags()
                onDone()
            }
        }) { Text("Skip all", color = C.muted) }
    }
}

@Composable
fun ScheduleLockedScreen(s: ScheduleEntity, onClose: () -> Unit) {
    BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(C.accent.copy(alpha = 0.08f), Color.Transparent)),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Title("Schedule running")
            Text(s.name, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("${fmt12(s.startMin)} – ${fmt12(s.endMin)}", fontFamily = Mono, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
            Text("Editing is locked until this session and its break finish.", color = C.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Pill("Back", false, onClose, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun StreakCard(streak: Int, goodDays: List<Boolean>) {
    val n by animateIntAsState(streak, tween(350), label = "streak")
    Tile {
        Box(
            Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(
                Brush.horizontalGradient(listOf(C.accent.copy(alpha = 0.15f), C.accent, C.accent.copy(alpha = 0.15f))),
            ),
        )
        Spacer(Modifier.height(10.dp))
        Text("Current streak", color = C.muted, fontSize = 13.sp)
        Text("$n days", fontFamily = Mono, fontSize = 32.sp, color = C.text, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            goodDays.forEach { good ->
                val a by animateFloatAsState(if (good) 1f else 0.18f, tween(250), label = "streakDot")
                Box(Modifier.size(10.dp).clip(CircleShape).background(C.accent.copy(alpha = a)))
            }
        }
    }
}

@Composable
fun HeatmapCard(rows: List<HeatRow>) {
    val max = rows.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Tile {
        Text("Best focus times", color = C.muted, fontSize = 13.sp)
        Text("Successful minutes by daypart", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(28.dp))
            DAYPARTS.forEach { p -> Text(p.short, color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.day, color = C.muted, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                row.values.forEach { v -> HeatCell(v, max) }
            }
        }
    }
}

@Composable
private fun RowScope.HeatCell(value: Long, max: Long) {
    val target = if (value > 0) 0.25f + 0.75f * (value.toFloat() / max) else 0.08f
    val a by animateFloatAsState(target, tween(250), label = "heatCell")
    Box(
        Modifier.weight(1f).height(18.dp).padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (value > 0) C.accent.copy(alpha = a) else C.line),
    )
}

@Composable
fun InterruptionTagsCard(counts: List<TagCount>) {
    if (counts.isEmpty()) return
    val max = counts.maxOf { it.n }.coerceAtLeast(1)
    Tile {
        Text("Interruption reasons", color = C.muted, fontSize = 13.sp)
        counts.forEach { c ->
            val ratio by animateFloatAsState(c.n.toFloat() / max, tween(300), label = "tagBar")
            Column(Modifier.padding(top = 10.dp)) {
                Row {
                    Text(c.tag, color = C.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Text("${c.n}", fontFamily = Mono, color = C.muted, fontSize = 13.sp)
                }
                Box(
                    Modifier.padding(top = 5.dp).fillMaxWidth(ratio).height(4.dp)
                        .clip(CircleShape).background(C.accent.copy(alpha = 0.85f)),
                )
            }
        }
    }
}
