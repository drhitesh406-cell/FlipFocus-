package com.flipfocus.app

import android.Manifest
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.*
import android.media.AudioAttributes
import android.os.*
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.*

/**
 * Foreground service that watches orientation and foreground utility apps. Monitoring starts
 * immediately: no initial face-down flip is needed. Alarm-clock schedules can start this service
 * even when the app UI is closed and the phone is locked.
 */
class FocusService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.flipfocus.app.STOP"
        const val ACTION_SKIP_BREAK = "com.flipfocus.app.SKIP_BREAK"
        const val ACTION_SCHEDULED_START = "com.flipfocus.app.SCHEDULED_START"
        const val EXTRA_MIN = "min"
        const val EXTRA_APPS = "apps"
        const val EXTRA_SCHEDULED = "scheduled"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        private const val CH = "focus"
        private const val NID = 1
        private const val GRACE_MS = 3_000L
        private const val FAIL_MS = 5_000L
        private const val UTILITY_RESUME_MS = 500L
        private const val RECOVER_MS = 1_500L
        private const val SENSOR_WARMUP_MS = 1_000L
        private const val BREAK_MS = 300_000L
        private const val DOWN_Z = -6.5f

        fun start(
            ctx: Context,
            minutes: Int,
            apps: Set<String> = emptySet(),
            scheduled: Boolean = false,
            scheduleId: Int = 0,
        ) {
            val i = Intent(ctx, FocusService::class.java)
                .putExtra(EXTRA_MIN, minutes)
                .putStringArrayListExtra(EXTRA_APPS, ArrayList(apps))
                .putExtra(EXTRA_SCHEDULED, scheduled)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            try { ctx.startForegroundService(i) } catch (_: Exception) { /* background start refused */ }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FocusService::class.java).setAction(ACTION_STOP))
        }

        fun skipBreak(ctx: Context) {
            ctx.startService(Intent(ctx, FocusService::class.java).setAction(ACTION_SKIP_BREAK))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var sm: SensorManager
    private lateinit var vib: Vibrator
    private lateinit var tm: TelephonyManager
    private lateinit var power: PowerManager
    private var usm: UsageStatsManager? = null
    private var telReg = false
    private var sensorsRegistered = false
    private var finished = false
    private var stopping = false
    private var sessionCompleted = false
    private var breakActive = false
    private var breakSkip = false
    private var wl: PowerManager.WakeLock? = null

    private var zf = 0f
    private var near = true
    private var hasZSample = false
    private var paused = false
    private var suspended = false
    private var violating = false
    private var downSince = 0L
    private var upSince = 0L
    private var failSince = 0L
    private var lastUsagePoll = 0L

    private var sessionId = 0L
    private var startWall = 0L
    private var planned = 0L
    private var pickups = 0
    private var calls = 0
    private var faceUp = 0L
    private var scheduled = false
    private var scheduleId = 0
    private var failed = false
    private var failureLogged = false

    private val callCb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                if (paused) {
                    paused = false
                    suspended = true
                    violating = false
                    downSince = 0L
                    failSince = 0L
                    upSince = SystemClock.elapsedRealtime() - (GRACE_MS - UTILITY_RESUME_MS)
                }
            } else if (!paused) {
                paused = true
                violating = false
                stopVib()
                calls++
                log("CALL_BYPASS")
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            ACTION_STOP -> {
                stopping = true
                if (job != null) finish(sessionCompleted) else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SKIP_BREAK -> {
                if (breakActive) breakSkip = true
                return START_NOT_STICKY
            }
        }
        if (job != null) return START_NOT_STICKY

        startForeground(NID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        FocusState.clearPendingTags()
        FocusState.reset()
        scheduled = (i?.getBooleanExtra(EXTRA_SCHEDULED, false) ?: false) || i?.action == ACTION_SCHEDULED_START
        scheduleId = i?.getIntExtra(EXTRA_SCHEDULE_ID, 0) ?: 0
        sessionCompleted = false
        breakActive = false
        breakSkip = false
        FocusState.scheduled.value = scheduled
        FocusState.runningScheduleId.value = if (scheduled) scheduleId else 0
        FocusState.failed.value = false
        FocusState.armed.value = true
        FocusState.phase.value = Phase.FOCUSED
        FocusState.blockedApps = i?.getStringArrayListExtra(EXTRA_APPS)?.toSet() ?: emptySet()
        FocusState.utilityApps.value = Prefs.utility(this)
        FocusState.setUtility(null)

        val mins = (i?.getIntExtra(EXTRA_MIN, 25) ?: 25).coerceAtLeast(1)
        job = scope.launch { runSession(mins * 60_000L) }
        return START_NOT_STICKY
    }

    private suspend fun runSession(plannedMs: Long) {
        val dao = Db.get(this).dao()
        planned = plannedMs
        startWall = System.currentTimeMillis()
        sessionId = dao.insert(SessionEntity(startMs = startWall, plannedMs = plannedMs, scheduled = scheduled))
        FocusState.sessionId = sessionId
        FocusState.totalMs.value = plannedMs
        FocusState.remainingMs.value = plannedMs

        if (scheduled && scheduleId > 0) {
            val current = dao.schedule(scheduleId)
            if (current != null && current.enabled) Scheduler.schedule(this, current)
        }

        power = getSystemService(PowerManager::class.java)
        wl = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flipfocus:session")
            .apply { acquire(plannedMs + BREAK_MS + 60_000) }
        sm = getSystemService(SensorManager::class.java)
        vib = getSystemService(VibratorManager::class.java).defaultVibrator
        tm = getSystemService(TelephonyManager::class.java)
        usm = getSystemService(UsageStatsManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL); sensorsRegistered = true
        }
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL); sensorsRegistered = true
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try { tm.registerTelephonyCallback(mainExecutor, callCb); telReg = true } catch (_: SecurityException) {}
        }

        val start = SystemClock.elapsedRealtime()
        val end = start + plannedMs
        var last = start
        upSince = start
        failSince = start
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val dt = now - last
            last = now
            if (stopping) break
            if (now >= end) { sessionCompleted = true; break }

            pollUtility(now)
            if (!power.isInteractive) FocusState.setUtility(null)
            val util = FocusState.utilityActive

            if (paused || util) {
                if (!suspended) { suspended = true; stopVib() }
                violating = false
                downSince = 0L
                upSince = 0L
                failSince = 0L
                FocusState.phase.value = if (paused) Phase.PAUSED_CALL else Phase.UTILITY
            } else if (!hasZSample && now - start < SENSOR_WARMUP_MS) {
                FocusState.phase.value = Phase.ARMING
            } else {
                if (suspended) {
                    suspended = false
                    violating = false
                    downSince = 0L
                    upSince = now - (GRACE_MS - UTILITY_RESUME_MS)
                    failSince = now
                }
                val down = zf < (if (near) DOWN_Z else -8.0f)
                if (down) {
                    if (downSince == 0L) downSince = now
                    upSince = 0L
                    failSince = 0L
                    if (violating && now - downSince >= RECOVER_MS) {
                        violating = false
                        stopVib()
                        FocusState.phase.value = Phase.FOCUSED
                    } else if (violating) {
                        FocusState.phase.value = Phase.VIOLATION
                    } else {
                        FocusState.phase.value = Phase.FOCUSED
                    }
                } else {
                    downSince = 0L
                    if (upSince == 0L) upSince = now
                    if (failSince == 0L) failSince = now
                    val upFor = now - upSince
                    if (upFor >= GRACE_MS) {
                        faceUp += dt
                        if (!violating) { violating = true; pickups++; logPickup(); startVib() }
                        FocusState.phase.value = Phase.VIOLATION
                    } else {
                        FocusState.phase.value = Phase.ARMING
                    }
                    if (now - failSince >= FAIL_MS) markFailed()
                }
            }
            FocusState.remainingMs.value = end - now
            delay(250)
        }
        if (sessionCompleted && !stopping && !failed) runBreak()
        finish(sessionCompleted)
    }

    private suspend fun runBreak() {
        breakActive = true
        breakSkip = false
        stopVib()
        FocusState.setUtility(null)
        FocusState.armed.value = false
        FocusState.phase.value = Phase.BREAK
        FocusState.totalMs.value = BREAK_MS
        FocusState.remainingMs.value = BREAK_MS
        showBreakNotification()
        if (::sm.isInitialized && sensorsRegistered) { sm.unregisterListener(this); sensorsRegistered = false }
        if (telReg) { tm.unregisterTelephonyCallback(callCb); telReg = false }

        val end = SystemClock.elapsedRealtime() + BREAK_MS
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (stopping || breakSkip || now >= end) break
            FocusState.remainingMs.value = end - now
            delay(250)
        }
        breakActive = false
    }

    private fun markFailed() {
        if (failed || stopping || finished) return
        failed = true
        failureLogged = true
        FocusState.failed.value = true
        log("FAILED")
    }

    private fun finish(completed: Boolean) {
        if (finished) return
        stopping = true
        finished = true
        job?.cancel()
        stopVib()
        wl?.let { if (it.isHeld) it.release() }
        if (::sm.isInitialized && sensorsRegistered) { sm.unregisterListener(this); sensorsRegistered = false }
        if (telReg) { tm.unregisterTelephonyCallback(callCb); telReg = false }

        val sid = sessionId
        val s = SessionEntity(
            id = sid, startMs = startWall, plannedMs = planned, endMs = System.currentTimeMillis(),
            pickups = pickups, faceUpMs = faceUp, callBypasses = calls,
            completed = completed, failed = failed, scheduled = scheduled,
        )
        val app = applicationContext
        if (sid > 0) CoroutineScope(Dispatchers.IO).launch {
            val dao = Db.get(app).dao()
            dao.update(s)
            if (!completed) dao.insertEvent(EventEntity(sessionId = sid, type = "EARLY_END", ts = System.currentTimeMillis()))
            if (failed && !failureLogged) dao.insertEvent(EventEntity(sessionId = sid, type = "FAILED", ts = System.currentTimeMillis()))
        }
        FocusState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (!finished) {
            stopping = true
            stopVib()
            wl?.let { if (it.isHeld) it.release() }
            if (::sm.isInitialized && sensorsRegistered) { sm.unregisterListener(this); sensorsRegistered = false }
            if (telReg) tm.unregisterTelephonyCallback(callCb)
            FocusState.reset()
        }
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                zf = 0.8f * zf + 0.2f * e.values[2]
                hasZSample = true
                FocusState.z.value = zf
            }
            Sensor.TYPE_PROXIMITY -> {
                near = e.values[0] < minOf(5f, e.sensor.maximumRange)
                FocusState.near.value = near
            }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    @Suppress("DEPRECATION")
    private fun pollUtility(nowElapsed: Long) {
        if (!hasUsageAccess() || nowElapsed - lastUsagePoll < 1_000L) return
        lastUsagePoll = nowElapsed
        val manager = usm ?: return
        val until = System.currentTimeMillis()
        val events = try { manager.queryEvents(until - 10_000L, until) } catch (_: Exception) { return }
        var fg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) fg = event.packageName
        }
        val pkg = fg ?: return
        val apps = FocusState.utilityApps.value
        if (pkg in apps) FocusState.setUtility(pkg)
        else if (pkg == packageName || FocusState.activeUtility.value != null) FocusState.setUtility(null)
    }

    private suspend fun logPickup() {
        val sid = sessionId
        val ts = System.currentTimeMillis()
        val app = applicationContext
        val id = withContext(Dispatchers.IO) {
            Db.get(app).dao().insertEvent(EventEntity(sessionId = sid, type = "PICKUP", ts = ts))
        }
        FocusState.pendingTags.value = FocusState.pendingTags.value + PickupTag(id, ts)
    }

    private fun startVib() = buzz(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0))

    @Suppress("DEPRECATION")
    private fun buzz(e: VibrationEffect) {
        val aa = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try { vib.vibrate(e, aa) } catch (t: Throwable) { vib.vibrate(e) }
    }

    private fun stopVib() { if (::vib.isInitialized) vib.cancel() }

    private fun log(type: String) {
        val sid = sessionId
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            Db.get(app).dao().insertEvent(EventEntity(sessionId = sid, type = type, ts = System.currentTimeMillis()))
        }
    }

    private fun channel(nm: NotificationManager) =
        nm.createNotificationChannel(NotificationChannel(CH, "Focus session", NotificationManager.IMPORTANCE_LOW))

    private fun notif(): Notification {
        channel(getSystemService(NotificationManager::class.java))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FocusService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Focus session active")
            .setContentText("Keep your phone face-down · utility apps pause vibration")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "End", stop).build())
            .build()
    }

    private fun breakNotif(): Notification {
        channel(getSystemService(NotificationManager::class.java))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val skip = PendingIntent.getService(
            this, 2, Intent(this, FocusService::class.java).setAction(ACTION_SKIP_BREAK), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Focus break")
            .setContentText("Vibration and failure detection are paused")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Skip break", skip).build())
            .build()
    }

    private fun showBreakNotification() {
        getSystemService(NotificationManager::class.java).notify(NID, breakNotif())
    }
}
