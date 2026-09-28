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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        val context = applicationContext
        val settings = loadSettings(context)

        // Если ночной режим выключен — ничего не делаем
        if (!settings.nightModeEnabled) return

        // Проверяем, наступило ли ночное время
        if (!isNightTimeActive(settings)) {
            // ИСПРАВЛЕНИЕ: Проверяем текущий фильтр напрямую у сервиса (currentInterruptionFilter)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                currentInterruptionFilter != INTERRUPTION_FILTER_NONE) {

                try {
                    // ИСПРАВЛЕНИЕ: Задаем фильтр тишины напрямую сервису
                    requestInterruptionFilter(INTERRUPTION_FILTER_NONE)
                    Log.d(TAG, "Ночной период! Система переведена в режим полной тишины (DND).")
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось переключить режим тишины: ${e.message}")
                }
            }
        } else {
            // Если наступило утро, возвращаем стандартный режим звука
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                currentInterruptionFilter == INTERRUPTION_FILTER_NONE) {

                try {
                    // ИСПРАВЛЕНИЕ: Возвращаем звук напрямую через сервис
                    requestInterruptionFilter(INTERRUPTION_FILTER_ALL)
                    Log.d(TAG, "Ночной период завершен. Звуки уведомлений возвращены.")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка возврата звука: ${e.message}")
                }
            }
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