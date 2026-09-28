package com.example.blocktel1

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Calendar

class MyNotificationListener : NotificationListenerService() {

    private companion object {
        const val TAG = "NotificationListener"
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        val context = applicationContext
        val settings = loadSettings(context)

        // 1. Если ночной режим выключен тумблером — принудительно разрешаем звуки и выходим
        if (!settings.nightModeEnabled) {
            requestInterruptionFilter(INTERRUPTION_FILTER_ALL)
            return
        }

        // 2. Живой расчёт минут суток прямо на месте
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(java.util.Calendar.MINUTE)

        val nowMin = currentHour * 60 + currentMinute
        val startMin = settings.nightStartHour * 60 + settings.nightStartMinute
        val endMin = settings.nightEndHour * 60 + settings.nightEndMinute

        // Математическое определение: наступила ли ночь (с учётом перехода через 00:00)
        val isNightNow = if (startMin <= endMin) {
            nowMin in startMin..endMin
        } else {
            nowMin >= startMin || nowMin <= endMin
        }

        // 3. Прямое управление фильтрами DND без всяких знаков отрицания (!)
        if (isNightNow) {
            // СЕЙЧАС НОЧЬ — принудительно запрещаем звуки и включаем тишину
            requestInterruptionFilter(INTERRUPTION_FILTER_NONE)
            android.util.Log.d("NightMode", "ЛОГ: Ночное время суток. Звуки смартфона полностью ГЛУШАТСЯ.")
        } else {
            // СЕЙЧАС ДЕНЬ — всегда возвращаем стандартный режим и активируем звуки
            requestInterruptionFilter(INTERRUPTION_FILTER_ALL)
            android.util.Log.d("NightMode", "ЛОГ: Дневное время суток. Все звуки смартфона РАЗРЕШЕНЫ.")
        }
    }

    private fun isNightTimeActive(settings: AppSettings): Boolean {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        val currentTimeInMinutes = currentHour * 60 + currentMinute
        val startTimeInMinutes = settings.nightStartHour * 60 + settings.nightStartMinute
        val endTimeInMinutes = settings.nightEndHour * 60 + settings.nightEndMinute

        return if (startTimeInMinutes <= endTimeInMinutes) {
            currentTimeInMinutes in startTimeInMinutes..endTimeInMinutes
        } else {
            currentTimeInMinutes >= startTimeInMinutes || currentTimeInMinutes <= endTimeInMinutes
        }
    }
}