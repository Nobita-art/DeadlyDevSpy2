package com.fasonbot.app.action

import android.content.Context
import com.fasonbot.app.ws.WebSocketSender
import java.io.File

abstract class BaseExecutor(protected val context: Context) {

    protected fun sendResponse(message: String, parseMode: String? = null) {
        WebSocketSender.sendText(message)
    }

    protected fun sendDocument(file: File, caption: String? = null) {
        WebSocketSender.sendFile(file, caption)
    }

    protected fun createTempFile(name: String, content: String): File? {
        return try {
            File.createTempFile("${name}_", ".txt").apply {
                writeText(content)
            }
        } catch (e: Exception) {
            sendResponse("Error creating file: ${e.message}")
            null
        }
    }

    protected fun deleteTempFile(file: File?) {
        try { file?.delete() } catch (_: Exception) {}
    }
}
