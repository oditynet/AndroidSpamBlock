package com.example.blocktel1

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch

class MyInCallService : InCallService() {

    private var powerManager: PowerManager? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "MyInCallService"
        var currentCall = mutableStateOf<android.telecom.Call?>(null)
        private var instance: MyInCallService? = null

        private var mediaPlayer: android.media.MediaPlayer? = null
        private var vibrator: android.os.Vibrator? = null

        fun startVibration(context: android.content.Context) {
            try {
                val settings = loadSettings(context)
                if (!settings.vibrationEnabled) {
                    Log.d(TAG, "Вибрация отключена пользователем в настройках")
                    return
                }

                if (vibrator == null) {
                    vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }

                val pattern = longArrayOf(0, 1000, 1000)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val effect = android.os.VibrationEffect.createWaveform(pattern, 0)
                    val attributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                    vibrator?.vibrate(effect, attributes)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
                Log.d(TAG, "Вибрация успешно запущена в такт звонку")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска вибрации: ${e.message}")
            }
        }

        fun stopVibration() {
            try {
                vibrator?.let {
                    it.cancel()
                    Log.d(TAG, "Вибрация остановлена")
                }
                vibrator = null
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки вибрации: ${e.message}")
            }
        }
        fun startRingtone(context: android.content.Context) {
            try {
                if (mediaPlayer != null) return

                val ringtoneUri: android.net.Uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(
                    context,
                    android.media.RingtoneManager.TYPE_RINGTONE
                ) ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(context, ringtoneUri)
                    isLooping = true

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
                playFallbackRington(context)
            }
        }

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

        fun toggleSpeaker(turnOn: Boolean) {
            val route = if (turnOn) {
                android.telecom.CallAudioState.ROUTE_SPEAKER
            } else {
                android.telecom.CallAudioState.ROUTE_EARPIECE
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val serviceInstance = instance as? android.telecom.InCallService
                    if (serviceInstance != null) {
                        @Suppress("DEPRECATION")
                        serviceInstance.setAudioRoute(route)
                        Log.d(TAG, "Аудио-маршрут изменен на: $route")
                    } else {
                        Log.e(TAG, "Не удалось изменить маршрут: Инстанс сервиса пуст")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка переключения динамика: ${e.message}")
                }
            }
        }
    }
    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            when (state) {
                Call.STATE_ACTIVE -> {
                    turnOnProximitySensor()
                }
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                    turnOffProximitySensor()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            proximityWakeLock = powerManager?.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "BlockTel:ProximityScreenOff"
            )
            Log.d(TAG, "Датчик приближения успешно инициализирован")
        } else {
            Log.e(TAG, "Устройство не поддерживает PROXIMITY_SCREEN_OFF_WAKE_LOCK")
        }
    }

    override fun onDestroy() {
        turnOffProximitySensor()
        super.onDestroy()
        instance = null
    }

    private fun turnOnProximitySensor() {
        if (proximityWakeLock != null && !proximityWakeLock!!.isHeld) {
            proximityWakeLock!!.acquire()
            Log.d(TAG, "Датчик приближения АКТИВИРОВАН")
        }
    }

    private fun turnOffProximitySensor() {
        if (proximityWakeLock != null && proximityWakeLock!!.isHeld) {
            proximityWakeLock!!.release()
            Log.d(TAG, "Датчик приближения ДЕАКТИВИРОВАН")
        }
    }
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        call.registerCallback(callCallback)

        val state = call.state
        val isIncoming = (state == android.telecom.Call.STATE_RINGING)
        val isOutgoing = (state == android.telecom.Call.STATE_DIALING || state == android.telecom.Call.STATE_CONNECTING)

        if (!isIncoming && !isOutgoing) {
            Log.d(TAG, "Игнорируем триггер: вызов находится в промежуточном состоянии $state")
            return
        }

        if (isOutgoing) {
            Log.d(TAG, "Обнаружен исходящий вызов. Активируем датчик.")
            turnOnProximitySensor()

            currentCall.value = call
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            return
        }

        val rawPhoneNumber = call.details.handle?.schemeSpecificPart ?: ""
        val phoneNumber = android.net.Uri.decode(rawPhoneNumber)
        val contactName = getContactNameFromPhoneBook(this, phoneNumber)

        val settings = loadSettings(this)
        val blockedPatterns = loadBlockedPatterns(this)

        val shouldBlockLocally = shouldBlockCall(phoneNumber, contactName, blockedPatterns, settings, this)

        if (shouldBlockLocally) {
            Log.d(TAG, "Номер заблокирован локальным правилом приложения.")
            rejectCallSystem(call)
            return
        }

        if (contactName == null) {
            val androidId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown_device"

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val isSpamInCloud = BaserowClient.checkIsSpam(phoneNumber, androidId)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
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
            allowCallSystem(call)
        }
    }

    private fun rejectCallSystem(call: Call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            call.reject(false, "Blocked by Cloud Consensus")
        } else {
            call.disconnect()
        }
    }

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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        Log.d(TAG, "Отправляем интент запуска MainActivity поверх других приложений")
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callCallback)
        turnOffProximitySensor()

        super.onCallRemoved(call)
        stopRingtone()
        stopVibration()
        Log.d(TAG, "Call removed")
        currentCall.value = null
    }
}