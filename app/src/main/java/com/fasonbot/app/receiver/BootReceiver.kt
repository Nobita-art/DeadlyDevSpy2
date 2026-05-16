package com.fasonbot.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.fasonbot.app.service.CoreService
import com.fasonbot.app.service.ServiceHelper

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "fasonbot_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Boot/autostart received: $action")

        // goAsync() prevents the receiver from being killed before the
        // background thread completes — essential on Android 14
        val pendingResult = goAsync()

        Thread {
            try {
                saveServiceState(context, true)

                when (action) {
                    Intent.ACTION_SHUTDOWN -> {
                        saveServiceState(context, true)
                        return@Thread
                    }
                    Intent.ACTION_USER_PRESENT,
                    "android.intent.action.USER_UNLOCKED" -> {
                        startServiceDirect(context)
                        ServiceHelper.initializePersistence(context)
                    }
                    else -> {
                        startServiceWithBackup(context)
                        ServiceHelper.initializePersistence(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun startServiceWithBackup(context: Context) {
        startServiceDirect(context)
        // Staggered alarm backups — Android 14 sometimes delays the first start
        scheduleAlarmBackup(context, 500,    1001)
        scheduleAlarmBackup(context, 2000,   1002)
        scheduleAlarmBackup(context, 5000,   1003)
        scheduleAlarmBackup(context, 15_000, 1004)
        scheduleAlarmBackup(context, 30_000, 1005)
    }

    private fun startServiceDirect(context: Context) {
        try {
            val i = Intent(context, CoreService::class.java).apply {
                action = CoreService.ACTION_START
            }
            ContextCompat.startForegroundService(context, i)
            Log.d(TAG, "Service start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Direct start failed: ${e.message}")
        }
    }

    private fun scheduleAlarmBackup(context: Context, delayMs: Long, requestCode: Int) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ServiceRestartReceiver::class.java).apply {
                    action = CoreService.ALARM_ACTION
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = SystemClock.elapsedRealtime() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Alarm backup ($delayMs ms) failed: ${e.message}")
        }
    }

    private fun saveServiceState(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_ENABLED, running).apply()
    }
}
