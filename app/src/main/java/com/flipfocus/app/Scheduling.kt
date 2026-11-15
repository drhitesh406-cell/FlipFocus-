package com.flipfocus.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object Scheduler {
    /** Next start time (epoch ms) strictly after [from], or -1 if no day is selected. */
    fun nextStart(s: ScheduleEntity, from: Calendar = Calendar.getInstance()): Long {
        for (i in 0..7) {
            val c = from.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, i)
            c.set(Calendar.HOUR_OF_DAY, s.startMin / 60); c.set(Calendar.MINUTE, s.startMin % 60)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val bit = 1 shl ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) // Monday = bit0
            if (s.days and bit != 0 && c.timeInMillis > from.timeInMillis) return c.timeInMillis
        }
        return -1
    }

    fun durationMin(s: ScheduleEntity): Int = (s.endMin - s.startMin).let { if (it <= 0) it + 1440 else it }

    private fun pi(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, id, Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(ctx: Context, s: ScheduleEntity) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, s.id)
        am.cancel(p)
        if (!s.enabled || s.days == 0) return
        val t = nextStart(s)
        if (t < 0) return
        // Fallback: if exact alarms were not granted (default on Android 14+), use an inexact alarm.
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
    }

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id))

    suspend fun rescheduleAll(ctx: Context) = Db.get(ctx).dao().schedulesNow().forEach { schedule(ctx, it) }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = Db.get(ctx).dao().schedule(id)
                if (s != null && s.enabled) {
                    if (FocusState.phase.value == Phase.IDLE) FocusService.start(ctx, Scheduler.durationMin(s), s.apps.split(",").filter { it.isNotBlank() }.toSet())
                    Scheduler.schedule(ctx, s) // queue the next occurrence
                }
            } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Scheduler.rescheduleAll(ctx) } finally { pending.finish() }
        }
    }
}

/** Sends the user home when a blocked app opens during an active (non-call) session. */
class BlockerService : AccessibilityService() {
    private var lastLog = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val ph = FocusState.phase.value
        if (ph == Phase.IDLE || ph == Phase.PAUSED_CALL) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in FocusState.blockedApps) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastLog > 2_000) {
            lastLog = now
            val sid = FocusState.sessionId
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                Db.get(app).dao().insertEvent(EventEntity(sessionId = sid, type = "BLOCK", ts = now))
            }
        }
    }

    override fun onInterrupt() {}
}
