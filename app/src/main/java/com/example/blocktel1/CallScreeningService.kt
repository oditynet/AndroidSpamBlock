package com.example.blocktel1

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.telecom.CallScreeningService
import android.telecom.Call
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

@RequiresApi(Build.VERSION_CODES.N)
class MyCallScreeningService : CallScreeningService() {

    private companion object {
        const val VERIFICATION_NOT_VERIFIED = 0
        const val VERIFICATION_PASSED = 1
        const val VERIFICATION_FAILED = 2
        const val NOTIFICATION_CHANNEL_ID = "call_block"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: ""

        // 1. STIR/SHAKEN проверка
        val verificationStatus = getStirShakenStatus(callDetails)
        val isSpoofed = verificationStatus == VERIFICATION_FAILED

        // 2. Проверка по паттернам
        val isInPatternList = shouldBlockByPatterns(phoneNumber)

        // 3. Определяем блокировку
        val shouldBlock = isSpoofed || isInPatternList

        // 4. Показываем уведомление если заблокирован
        if (shouldBlock) {
            val reason = if (isSpoofed) "STIR/SHAKEN" else "паттерн-список"
            showBlockNotification(phoneNumber, reason)
        }

        // 5. Отвечаем системе
        val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Используем Builder для API 29+
            CallScreeningService.CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setRejectCall(shouldBlock)
                .setSkipCallLog(shouldBlock)
                .setSkipNotification(shouldBlock)
                .build()
        } else {
            // Для более старых API используем базовый Builder
            CallScreeningService.CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setRejectCall(shouldBlock)
                .build()
        }

        respondToCall(callDetails, response)
    }

    private fun getStirShakenStatus(callDetails: Call.Details): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                callDetails.callerNumberVerificationStatus
            } else {
                VERIFICATION_NOT_VERIFIED
            }
        } catch (e: Exception) {
            VERIFICATION_NOT_VERIFIED
        }
    }

    private fun shouldBlockByPatterns(phoneNumber: String): Boolean {
        return try {
            val context = applicationContext
            val prefs = context.getSharedPreferences("blocktel_prefs", android.content.Context.MODE_PRIVATE)
            val settings = AppSettings(
                callLogLimit = prefs.getInt("call_log_limit", 20),
                allowContacts = prefs.getBoolean("allow_contacts", false),
                blockHiddenNumbers = prefs.getBoolean("block_hidden", false),
                blockInternational = prefs.getBoolean("block_international", false)
            )

            val blockedPatterns = loadBlockedPatterns(context)

            shouldBlockCall(
                phoneNumber,
                null,
                blockedPatterns,
                settings,
                context
            )
        } catch (e: Exception) {
            false
        }
    }

    private fun showBlockNotification(phoneNumber: String, reason: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val title = when (reason) {
                "STIR/SHAKEN" -> "Заблокировано по STIR/SHAKEN"
                else -> "Заблокировано (паттерн-список)"
            }

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText(phoneNumber)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)

        } catch (e: Exception) {
            Log.e("Notification", "Ошибка: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Блокировка звонков",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о заблокированных звонках"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}