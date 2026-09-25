package com.example.blocktel1

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch

class MyInCallService : InCallService() {

    companion object {
        private const val TAG = "MyInCallService"
        var currentCall = mutableStateOf<android.telecom.Call?>(null)
        private var instance: MyInCallService? = null

        // Плеер для воспроизведения системного рингтона
        private var mediaPlayer: android.media.MediaPlayer? = null
        // Объект для управления вибрацией железа телефона
        private var vibrator: android.os.Vibrator? = null

        // ФУНКЦИЯ ДЛЯ ВКЛЮЧЕНИЯ ВИБРАЦИИ
        fun startVibration(context: android.content.Context) {
            try {
                val settings = loadSettings(context)
                // Проверяем галку в настройках, которую выбрал пользователь
                if (!settings.vibrationEnabled) {
                    Log.d(TAG, "Вибрация отключена пользователем в настройках")
                    return
                }

                if (vibrator == null) {
                    vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }

                // Паттерн прерывистого звонка: 0мс ждем, 1000мс вибрируем, 1000мс отдыхаем
                val pattern = longArrayOf(0, 1000, 1000)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // Для современных Android (8.0 и до Android 14/15/16)
                    // 0 означает зациклить паттерн с самого начала
                    val effect = android.os.VibrationEffect.createWaveform(pattern, 0)

                    // Привязываем вибрацию к типу "Звонок", чтобы учитывался режим "Не беспокоить"
                    val attributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()

                    vibrator?.vibrate(effect, attributes)
                } else {
                    // Для очень старых версий Android
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
                Log.d(TAG, "Вибрация успешно запущена в такт звонку")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска вибрации: ${e.message}")
            }
        }

        // ФУНКЦИЯ ДЛЯ СТОПА ВИБРАЦИИ
        fun stopVibration() {
            try {
                vibrator?.let {
                    it.cancel() // Программный стоп мотора вибрации
                    Log.d(TAG, "Вибрация остановлена")
                }
                vibrator = null
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки вибрации: ${e.message}")
            }
        }


        // ФУНКЦИЯ ДЛЯ ЗАПУСКА СИСТЕМНОЙ МЕЛОДИИ ЗВОНКА
        fun startRingtone(context: android.content.Context) {
            try {
                if (mediaPlayer != null) return // Уже играет

                // 1. Запрашиваем у системы URI мелодии, которую выбрал пользователь
                val ringtoneUri: android.net.Uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(
                    context,
                    android.media.RingtoneManager.TYPE_RINGTONE
                ) ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

                // 2. Инициализируем плеер
                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(context, ringtoneUri)
                    isLooping = true // Зацикливаем проигрывание

                    // 3. Указываем Android, что этот звук — именно входящий вызов
                    // Это автоматически применит громкость рингтона и учтет режим "Не беспокоить"
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setAudioAttributes(audioAttributes)

                    prepare()
                    start()
                }
                Log.d(TAG, "Системный рингтон успешно запущен: $ringtoneUri")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка воспроизведения системного рингтона: ${e.message}")

                // Резервный вариант на случай ошибки доступа к кастомному файлу
                playFallbackRington(context)
            }
        }

        // Резервный запуск стандартного звука, если к выбранному файлу нет доступа
        private fun playFallbackRington(context: android.content.Context) {
            try {
                val fallbackUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(context, fallbackUri)
                    isLooping = true
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Даже резервный рингтон не запустился: ${e.message}")
            }
        }

