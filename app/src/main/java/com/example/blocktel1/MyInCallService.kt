package com.example.blocktel1

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.compose.runtime.mutableStateOf

class MyInCallService : InCallService() {

    companion object {
        private const val TAG = "MyInCallService"
        var currentCall = mutableStateOf<Call?>(null)

        // Храним активную ссылку на запущенный системой сервис
        private var instance: MyInCallService? = null

        // Статическая функция для переключения звука
        fun toggleSpeaker(turnOn: Boolean) {
            val route = if (turnOn) {
                android.telecom.CallAudioState.ROUTE_SPEAKER
            } else {
                android.telecom.CallAudioState.ROUTE_EARPIECE
            }
            // Вызываем встроенный метод самого InCallService
            instance?.setAudioRoute(route)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this // Запоминаем инстанс при создании сервиса
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null // Очищаем ссылку при уничтожении
    }
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "Call added: ${call.details.handle}")

        val phoneNumber = call.details.handle?.schemeSpecificPart ?: ""
        val contactName = getContactNameFromPhoneBook(this, phoneNumber)

        val settings = loadSettings(this)
        val blockedPatterns = loadBlockedPatterns(this)

        val shouldBlock = shouldBlockCall(
            phoneNumber,
            contactName,
            blockedPatterns,
            settings,
            this
        )

        if (shouldBlock) {
            Log.d(TAG, "Blocking call via InCallService")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                call.reject(false, "Blocked")
            } else {
                call.disconnect()
            }
        }
        else {
            // Если звонок нормальный — сохраняем его и открываем приложение
            currentCall.value = call

            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "Call removed")
        currentCall.value = null
    }
}