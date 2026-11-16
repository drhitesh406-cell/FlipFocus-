package com.flipfocus.app

import android.Manifest
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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
 * Foreground service that watches orientation and the foreground app (utility apps pause vibration; other apps buzz). Monitoring starts
 * immediately: no initial face-down flip is needed. Alarm-clock schedules can start this service
 * even when the app UI is closed and the phone is locked.
 */
class FocusService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.flipfocus.app.STOP"
        const val ACTION_SKIP_BREAK = "com.flipfocus.app.SKIP_BREAK"
        const val ACTION_SCHEDULED_START = "com.flipfocus.app.SCHEDULED_START"
        const val EXTRA_MIN = "min"
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
        private const val APP_GRACE_MS = 2_000L
        private const val APP_COOLDOWN_MS = 3_000L

        fun start(
            ctx: Context,
            minutes: Int,
            scheduled: Boolean = false,
            scheduleId: Int = 0,
        ) {
            val i = Intent(ctx, FocusService::class.java)
                .putExtra(EXTRA_MIN, minutes)
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
    private var kg: KeyguardManager? = null
    private var homePkgs = emptySet<String>()
    private var curPkg: String? = null
    private var prevPkg: String? = null
    private var curSince = 0L
    private var curBuzzed = false
    private var lastAppBuzz = 0L
    private var lastAccelAt = 0L
    private var lastReg = 0L
    private var lastVibAt = 0L
    private var rxReg = false
    private var sessionTitle = "Focus session"
    private var statusText = "Starting…"
    private var endWall = 0L
    private var noteUntil = 0L
    private var lastTingAt = 0L
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) getSystemService(NotificationManager::class.java).cancel(2)
            if (!breakActive && !finished && !stopping) registerSensors()
        }
    }

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
        endWall = startWall + plannedMs
        sessionTitle = if (scheduled) "Schedule · " + ((if (scheduleId > 0) dao.schedule(scheduleId)?.name else null)?.ifBlank { "Focus" } ?: "Focus")
            else "Focus session · ${plannedMs / 60_000} min"
        refreshNotif()
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
        kg = getSystemService(KeyguardManager::class.java)
        homePkgs = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.ResolveInfoFlags.of(0L),
        ).map { it.activityInfo.packageName }.toSet()
        if (scheduled && (!power.isInteractive || kg?.isKeyguardLocked == true)) postStartFsi()
        registerSensors()
        try {
            registerReceiver(screenRx, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }, Context.RECEIVER_NOT_EXPORTED)
            rxReg = true
        } catch (_: Exception) {}
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

            pollForeground(now)
            // Sensors can go silent across screen-off/lock on some devices: re-register when events stop.
            if (sensorsRegistered && now - lastAccelAt > 2_500L && now - lastReg > 2_000L) registerSensors()
            if (!power.isInteractive) FocusState.setUtility(null)
            val util = FocusState.utilityActive

            if (paused || util) {
                if (!suspended) { suspended = true; stopVib() }
                violating = false
                downSince = 0L
                upSince = 0L
                failSince = 0L
                FocusState.phase.value = if (paused) Phase.PAUSED_CALL else Phase.UTILITY
            } else if (!power.isInteractive || kg?.isKeyguardLocked == true) {
                // Locked / screen off: never vibrate and never fail. Grace and fail timers restart on unlock.
                if (violating) stopVib()
                violating = false
                downSince = 0L
                upSince = 0L
                failSince = 0L
                FocusState.phase.value = Phase.FOCUSED
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
                    if (downSince == 0L) {
                        downSince = now
                        if (upSince != 0L && now - upSince >= 1_000L && now - lastTingAt > 3_000L) { lastTingAt = now; Chime.play(1) }
                    }
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
            // Unlock haptics can cancel an ongoing repeating vibration: restart it (gentle 0.5s on / 1.5s off pulses) while still face-up.
            if (violating && !paused && !FocusState.utilityActive && now - lastVibAt >= 4_000L) startVib()
            val st = when {
                now < noteUntil -> "Buzzed: you opened another app"
                FocusState.phase.value == Phase.VIOLATION -> "Vibrating: phone is face-up. Place it face-down"
                FocusState.phase.value == Phase.UTILITY -> "Utility app open · vibration paused"
                FocusState.phase.value == Phase.PAUSED_CALL -> "Paused for call"
                FocusState.phase.value == Phase.ARMING -> "Place phone face-down"
                else -> "Phone face-down · stay focused"
            }
            if (st != statusText) { statusText = st; refreshNotif() }
            FocusState.remainingMs.value = end - now
            delay(250)
        }
        if (sessionCompleted && !stopping) Chime.play(2)
        if (sessionCompleted && !stopping && !failed) runBreak()
        finish(sessionCompleted)
    }

    private suspend fun runBreak() {
        breakActive = true
        unregRx()
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
        unregRx()
        getSystemService(NotificationManager::class.java).cancel(2)
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
        unregRx()
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
                lastAccelAt = SystemClock.elapsedRealtime()
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
    private fun pollForeground(nowElapsed: Long) {
        if (!hasUsageAccess() || nowElapsed - lastUsagePoll < 1_000L) return
        lastUsagePoll = nowElapsed
        val manager = usm ?: return
        val until = System.currentTimeMillis()
        val events = try { manager.queryEvents(until - 10_000L, until) } catch (_: Exception) { return }
        var fg: String? = null
        var fgTs = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) { fg = event.packageName; fgTs = event.timeStamp }
        }
        val apps = FocusState.utilityApps.value
        fg?.let { pkg ->
            if (pkg in apps) FocusState.setUtility(pkg)
            else if (pkg == packageName || FocusState.activeUtility.value != null) FocusState.setUtility(null)
            if (pkg != curPkg) { prevPkg = curPkg; curPkg = pkg; curSince = fgTs; curBuzzed = false }
        }
        checkAppSwitch(apps, nowElapsed)
    }

    /** One short buzz when a non-utility app (or the home screen) stays in front for 2 s; never while locked. */
    private fun checkAppSwitch(apps: Set<String>, nowElapsed: Long) {
        val pkg = curPkg ?: return
        if (curBuzzed) return
        if (!power.isInteractive || kg?.isKeyguardLocked == true || paused || violating) { curBuzzed = true; return }
        if (pkg == packageName || pkg in apps || isSystemChrome(pkg)) return
        val prev = prevPkg
        if (pkg in homePkgs && prev != null && prev in apps) { curBuzzed = true; return }
        if (System.currentTimeMillis() - curSince < APP_GRACE_MS) return
        if (nowElapsed - lastAppBuzz < APP_COOLDOWN_MS) return
        curBuzzed = true
        lastAppBuzz = nowElapsed
        noteUntil = nowElapsed + 8_000L
        buzz(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
        val sid = sessionId
        val ts = System.currentTimeMillis()
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            Db.get(app).dao().insertEvent(EventEntity(sessionId = sid, type = "APP_OPEN", ts = ts, pkg = pkg))
        }
    }

    private fun isSystemChrome(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg == "android" || pkg.contains("inputmethod", true) ||
            pkg.contains("keyboard", true) || pkg.endsWith(".ime", true)

    private suspend fun logPickup() {
        val sid = sessionId
        val ts = System.currentTimeMillis()
        val app = applicationContext
        val id = withContext(Dispatchers.IO) {
            Db.get(app).dao().insertEvent(EventEntity(sessionId = sid, type = "PICKUP", ts = ts))
        }
        FocusState.pendingTags.value = FocusState.pendingTags.value + PickupTag(id, ts)
    }

    private fun startVib() { lastVibAt = SystemClock.elapsedRealtime(); buzz(VibrationEffect.createWaveform(longArrayOf(0, 500, 1500), 0)) }

    private fun registerSensors() {
        if (!::sm.isInitialized) return
        sm.unregisterListener(this)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL); sensorsRegistered = true }
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL); sensorsRegistered = true }
        lastReg = SystemClock.elapsedRealtime()
        lastAccelAt = lastReg
    }

    private fun unregRx() { if (rxReg) { try { unregisterReceiver(screenRx) } catch (_: Exception) {}; rxReg = false } }

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
            .setContentTitle(sessionTitle)
            .setContentText(statusText)
            .setStyle(Notification.BigTextStyle().bigText(statusText))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply { if (endWall > 0) setWhen(endWall).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) }
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

    private fun refreshNotif() { getSystemService(NotificationManager::class.java).notify(NID, notif()) }

    private fun postStartFsi() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!canFsi()) return
        nm.createNotificationChannel(NotificationChannel("focus_start", "Session start", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        val pi = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).putExtra("fsi", true).putExtra("tab", 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(2, Notification.Builder(this, "focus_start").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(sessionTitle).setContentText("Focus session started")
            .setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun showBreakNotification() {
        getSystemService(NotificationManager::class.java).notify(NID, breakNotif())
    }
}
