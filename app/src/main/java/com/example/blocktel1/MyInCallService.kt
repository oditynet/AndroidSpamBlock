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

        Log.d(TAG, "Call added: ${call.details.handle}")

        // 1. Считываем сырую строку (Используем оригинальные переменные ОДИН раз)
        val rawPhoneNumber1 = call.details.handle?.schemeSpecificPart ?: ""
        val phoneNumber1 = android.net.Uri.decode(rawPhoneNumber1)
        val cleanNumber = phoneNumber1.filter { it.isDigit() || it == '+' }

        // === ИСПРАВЛЕНИЕ: БЛОК АНАЛИЗА СЕТИ (БЕЗ ДУБЛИРОВАНИЯ ПЕРЕМЕННЫХ) ===
        try {
            val telephonyManager = getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager

            // Получаем Technology Type (GSM = 1, CDMA = 2, SIP/VoIP = 3)
            val techType = telephonyManager.phoneType

            // Получаем Network Type (LTE = 13, 3G = 3, 2G = 1 и т.д.) через правильный ContextCompat
            val networkType = if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    telephonyManager.dataNetworkType
                } else {
                    @Suppress("DEPRECATION") telephonyManager.networkType
                }
            } else {
                0
            }

            // Получаем презентацию номера (Скрыт = 2, Разрешен = 1)
            val presentation = call.details.callerDisplayNamePresentation

            // Сохраняем технические параметры в SharedPreferences для истории
            val prefs = getSharedPreferences("blocktel_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("tech_type_$cleanNumber", techType)
                .putInt("net_type_$cleanNumber", networkType)
                .putInt("pres_type_$cleanNumber", presentation)
                .apply()

            Log.d(TAG, "АНАЛИЗАТОР ЗВОНКА: Номер $cleanNumber | Tech Type: $techType | Net Type: $networkType | Pres: $presentation")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка анализатора сети: ${e.message}")
        }


        Log.d(TAG, "Call added: ${call.details.handle}")

        // Считываем сырую строку (например, "+79209224243" или "%2B79209224243")
        val rawPhoneNumber = call.details.handle?.schemeSpecificPart ?: ""

        // КРИТИЧЕСКИ ВАЖНО: Декодируем %2B обратно в знак +
        val phoneNumber = android.net.Uri.decode(rawPhoneNumber)
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

            val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call.details.callDirection == Call.Details.DIRECTION_INCOMING
            } else {
                call.state == Call.STATE_RINGING
            }

            if (!shouldBlock && isIncoming) {
                startRingtone(this)
                startVibration(this)
            }


            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        stopRingtone()
        stopVibration()
        Log.d(TAG, "Call removed")
        currentCall.value = null
    }
}