package com.fasonbot.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fasonbot.app.R

/**
 * CompanionService runs in a SEPARATE PROCESS (:guard).
 *
 * Android 14 (Infinix XOS 14) aggressive process-killing almost never kills
 * two DIFFERENT processes simultaneously.
 *
 * Strategy:
 *  - CoreService (main process) starts CompanionService on creation.
 *  - CompanionService (in :guard process) calls CoreService.start() every 30 s.
 *  - If the OS kills the main process, CompanionService revives CoreService.
 *  - If the OS kills :guard, CoreService's onDestroy schedules an alarm that
 *    will restart both services.
 *
 * This is the same dual-process watchdog used by persistent apps on Android.
 */
class CompanionService : Service() {

    companion object {
        private const val TAG           = "CompanionService"
        private const val CHANNEL_ID    = "guard_channel"
        private const val NOTIF_ID      = 1002
        private const val WATCH_INTERVAL = 30_000L
        private const val PREFS_NAME    = "fasonbot_prefs"
        private const val KEY_ENABLED   = "service_enabled"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, CompanionService::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "start: ${e.message}")
            }
        }
    }

    private val handler  = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // Every WATCH_INTERVAL: poke CoreService to start if it isn't alive.
    private val watchRunnable = object : Runnable {
        override fun run() {
            try {
                if (isServiceEnabled()) {
                    // startForegroundService is harmless if CoreService is running —
                    // it simply calls onStartCommand, which re-acquires wake lock.
                    CoreService.start(applicationContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "watch: ${e.message}")
            }
            handler.postDelayed(this, WATCH_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        goForeground()
        acquireWakeLock()
        // First poke after 5 s (boot delay), then every WATCH_INTERVAL
        handler.postDelayed(watchRunnable, 5_000)
        Log.i(TAG, "CompanionService running in :guard process")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (wakeLock?.isHeld == false) acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "CompanionService destroyed — scheduling CoreService recovery")
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        CoreService.scheduleRestartAlarm(applicationContext, 1_000)
        ServiceHelper.scheduleImmediateWorkManager(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────
    private fun isServiceEnabled() =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Guard Service", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Background guard"
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false); enableLights(false); enableVibration(false); setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun goForeground() {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(" ")
            .setContentText(" ")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "goForeground: ${e.message}")
            try { startForeground(NOTIF_ID, n) } catch (_: Exception) {}
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FasonBot:guard")
            wakeLock?.acquire()
        } catch (e: Exception) { Log.e(TAG, "acquireWakeLock: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }
}
