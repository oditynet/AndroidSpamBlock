package com.example.blocktel1

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.telecom.CallScreeningService
import android.telecom.Call
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import android.provider.ContactsContract
import android.content.Context

@RequiresApi(Build.VERSION_CODES.N)
class MyCallScreeningService : CallScreeningService() {

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "call_block"
        const val TAG = "CallScreeningService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "CallScreeningService created")
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: ""
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")


        val contactName = getContactName(cleanNumber)
        Log.d(TAG, "Имя: ${contactName ?: "Не найдено"}")

        val settings = loadSettings(this)
        val blockedPatterns = loadBlockedPatterns(this)

        val shouldBlock = shouldBlockCall(
            phoneNumber,
            contactName,
            blockedPatterns,
            settings,
            this
        )

        Log.d(TAG, "Блокировать: $shouldBlock")

        if (shouldBlock) {
            showBlockNotification(contactName ?: phoneNumber)
        }

        val response = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CallScreeningService.CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setRejectCall(shouldBlock)
                .setSkipCallLog(shouldBlock)
                .setSkipNotification(shouldBlock)
                .build()
        } else {
            CallScreeningService.CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setRejectCall(shouldBlock)
                .build()
        }

        respondToCall(callDetails, response)
    }

    private fun getContactName(phoneNumber: String): String? {
        if (phoneNumber.isEmpty()) return null

        return try {
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")

            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(cleanNumber)
                .build()

            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name", e)
            null
        }
    }

    private fun showBlockNotification(displayInfo: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("🚫 Заблокированный звонок")
                .setContentText(displayInfo)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}