        // ФУНКЦИЯ ДЛЯ ОСТАНОВКИ МЕЛОДИИ
        fun stopRingtone() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.stop()
                    }
                    it.release()
                }
                mediaPlayer = null
                Log.d(TAG, "Рингтон остановлен")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при остановке рингтона: ${e.message}")
            }
        }
        // НАДЕЖНАЯ ФУНКЦИЯ ДЛЯ ANDROID 14+: Работает через AudioManager железа телефона
        fun toggleSpeaker(turnOn: Boolean) {
            val route = if (turnOn) {
                android.telecom.CallAudioState.ROUTE_SPEAKER
            } else {
                android.telecom.CallAudioState.ROUTE_EARPIECE
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    // Проверяем: если инстанс запущен, принудительно приводим его к базовому классу InCallService
                    val serviceInstance = instance as? android.telecom.InCallService

                    if (serviceInstance != null) {
                        // Подавляем ложное предупреждение старых версий компилятора
                        @Suppress("DEPRECATION")
                        serviceInstance.setAudioRoute(route)
                        Log.d(TAG, "Аудио-маршрут успешно изменен через InCallService на: $route")
                    } else {
                        Log.e(TAG, "Не удалось изменить маршрут: Инстанс сервиса пуст")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка переключения динамика: ${e.message}")
                }
            }
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

        // 1. КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Реагируем ТОЛЬКО на состояние входящего звонка (RINGING).
        // Если ОС присылает события изменения состояния (DISCONNECTING, ACTIVE и т.д.), полностью их игнорируем,
        // чтобы избежать лавинообразного бесконечного цикла проверок и зависания.
        if (call.state != android.telecom.Call.STATE_RINGING) {
            Log.d(TAG, "Игнорируем триггер: вызов находится в состоянии ${call.state}, а не RINGING")
            return
        }

        val rawPhoneNumber = call.details.handle?.schemeSpecificPart ?: ""
        val phoneNumber = android.net.Uri.decode(rawPhoneNumber)
        val contactName = getContactNameFromPhoneBook(this, phoneNumber)

        val settings = loadSettings(this)
        val blockedPatterns = loadBlockedPatterns(this)

        // 2. Локальная моментальная проверка (Ваши контакты, Ночной режим, Черный список)
        val shouldBlockLocally = shouldBlockCall(phoneNumber, contactName, blockedPatterns, settings, this)

        if (shouldBlockLocally) {
            Log.d(TAG, "Номер заблокирован локальным правилом приложения.")
            rejectCallSystem(call)
            return
        }

        // 3. Облачная проверка Baserow (только для незнакомых номеров, которых нет в книге контактов)
        if (contactName == null) {
            val androidId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown_device"

            // Запускаем асинхронный сетевой запрос строго в фоновом пуле потоков (Dispatchers.IO)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val isSpamInCloud = BaserowClient.checkIsSpam(phoneNumber, androidId)

                // Возвращаемся на главный поток UI без использования Handler
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {

                    // Дополнительная проверка: не отменил ли пользователь вызов, пока шел интернет-запрос
                    if (isSpamInCloud && call.state == android.telecom.Call.STATE_RINGING) {
                        Log.d(TAG, "Облачный консенсус Baserow велел ЗАБЛОКИРОВАТЬ звонок.")
                        rejectCallSystem(call)
                    } else if (!isSpamInCloud && call.state == android.telecom.Call.STATE_RINGING) {
                        Log.d(TAG, "Облако подтвердило: номер чист. Пропускаем на экран.")
                        allowCallSystem(call)
                    }
                }
            }
        } else {
            // Номер из телефонной книги — пускаем вызов мгновенно без интернета
            allowCallSystem(call)
        }
    }

    // Вспомогательный метод сброса звонка для чистоты кода
    private fun rejectCallSystem(call: Call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            call.reject(false, "Blocked by Cloud Consensus")
        } else {
            call.disconnect()
        }
    }

    // Вспомогательный метод пропуска звонка (Ваш оригинальный код интерфейса)
    private fun allowCallSystem(call: Call) {
        currentCall.value = call
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            call.details.callDirection == Call.Details.DIRECTION_INCOMING
        } else {
            call.state == Call.STATE_RINGING
        }
        if (isIncoming) {
            startRingtone(this)
            startVibration(this)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        stopRingtone()
        stopVibration()
        Log.d(TAG, "Call removed")
        currentCall.value = null
    }
}