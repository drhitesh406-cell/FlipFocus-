package com.flipfocus.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ROW = 40.dp
private const val LOOPS = 400

/** Slot-machine style snapping wheel. [loop] makes it endless. Reports the settled index. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Wheel(items: List<String>, index: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, loop: Boolean = false) {
    val n = items.size
    val count = if (loop) n * LOOPS else n
    val start = if (loop) n * (LOOPS / 2) + index else index
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val centered by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0 }
    }
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(state) {
        snapshotFlow { if (state.isScrollInProgress) -1 else state.firstVisibleItemIndex % n }
            .collect { if (it >= 0) latest(it) }
    }
    Box(modifier.height(ROW * 5), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(ROW).clip(RoundedCornerShape(10.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(10.dp)))
        LazyColumn(
            state = state, flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = ROW * 2), modifier = Modifier.fillMaxWidth(),
        ) {
            items(count) { i ->
                val sel = i == centered
                Box(Modifier.fillMaxWidth().height(ROW), contentAlignment = Alignment.Center) {
                    Text(
                        items[i % n], textAlign = TextAlign.Center, fontFamily = Mono,
                        fontSize = if (sel) 22.sp else 17.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) C.text else C.muted,
                    )
                }
            }
        }
    }
}

private val HOURS12 = (1..12).map { it.toString() }
private val MINS60 = (0..59).map { "%02d".format(it) }

/** 12-hour time wheels (hour · minute · AM/PM) over minutes-from-midnight. */
@Composable
fun TimeWheel(minutes: Int, onChange: (Int) -> Unit) {
    var h by remember { mutableIntStateOf((minutes / 60).let { if (it % 12 == 0) 12 else it % 12 }) }
    var m by remember { mutableIntStateOf(minutes % 60) }
    var pm by remember { mutableStateOf(minutes / 60 >= 12) }
    fun push() = onChange(((h % 12) + if (pm) 12 else 0) * 60 + m)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Wheel(HOURS12, h - 1, { h = it + 1; push() }, Modifier.weight(1f), loop = true)
        Wheel(MINS60, m, { m = it; push() }, Modifier.weight(1f), loop = true)
        Wheel(listOf("AM", "PM"), if (pm) 1 else 0, { pm = it == 1; push() }, Modifier.weight(1f))
    }
}

private val HRS = (0..12).map { "$it h" }

/** Duration wheels (hours · minutes). Returns total minutes, clamped to 1..720. */
@Composable
fun DurationWheel(minutes: Int, onChange: (Int) -> Unit) {
    var h by remember { mutableIntStateOf(minutes / 60) }
    var m by remember { mutableIntStateOf(minutes % 60) }
    fun push() = onChange((h * 60 + m).coerceIn(1, 720))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Wheel(HRS, h, { h = it; push() }, Modifier.weight(1f))
        Wheel(MINS60.map { "$it m" }, m, { m = it; push() }, Modifier.weight(1f), loop = true)
    }
}
