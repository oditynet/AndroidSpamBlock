package com.example.blocktel1

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Calendar

class MyNotificationListener : NotificationListenerService() {

    private companion object {
        const val TAG = "NotificationListener"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ЛОГ: Служба MyNotificationListener создана.")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "ЛОГ: Служба MyNotificationListener успешно подключена к системе!")
        checkAndApplyNightMode()
    }

    // === КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Ловим мгновенные изменения из настроек ===
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ЛОГ: Получена команда из интерфейса. Обновляю время ночи...")
        checkAndApplyNightMode()
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // Пересчитываем режим при прилете нового уведомления
        checkAndApplyNightMode()
    }

    private fun checkAndApplyNightMode() {
        return //OFF режим
        val context = applicationContext
        val settings = loadSettings(context)

        // 1. Если ночной режим выключен — принудительно возвращаем все звуки
            /*if (!settings.nightModeEnabled) {
            try {
                requestInterruptionFilter(INTERRUPTION_FILTER_ALL)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка переключения фильтра: ${e.message}")
            }
            return
        }*/

        // 2. Живой расчет минут суток
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        val nowMin = currentHour * 60 + currentMinute
        val startMin = settings.nightStartHour * 60 + settings.nightStartMinute
        val endMin = settings.nightEndHour * 60 + settings.nightEndMinute

        val isNightNow = if (startMin <= endMin) {
            nowMin in startMin..endMin
        } else {
            nowMin >= startMin || nowMin <= endMin
        }

        // 3. Прямое управление режимом "Не беспокоить"
        /*try {
            if (isNightNow) {
                requestInterruptionFilter(INTERRUPTION_FILTER_NONE)
                Log.d(TAG, "ЛОГ: Ночное время активно. Звуки смартфона полностью ГЛУШАТСЯ.")
            } else {
                requestInterruptionFilter(INTERRUPTION_FILTER_ALL)
                Log.d(TAG, "ЛОГ: Дневное время суток. Все звуки смартфона РАЗРЕШЕНЫ.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка DND: ${e.message}. Проверьте, выдан ли доступ к уведомлениям!")
        }*/
    }
}