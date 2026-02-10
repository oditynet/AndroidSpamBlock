package com.example.blocktel1

import android.os.Build
import android.telecom.CallScreeningService
import android.telecom.Call
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class MyCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: ""
        //Log.d("CallScreening", "Перехват звонка от: $phoneNumber")

        val shouldBlock = shouldBlockCall(this, phoneNumber)

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

        if (shouldBlock) {
            //Log.i("CallScreening", "Звонок заблокирован: $phoneNumber")
        }
    }

    private fun shouldBlockCall(context: android.content.Context, phoneNumber: String): Boolean {
        val prefs = context.getSharedPreferences("blocktel_prefs", android.content.Context.MODE_PRIVATE)
        val settings = AppSettings(
            callLogLimit = prefs.getInt("call_log_limit", 20),
            allowContacts = prefs.getBoolean("allow_contacts", false),
            blockHiddenNumbers = prefs.getBoolean("block_hidden", false),
            blockInternational = prefs.getBoolean("block_international", false)
        )

        val blockedPatterns = loadBlockedPatterns(context)

        // Проверяем номер
        return shouldBlockCall(
            phoneNumber,
            null,
            blockedPatterns,
            settings,
            context
        )
    }
}