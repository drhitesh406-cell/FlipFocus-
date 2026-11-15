package com.flipfocus.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    /** Two-week minute segments; the second week catches schedules that cross Sunday midnight. */
    private fun segments(s: ScheduleEntity): List<Pair<Long, Long>> {
        val dur = durationMin(s).toLong()
        val out = mutableListOf<Pair<Long, Long>>()
        for (day in 0..6) {
            if ((s.days and (1 shl day)) == 0) continue
            for (week in 0..1) {
                val start = ((week * 7 + day) * 1440 + s.startMin).toLong()
                out += start to (start + dur)
            }
        }
        return out
    }

    /** First enabled schedule that overlaps [s] on a shared day, or null. */
    fun conflict(s: ScheduleEntity, all: List<ScheduleEntity>): ScheduleEntity? {
        if (!s.enabled || s.days == 0) return null
        val mine = segments(s)
        return all.firstOrNull { o ->
            o.id != s.id && o.enabled && o.days != 0 && segments(o).any { a -> mine.any { b -> a.first < b.second && b.first < a.second } }
        }
    }

    private fun serviceBase(ctx: Context, id: Int) = Intent(ctx, FocusService::class.java)
        .setAction(FocusService.ACTION_SCHEDULED_START)
        .putExtra(FocusService.EXTRA_SCHEDULE_ID, id)

    private fun serviceIntent(ctx: Context, s: ScheduleEntity) = serviceBase(ctx, s.id)
        .putExtra(FocusService.EXTRA_MIN, durationMin(s))
        .putExtra(FocusService.EXTRA_SCHEDULED, true)

    /** Alarm-clock PendingIntents may start a foreground service even when the app is backgrounded. */
    private fun pi(ctx: Context, s: ScheduleEntity) = PendingIntent.getForegroundService(
        ctx, s.id, serviceIntent(ctx, s),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelPi(ctx: Context, id: Int) = PendingIntent.getForegroundService(
        ctx, id, serviceBase(ctx, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Legacy broadcast alarms from an older app version; canceled whenever a schedule is refreshed. */
    private fun legacyPi(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, id, Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showPi(ctx: Context) = PendingIntent.getActivity(
        ctx, 10_001, Intent(ctx, MainActivity::class.java).putExtra("tab", 1),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(ctx: Context, s: ScheduleEntity) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, s)
        am.cancel(p)
        am.cancel(legacyPi(ctx, s.id))
        if (!s.enabled || s.days == 0) return
        val t = nextStart(s)
        if (t < 0) return
        // setAlarmClock is exact, Doze-exempt, and allowed to start the FGS while locked/closed.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(t, showPi(ctx)), p)
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(cancelPi(ctx, id))
        am.cancel(legacyPi(ctx, id))
    }

    suspend fun rescheduleAll(ctx: Context) = Db.get(ctx).dao().schedulesNow().forEach { schedule(ctx, it) }
}

/** Handles legacy alarms still queued from before the direct foreground-service trigger. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = Db.get(ctx).dao().schedule(id)
                if (s != null && s.enabled) {
                    if (FocusState.phase.value == Phase.IDLE) {
                        FocusService.start(ctx, Scheduler.durationMin(s), scheduled = true, scheduleId = s.id)
                    }
                    Scheduler.schedule(ctx, s)
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
