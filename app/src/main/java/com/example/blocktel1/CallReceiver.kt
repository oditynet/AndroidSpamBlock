package com.example.blocktel1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d("CallReceiver", "Состояние звонка: $state, Номер: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Входящий звонок
                incomingNumber?.let { number ->
                    val contactName = getContactName(context, number)

                    // Загружаем список блокируемых текстов из SharedPreferences
                    val blockedPatterns = loadBlockedPatterns(context)

                    // Проверяем, нужно ли блокировать этот звонок
                    val shouldBlock = shouldBlockCall(number, contactName, blockedPatterns)

                    val logMessage = "Входящий звонок:\n" +
                            "Номер: $number\n" +
                            "Имя: ${contactName ?: "Неизвестный"}\n" +
                            "Блокировать: $shouldBlock"

                    Log.i("CallMonitor", logMessage)

                    // Показываем уведомление
                    Toast.makeText(
                        context,
                        "📞 ${if (shouldBlock) "БЛОКИРУЕМ" else "Звонок от"}: ${contactName ?: number}",
                        Toast.LENGTH_LONG
                    ).show()

                    // Если нужно блокировать, пытаемся сбросить звонок
                    if (shouldBlock) {
                        blockCall(context)
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.d("CallMonitor", "Звонок начался")
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d("CallMonitor", "Звонок завершен")
            }
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        return try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(phoneNumber)
                .build()

            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        cursor.getString(nameIndex)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.e("CallReceiver", "Ошибка при получении имени контакта", e)
            null
        }
    }

    private fun loadBlockedPatterns(context: Context): List<String> {
        val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
        val patterns = prefs.getStringSet("blocked_patterns", emptySet()) ?: emptySet()
        return patterns.toList()
    }

    private fun shouldBlockCall(number: String, name: String?, blockedPatterns: List<String>): Boolean {
        if (blockedPatterns.isEmpty()) return false

        return blockedPatterns.any { pattern ->
            pattern.isNotBlank() && (
                number.contains(pattern, ignoreCase = true) ||
                (name?.contains(pattern, ignoreCase = true) == true)
            )
        }
    }

    private fun blockCall(context: Context) {
        try {
            Log.w("CallMonitor", "ПОПЫТКА ЗАБЛОКИРОВАТЬ ЗВОНОК")

            // Способ 1: Используем ITelephony (требует системного разрешения)
            // Это более сложный метод, который может работать не на всех устройствах

            // Способ 2: Используем доступные методы для завершения звонка
            // Для Android 9+ (API 28) и выше можно попробовать этот способ
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE)
                        as android.telecom.TelecomManager

                    // Пытаемся завершить звонок
                    telecomManager.endCall()
                    Log.i("CallMonitor", "Звонок завершен через TelecomManager")
                    Toast.makeText(context, "Звонок заблокирован!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("CallMonitor", "Ошибка при завершении звонка через TelecomManager", e)
                }
            }

            // Способ 3: Отправляем интент для сброса звонка (работает на некоторых устройствах)
            try {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT,
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_HEADSETHOOK))
                }
                context.sendOrderedBroadcast(intent, null)

                Thread.sleep(100)

                val intent2 = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT,
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_HEADSETHOOK))
                }
                context.sendOrderedBroadcast(intent2, null)

                Log.i("CallMonitor", "Отправлены команды для сброса звонка")
            } catch (e: Exception) {
                Log.e("CallMonitor", "Ошибка при отправке команд для сброса", e)
            }

        } catch (e: Exception) {
            Log.e("CallMonitor", "Общая ошибка при блокировке звонка", e)
            Toast.makeText(context, "Ошибка блокировки звонка", Toast.LENGTH_SHORT).show()
        }
    }
}