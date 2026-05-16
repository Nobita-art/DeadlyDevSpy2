package com.fasonbot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import com.fasonbot.app.service.CoreService
import com.fasonbot.app.service.ServiceHelper

class NetworkReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkReceiver"
        private const val PREFS_NAME = "fasonbot_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Network event: ${intent.action}")

        if (!isNetworkAvailable(context)) return
        if (!shouldServiceRun(context)) return

        Log.d(TAG, "Network available — ensuring service is running")
        ensureServiceRunning(context)
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    private fun ensureServiceRunning(context: Context) {
        try {
            if (!CoreService.isRunning()) {
                val intent = Intent(context, CoreService::class.java).apply {
                    action = CoreService.ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } else {
                // Service is running but WebSocket may be down — reconnect
                val client = CoreService.getWebSocketClient(context)
                if (!client.isConnected()) {
                    client.reconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ensure service error: ${e.message}")
            CoreService.scheduleRestartAlarm(context, 3000)
            ServiceHelper.scheduleImmediateWorkManager(context)
        }
    }

    private fun shouldServiceRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ENABLED, true)
}
