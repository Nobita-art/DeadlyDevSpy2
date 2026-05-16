package com.fasonbot.app.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

class WatchdogJobService : JobService() {

    companion object {
        private const val TAG = "WatchdogJob"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Watchdog triggered")
        try {
            if (!CoreService.isRunning()) {
                Log.i(TAG, "CoreService not running — starting it")
                CoreService.start(applicationContext)
            } else {
                // Service is running; force reconnect if WebSocket is down
                val client = CoreService.getWebSocketClient(applicationContext)
                if (!client.isConnected()) {
                    Log.i(TAG, "WebSocket down — reconnecting")
                    client.reconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog error: ${e.message}")
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
