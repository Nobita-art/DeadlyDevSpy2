package com.fasonbot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.fasonbot.app.service.CoreService
import com.fasonbot.app.service.ServiceHelper

class ServiceRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceRestartReceiver"
        private const val PREFS_NAME = "fasonbot_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Restart receiver triggered: ${intent.action}")

        // goAsync() keeps the BroadcastReceiver alive long enough to
        // complete startForegroundService — critical on Android 14
        val pendingResult = goAsync()

        Thread {
            try {
                if (!shouldServiceRun(context)) {
                    Log.d(TAG, "Service disabled — skipping restart")
                    return@Thread
                }

                // Method 1: direct foreground service start
                try {
                    val i = Intent(context, CoreService::class.java).apply {
                        action = CoreService.ACTION_START
                    }
                    ContextCompat.startForegroundService(context, i)
                    Log.d(TAG, "Service start requested")
                } catch (e: Exception) {
                    Log.e(TAG, "Direct start failed: ${e.message}")
                    // Method 2: schedule another alarm + WorkManager fallback
                    CoreService.scheduleRestartAlarm(context, 2000)
                    ServiceHelper.scheduleImmediateWorkManager(context)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun shouldServiceRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ENABLED, true)
}
