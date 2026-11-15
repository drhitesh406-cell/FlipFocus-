package com.flipfocus.app

import android.Manifest
import android.app.*
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
 * Foreground service (type specialUse) that watches the accelerometer (Z axis) and proximity
 * sensor. Face-down = Z < -6.5 m/s² and proximity near. Everything runs on the main thread.
 */
class FocusService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.flipfocus.app.STOP"
        const val EXTRA_MIN = "min"
        const val EXTRA_APPS = "apps"
        private const val CH = "focus"
        private const val NID = 1
        private const val ARM_MS = 2_000L      // must stay face-down this long to arm
        private const val GRACE_MS = 3_000L    // face-up tolerated this long before vibrating
        private const val RECOVER_MS = 1_500L  // face-down this long to stop vibrating
        private const val DOWN_Z = -6.5f

        fun start(ctx: Context, minutes: Int, apps: Set<String> = emptySet()) {
            val i = Intent(ctx, FocusService::class.java).putExtra(EXTRA_MIN, minutes)
                .putStringArrayListExtra(EXTRA_APPS, ArrayList(apps))
            try { ctx.startForegroundService(i) } catch (_: Exception) { /* background start refused */ }
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FocusService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var sm: SensorManager
    private lateinit var vib: Vibrator
    private lateinit var tm: TelephonyManager
    private var telReg = false
    private var finished = false
    private var wl: PowerManager.WakeLock? = null

    private var zf = 0f
    private var near = true // stays true if the device has no proximity sensor
    private var paused = false
    private var suspended = false
    private var armed = false
    private var violating = false
    private var downSince = 0L
    private var upSince = 0L

    private var sessionId = 0L
    private var startWall = 0L
    private var planned = 0L
    private var pickups = 0
    private var calls = 0
    private var faceUp = 0L

    private val callCb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                if (paused) { // resume: user must flip the phone again before monitoring re-arms
                    paused = false; armed = false; violating = false; downSince = 0
                    upSince = SystemClock.elapsedRealtime()
                }
            } else if (!paused) { // RINGING or OFFHOOK
                paused = true; stopVib(); calls++; log("CALL_BYPASS")
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) {
            if (job != null) finish(false) else stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        startForeground(NID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        FocusState.blockedApps = i?.getStringArrayListExtra(EXTRA_APPS)?.toSet() ?: emptySet()
        FocusState.utilityApps = Prefs.utility(this); FocusState.utilityActive = false
        val mins = i?.getIntExtra(EXTRA_MIN, 25) ?: 25
        job = scope.launch { runSession(mins * 60_000L) }
        return START_NOT_STICKY
    }

    private suspend fun runSession(plannedMs: Long) {
        val dao = Db.get(this).dao()
        planned = plannedMs
        startWall = System.currentTimeMillis()
        sessionId = dao.insert(SessionEntity(startMs = startWall, plannedMs = plannedMs))
        FocusState.sessionId = sessionId
        FocusState.totalMs.value = plannedMs

        wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flipfocus:session")
            .apply { acquire(plannedMs + 60_000) } // keep CPU running while the screen is off
        sm = getSystemService(SensorManager::class.java)
        vib = getSystemService(VibratorManager::class.java).defaultVibrator
        tm = getSystemService(TelephonyManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try { tm.registerTelephonyCallback(mainExecutor, callCb); telReg = true } catch (_: SecurityException) {}
        }

        val start = SystemClock.elapsedRealtime()
        val end = start + plannedMs
        var last = start
        upSince = start
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val dt = now - last; last = now
            if (now >= end) break
            val util = FocusState.utilityActive
            if (paused || util) {
                if (!suspended) { suspended = true; stopVib() }
                FocusState.phase.value = if (paused) Phase.PAUSED_CALL else Phase.UTILITY
            } else {
                if (suspended) { // leaving a utility app: vibrate again right away if the phone is not face-down
                    suspended = false; violating = false; downSince = 0; upSince = now - GRACE_MS
                }
                val down = zf < (if (near) DOWN_Z else -8.0f) // proximity far => require a flatter phone
                if (down) { if (downSince == 0L) downSince = now; upSince = 0 }
                else { downSince = 0; if (upSince == 0L) upSince = now }
                when {
                    !armed -> {
                        FocusState.phase.value = Phase.ARMING
                        if (down && now - downSince >= ARM_MS) { armed = true; FocusState.armed.value = true; pulse() }
                    }
                    violating -> {
                        faceUp += dt
                        if (down && now - downSince >= RECOVER_MS) {
                            violating = false; stopVib(); FocusState.phase.value = Phase.FOCUSED
                        }
                    }
                    else -> {
                        FocusState.phase.value = Phase.FOCUSED
                        if (!down && now - upSince >= GRACE_MS) {
                            violating = true; pickups++; log("PICKUP"); startVib()
                            FocusState.phase.value = Phase.VIOLATION
                        }
                    }
                }
            }
            FocusState.remainingMs.value = end - now
            delay(250)
        }
        finish(true)
    }

    private fun finish(completed: Boolean) {
        if (finished) return
        finished = true
        job?.cancel()
        stopVib(); wl?.let { if (it.isHeld) it.release() }
        if (::sm.isInitialized) sm.unregisterListener(this)
        if (telReg) { tm.unregisterTelephonyCallback(callCb); telReg = false }
        val sid = sessionId
        val s = SessionEntity(
            id = sid, startMs = startWall, plannedMs = planned, endMs = System.currentTimeMillis(),
            pickups = pickups, faceUpMs = faceUp, callBypasses = calls, completed = completed,
        )
        val app = applicationContext
        if (sid > 0) CoroutineScope(Dispatchers.IO).launch {
            val dao = Db.get(app).dao()
            dao.update(s)
            if (!completed) dao.insertEvent(EventEntity(sessionId = sid, type = "EARLY_END", ts = System.currentTimeMillis()))
        }
        FocusState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (!finished) { // killed unexpectedly: release hardware
            stopVib(); wl?.let { if (it.isHeld) it.release() }
            if (::sm.isInitialized) sm.unregisterListener(this)
            if (telReg) tm.unregisterTelephonyCallback(callCb)
            FocusState.reset()
        }
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { zf = 0.8f * zf + 0.2f * e.values[2]; FocusState.z.value = zf }
            Sensor.TYPE_PROXIMITY -> { near = e.values[0] < minOf(5f, e.sensor.maximumRange); FocusState.near.value = near }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun startVib() {
        val eff = VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0) // repeat from index 0
        buzz(eff)
    }
    @Suppress("DEPRECATION")
    private fun buzz(e: VibrationEffect) {
        val aa = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try { vib.vibrate(e, aa) } catch (t: Throwable) { vib.vibrate(e) }
    }
    private fun pulse() { // short buzz = "armed"; also confirms vibration works
        buzz(VibrationEffect.createOneShot(120, 255))
    }
    private fun stopVib() { if (::vib.isInitialized) vib.cancel() }

    private fun log(type: String) {
        val sid = sessionId
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            Db.get(app).dao().insertEvent(EventEntity(sessionId = sid, type = type, ts = System.currentTimeMillis()))
        }
    }

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Focus session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FocusService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Focus session active")
            .setContentText("Keep your phone face-down")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
