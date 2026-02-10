package com.example.blocktel1

import android.util.Base64
import java.nio.charset.StandardCharsets

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.blocktel1.ui.theme.BlockTel1Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

import android.telecom.TelecomManager
import androidx.compose.ui.text.style.TextOverflow

// Функция для декодирования base64
fun decodeBase64(encoded: String): String {
    return try {
        val decodedBytes = Base64.decode(encoded, Base64.DEFAULT)
        String(decodedBytes, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        Log.e("Base64", "Ошибка декодирования: ${e.message}")
        "" // Возвращаем пустую строку в случае ошибки
    }
}

// Функция для проверки строки на base64
fun isBase64(str: String): Boolean {
    return try {
        // Проверяем, можно ли декодировать строку
        Base64.decode(str, Base64.DEFAULT)
        // Проверяем, что строка содержит только допустимые символы
        str.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
    } catch (e: Exception) {
        false
    }
}

// Модель данных для звонков
data class CallLog(
    val number: String,           // Форматированный номер
    val cleanNumber: String,      // Очищенный номер (только цифры)
    val name: String?,
    val timestamp: String,
    val type: String,
    val duration: String = "",
    val shouldBlock: Boolean = false
)

// Модель настроек
data class AppSettings(
    val callLogLimit: Int = 20,
    val allowContacts: Boolean = false,
    val blockHiddenNumbers: Boolean = false,
    val blockInternational: Boolean = false
)

class MainActivity : ComponentActivity() {

    // Регистрируем запрос разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        permissionGranted.value = allGranted
        if (allGranted) {
            startCallBlockingService()
        }
    }

    private val permissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Проверяем разрешения при создании
        checkPermissions()

        setContent {
            BlockTel1Theme {
                CallMonitorApp(
                    permissionGranted = permissionGranted.value,
                    onRequestPermissions = { requestPermissions() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionGranted.value) {
            startCallBlockingService()
        }
    }

    private fun startCallBlockingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceIntent = Intent(this, CallBlockingService::class.java)
            startForegroundService(serviceIntent)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        permissionGranted.value = allGranted
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())
    }
}

// Функции для работы с настройками
fun saveSettings(context: Context, settings: AppSettings) {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.putInt("call_log_limit", settings.callLogLimit)
    editor.putBoolean("allow_contacts", settings.allowContacts)
    editor.putBoolean("block_hidden", settings.blockHiddenNumbers)
    editor.putBoolean("block_international", settings.blockInternational)
    editor.apply()
}

fun loadSettings(context: Context): AppSettings {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
    return AppSettings(
        callLogLimit = prefs.getInt("call_log_limit", 20),
        allowContacts = prefs.getBoolean("allow_contacts", false),
        blockHiddenNumbers = prefs.getBoolean("block_hidden", false),
        blockInternational = prefs.getBoolean("block_international", false)
    )
}

// Сохранение и загрузка паттернов
fun saveBlockedPatterns(context: Context, patterns: List<String>) {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)

    // Разделяем паттерны на пользовательские и интернет
    val userPatterns = patterns
        .filter { it.startsWith("user_") }
        .map {
            // Сохраняем пользовательские паттерны как есть
            it
        }
        .toMutableList()

    val internetPatterns = patterns
        .filterNot { it.startsWith("user_") }
        .map {
            // Для интернет-паттернов сохраняем уже очищенные версии
            it
        }
        .toMutableList()

    val editor = prefs.edit()

    // Сохраняем отдельно
    editor.putStringSet("user_patterns", userPatterns.toSet())
    editor.putStringSet("internet_patterns", internetPatterns.toSet())

    // Также сохраняем дату последнего обновления
    editor.putLong("last_update_time", System.currentTimeMillis())

    editor.apply()

    Log.d("SavePatterns", "Сохранено: ${userPatterns.size} пользовательских + ${internetPatterns.size} интернет паттернов")
}

fun loadBlockedPatterns(context: Context): List<String> {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)

    val userPatterns = prefs.getStringSet("user_patterns", emptySet()) ?: emptySet()
    val internetPatterns = prefs.getStringSet("internet_patterns", emptySet()) ?: emptySet()

    // Объединяем, пользовательские паттерны идут первыми
    val allPatterns = (userPatterns + internetPatterns).toMutableList()

    // Также можно отсортировать пользовательские паттерны первыми
    val sortedPatterns = allPatterns.sortedBy { !it.startsWith("user_") }

    Log.d("LoadPatterns", "Загружено: ${userPatterns.size} пользовательских + ${internetPatterns.size} интернет паттернов")

    return sortedPatterns
}


// Функция для форматирования номера телефона
fun formatPhoneNumber(number: String): String {
    val cleanNumber = number.replace(Regex("[^0-9+]"), "")

    return when {
        cleanNumber.startsWith("+7") && cleanNumber.length >= 12 -> {
            val last10 = cleanNumber.takeLast(10)
            "+7 ${last10.substring(0, 3)} ${last10.substring(3, 6)}-${last10.substring(6, 8)}-${last10.substring(8)}"
        }
        cleanNumber.startsWith("8") && cleanNumber.length >= 11 -> {
            val last10 = cleanNumber.takeLast(10)
            "+7 ${last10.substring(0, 3)} ${last10.substring(3, 6)}-${last10.substring(6, 8)}-${last10.substring(8)}"
        }
        cleanNumber.length >= 10 -> {
            val last10 = cleanNumber.takeLast(10)
            "+7 ${last10.substring(0, 3)} ${last10.substring(3, 6)}-${last10.substring(6, 8)}-${last10.substring(8)}"
        }
        else -> cleanNumber
    }
}

fun shouldBlockCall(
    number: String,
    name: String?,
    blockedPatterns: List<String>,
    settings: AppSettings,
    context: Context
): Boolean {
    // Очищаем номер от лишних символов
    val cleanNumber = number.replace(Regex("[^0-9+]"), "")

    // 1. ВЫСШИЙ ПРИОРИТЕТ: проверка по паттернам блокировки
    // Если номер или имя совпадают с паттерном - блокируем ВСЕГДА!
    if (blockedPatterns.isNotEmpty()) {
        val checkPatterns = blockedPatterns.map {
            if (it.startsWith("user_")) it.removePrefix("user_") else it
        }

        val hasBlockingPattern = checkPatterns.any { pattern ->
            pattern.isNotBlank() && (
                    cleanNumber.contains(pattern, ignoreCase = true) ||
                            (name?.contains(pattern, ignoreCase = true) == true)
                    )
        }

        // Если есть паттерн блокировки - ИГНОРИРУЕМ ВСЕ НАСТРОЙКИ и блокируем!
        if (hasBlockingPattern) {
            return true
        }
    }

    // 2. Проверка: является ли номер контактом
    val isContact = name != null && name != number

    // Если это контакт И включена настройка "разрешать контакты" - НЕ блокируем
    if (isContact && settings.allowContacts) {
        return false
    }

    // 3. Проверка на скрытые номера
    if (settings.blockHiddenNumbers && (number.isBlank() || !number.matches(Regex("^[0-9+\\s()-]*$")))) {
        return true
    }

    // 4. Проверка на международные номера
    if (settings.blockInternational && number.startsWith("+") && !number.startsWith("+7")) {
        return true
    }

    return false
}


// Функция для загрузки истории звонков с проверкой блокировки
fun loadCallHistory(context: Context, blockedPatterns: List<String>, limit: Int = 20): List<CallLog> {
    val callLogs = mutableListOf<CallLog>()
    val settings = loadSettings(context)

    try {
        // Проверяем разрешение на чтение журнала вызовов
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return callLogs
        }

        val cursor = context.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            null,
            null,
            null,
            "${android.provider.CallLog.Calls.DATE} DESC"
        )

        cursor?.use { c ->
            val numberIndex = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
            val nameIndex = c.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
            val dateIndex = c.getColumnIndex(android.provider.CallLog.Calls.DATE)
            val typeIndex = c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
            val durationIndex = c.getColumnIndex(android.provider.CallLog.Calls.DURATION)

            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

            var count = 0
            while (c.moveToNext() && count < limit) {
                val number = c.getString(numberIndex) ?: "Неизвестный номер"
                val name = c.getString(nameIndex)
                val dateLong = c.getLong(dateIndex)
                val callType = c.getInt(typeIndex)
                val duration = if (durationIndex != -1) c.getString(durationIndex) ?: "0" else "0"

                val date = if (dateLong > 0) {
                    dateFormat.format(Date(dateLong))
                } else {
                    "Неизвестно"
                }

                // Определяем тип звонка - ЭТО ДОБАВЛЯЕМ
                val typeText = when (callType) {
                    android.provider.CallLog.Calls.INCOMING_TYPE -> "📥 Входящий"
                    android.provider.CallLog.Calls.OUTGOING_TYPE -> "📤 Исходящий"
                    android.provider.CallLog.Calls.MISSED_TYPE -> "❌ Пропущенный"
                    android.provider.CallLog.Calls.REJECTED_TYPE -> "🚫 Отклоненный"
                    android.provider.CallLog.Calls.BLOCKED_TYPE -> "⛔ Заблокированный"
                    android.provider.CallLog.Calls.VOICEMAIL_TYPE -> "📩 Голосовая почта"
                    else -> "❓ Неизвестно"
                }

                // Форматируем номер для отображения
                val formattedNumber = formatPhoneNumber(number)

                // Получаем очищенный номер для паттернов
                val cleanNumber = number.replace(Regex("[^0-9+]"), "")

                // Проверяем, нужно ли блокировать
                val shouldBlock = shouldBlockCall(
                    formattedNumber,
                    name,
                    blockedPatterns,
                    settings,
                    context
                )

                // Форматируем продолжительность
                val durationText = if (duration.toIntOrNull() ?: 0 > 0) {
                    "${duration.toInt() / 60}:${String.format("%02d", duration.toInt() % 60)}"
                } else {
                    "0:00"
                }

                callLogs.add(
                    CallLog(
                        number = formattedNumber,
                        cleanNumber = cleanNumber,
                        name = name,
                        timestamp = date,
                        type = typeText,  // Используем typeText здесь
                        duration = durationText,
                        shouldBlock = shouldBlock
                    )
                )
                count++
            }
        }
    } catch (e: SecurityException) {
        android.util.Log.e("CallMonitor", "Нет разрешения на чтение журнала вызовов", e)
    } catch (e: Exception) {
        android.util.Log.e("CallMonitor", "Ошибка при загрузке истории звонков", e)
    }

    return callLogs
}

// Добавьте эту функцию если ее нет
@Suppress("DEPRECATION")
fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null &&
                (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    } else {
        @Suppress("DEPRECATION")
        val networkInfo = connectivityManager.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }
}

suspend fun updatePatternsFromInternet(context: Context, currentPatterns: MutableList<String>): Pair<Int, String> {
    var addedCount = 0
    var statusMessage = ""

    // Проверка сети в основном потоке
    if (!isNetworkAvailable(context)) {
        statusMessage = "❌ Нет подключения к интернету"
        withContext(Dispatchers.Main) {
            showToast(context, statusMessage)
        }
        return Pair(0, statusMessage)
    }

    // Показываем уведомление о начале загрузки в основном потоке
    withContext(Dispatchers.Main) {
        showToast(context, "Начинаю загрузку паттернов...")
    }

    return try {
        // ВСЕ сетевые операции выполняем в IO dispatcher
        withContext(Dispatchers.IO) {
            val url = "https://raw.githubusercontent.com/oditynet/AndroidSpamBlock/main/updatepattern.txt"

            Log.d("UpdatePatterns", "Начинаю загрузку с URL: $url")

            var connection: java.net.HttpURLConnection? = null

            try {
                val urlObj = java.net.URL(url)
                connection = urlObj.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.setRequestProperty("Accept", "text/plain")

                Log.d("UpdatePatterns", "Пытаюсь подключиться...")
                connection.connect()

                val responseCode = connection.responseCode
                val responseMessage = connection.responseMessage ?: "No message"

                Log.d("UpdatePatterns", "Response Code: $responseCode, Message: $responseMessage")

                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val patternsText = inputStream.bufferedReader().use { it.readText() }
                    inputStream.close()

                    Log.d("UpdatePatterns", "Получено данных: ${patternsText.length} символов")
                    Log.d("UpdatePatterns", "Первые 200 символов: ${patternsText.take(200)}")

                    // Разбиваем на строки и фильтруем
                    val lines = patternsText.lines()
                    Log.d("UpdatePatterns", "Всего строк в файле: ${lines.size}")

                    // Собираем очищенные паттерны
                    val newPatterns = mutableListOf<String>()

                    lines.forEachIndexed { index, line ->
                        val trimmedLine = line.trim()

                        // Пропускаем пустые строки и комментарии
                        if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                            // Проверяем, является ли строка base64
                            if (isBase64(trimmedLine)) {
                                // Декодируем base64
                                val decoded = decodeBase64(trimmedLine)
                                if (decoded.isNotBlank()) {
                                    Log.d("UpdatePatterns", "Строка $index: декодировано из base64: '$decoded'")

                                    // Очищаем декодированную строку
                                    val cleaned = decoded.replace(Regex("[^0-9a-zA-Z]"), "")
                                    if (cleaned.isNotBlank()) {
                                        newPatterns.add(cleaned)
                                        Log.d("UpdatePatterns", "  → Добавлен паттерн: '$cleaned'")
                                    }
                                } else {
                                    Log.d("UpdatePatterns", "Строка $index: не удалось декодировать base64")
                                }
                            } else {
                                // Если не base64, добавляем как есть (после очистки)
                                val cleaned = trimmedLine.replace(Regex("[^0-9a-zA-Z]"), "")
                                if (cleaned.isNotBlank()) {
                                    newPatterns.add(cleaned)
                                    Log.d("UpdatePatterns", "Строка $index: обычная строка: '$cleaned'")
                                }
                            }
                        }
                    }

                    Log.d("UpdatePatterns", "Найдено паттернов после обработки: ${newPatterns.size}")

                    // Проверяем, есть ли конкретный паттерн для тестирования
                    val testPattern = "4956406600"
                    if (newPatterns.contains(testPattern)) {
                        Log.d("UpdatePatterns", "✅ ПАТТЕРН $testPattern НАЙДЕН В ЗАГРУЖЕННЫХ!")
                    } else {
                        Log.d("UpdatePatterns", "⚠️ ПАТТЕРН $testPattern НЕ НАЙДЕН в загруженных")
                        Log.d("UpdatePatterns", "Первые 20 паттернов: ${newPatterns.take(20)}")
                    }

                    // НОВАЯ ЛОГИКА:
                    // 1. Сохраняем ТОЛЬКО пользовательские паттерны (с префиксом "user_")
                    val userPatterns = currentPatterns.filter { it.startsWith("user_") }.toMutableList()

                    // 2. Полностью очищаем список
                    currentPatterns.clear()

                    // 3. Добавляем обратно пользовательские паттерны
                    currentPatterns.addAll(userPatterns)

                    // 4. Добавляем ВСЕ новые паттерны из интернета
                    var internetPatternsCount = 0
                    for (pattern in newPatterns) {
                        // Проверяем, нет ли уже такого паттерна (включая пользовательские)
                        val alreadyExists = currentPatterns.any {
                            val cleanExisting = if (it.startsWith("user_")) it.removePrefix("user_") else it
                            cleanExisting.equals(pattern, ignoreCase = true)
                        }

                        if (!alreadyExists) {
                            currentPatterns.add(pattern)
                            addedCount++
                            internetPatternsCount++
                            Log.d("UpdatePatterns", "Добавлен паттерн: $pattern")
                        } else {
                            Log.d("UpdatePatterns", "Пропущен паттерн (уже существует): $pattern")
                        }
                    }

                    // Сохраняем обновленный список
                    saveBlockedPatterns(context, currentPatterns)

                    // Логируем результат
                    val userCount = userPatterns.size
                    val internetCount = currentPatterns.size - userCount

                    Log.d("UpdatePatterns", "Итог: Пользовательских: $userCount, Интернет: $internetCount")
                    Log.d("UpdatePatterns", "Добавлено новых: $addedCount")

                    statusMessage = when {
                        addedCount > 0 ->
                            "✅ Пользовательских: $userCount. Загружено: $addedCount новых"
                        newPatterns.isEmpty() ->
                            "⚠️ Файл с паттернами пуст или содержит только комментарии"
                        else ->
                            "ℹ️ Все паттерны уже актуальны. Пользовательских: $userCount. Интернет: $internetCount"
                    }

                    Log.d("UpdatePatterns", statusMessage)

                } else {
                    statusMessage = "❌ Ошибка сервера: $responseCode - $responseMessage"
                    Log.e("UpdatePatterns", "HTTP Error: $responseCode - $responseMessage")
                }

            } catch (e: java.net.SocketTimeoutException) {
                statusMessage = "⏱️ Таймаут соединения"
                Log.e("UpdatePatterns", "SocketTimeoutException", e)

            } catch (e: java.net.UnknownHostException) {
                statusMessage = "🌐 Не удалось найти сервер"
                Log.e("UpdatePatterns", "UnknownHostException", e)

            } catch (e: java.net.MalformedURLException) {
                statusMessage = "🔗 Некорректный URL"
                Log.e("UpdatePatterns", "MalformedURLException", e)

            } catch (e: java.io.IOException) {
                statusMessage = "📡 Ошибка сети: ${e.message ?: "Unknown IO error"}"
                Log.e("UpdatePatterns", "IOException", e)

            } catch (e: SecurityException) {
                statusMessage = "🔒 Ошибка безопасности"
                Log.e("UpdatePatterns", "SecurityException", e)

            } catch (e: Exception) {
                statusMessage = "❓ Неизвестная ошибка: ${e.javaClass.simpleName}"
                Log.e("UpdatePatterns", "Общая ошибка", e)

            } finally {
                connection?.disconnect()
            }

            Pair(addedCount, statusMessage)
        }

    } finally {
        // Показываем финальный toast в основном потоке
        if (statusMessage.isNotBlank()) {
            withContext(Dispatchers.Main) {
                showToast(context, statusMessage)
            }
        }
    }
}

// Функции для показа уведомлений
fun showToast(context: Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

fun showLongToast(context: Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}

// Композируемые функции
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallMonitorApp(
    permissionGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📞 Блокировщик звонков") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Главная") },
                    label = { Text("Главная") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Clear, contentDescription = "Блокировки") },
                    label = { Text("Блокировки") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                    label = { Text("Настройки") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> MainScreen(permissionGranted, onRequestPermissions)
                1 -> BlockingPatternsScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

// Функция для добавления номера в паттерны блокировки
fun addNumberToPatterns(context: Context, phoneNumber: String, blockedPatterns: MutableList<String>) {
    // Очищаем номер от форматирования, оставляем только цифры и плюс
    val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")

    if (cleanNumber.isBlank()) {
        showToast(context, "Не удалось извлечь номер")
        return
    }

    // Создаем паттерн с префиксом user_
    val pattern = "user_$cleanNumber"

    // Проверяем, нет ли уже такого паттерна
    val alreadyExists = blockedPatterns.any { existingPattern ->
        val cleanExisting = if (existingPattern.startsWith("user_"))
            existingPattern.removePrefix("user_")
        else
            existingPattern

        cleanExisting.equals(cleanNumber, ignoreCase = true)
    }

    if (!alreadyExists) {
        blockedPatterns.add(pattern)
        // Сохраняем обновленные паттерны
        saveBlockedPatterns(context, blockedPatterns)
        showToast(context, "Номер добавлен в паттерны блокировки")
        Log.d("AddToPatterns", "Добавлен номер: $cleanNumber")
    } else {
        showToast(context, "Этот номер уже есть в списке блокировок")
    }
}

@Composable
fun MainScreen(
    permissionGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    val callLogs = remember { mutableStateListOf<CallLog>() }
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Загружаем настройки
    val settings = loadSettings(context)
    val callLogLimit = remember { mutableStateOf(settings.callLogLimit) }
    val blockedPatterns = remember { mutableStateListOf<String>() }

    // Автоматически загружаем историю при получении разрешений
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            scope.launch {
                isLoading = true
                blockedPatterns.clear()
                blockedPatterns.addAll(loadBlockedPatterns(context))
                val history = loadCallHistory(context, blockedPatterns, callLogLimit.value)
                callLogs.clear()
                callLogs.addAll(history)
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!permissionGranted) {
            PermissionRequestScreen(onRequestPermissions = onRequestPermissions)
        } else {
            // Кнопка обновления
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "История звонков (${callLogs.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            blockedPatterns.clear()
                            blockedPatterns.addAll(loadBlockedPatterns(context))
                            val history = loadCallHistory(context, blockedPatterns, callLogLimit.value)
                            callLogs.clear()
                            callLogs.addAll(history)
                            isLoading = false
                        }
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Обновить")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Основной экран с логами звонков
            CallHistoryScreen(
                callLogs = callLogs,
                isLoading = isLoading,
                onAddToPatterns = { phoneNumber ->
                    val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
                    addNumberToPatterns(context, cleanNumber, blockedPatterns)

                    // Обновляем историю, чтобы показать новые блокировки
                    scope.launch {
                        val updatedHistory = loadCallHistory(context, blockedPatterns, callLogLimit.value)
                        callLogs.clear()
                        callLogs.addAll(updatedHistory)
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Статус приложения
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = "Статус: ${if (permissionGranted) "✅ Активен" else "❌ Неактивен"}",
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Паттернов для блокировки: ${blockedPatterns.size}",
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Лимит истории: ${callLogLimit.value} звонков",
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun BlockingPatternsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blockedPatterns = remember { mutableStateListOf<String>() }
    var newPattern by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var lastUpdateTime by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var patternToDelete by remember { mutableStateOf("") }

    // Загружаем сохраненные паттерны и время обновления
    LaunchedEffect(Unit) {
        blockedPatterns.clear()
        blockedPatterns.addAll(loadBlockedPatterns(context))

        // Получаем время последнего обновления
        val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
        val lastUpdate = prefs.getLong("last_update_time", 0)
        if (lastUpdate > 0) {
            val date = Date(lastUpdate)
            val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            lastUpdateTime = format.format(date)
        }
    }

    // Диалог подтверждения удаления
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удаление паттерна") },
            text = { Text("Вы уверены, что хотите удалить паттерн \"${patternToDelete.removePrefix("user_")}\"?") },
            confirmButton = {
                Button(
                    onClick = {
                        blockedPatterns.remove(patternToDelete)
                        saveBlockedPatterns(context, blockedPatterns)
                        showDeleteDialog = false
                        patternToDelete = ""
                        showToast(context, "Паттерн удален")
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        patternToDelete = ""
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .padding(16.dp)
    ) {
        // Заголовок
        Text(
            text = "Управление блокировками",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Секция 1: Добавление нового паттерна
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = "Добавить свой паттерн:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                /*Text(
                    text = "Паттерн - это часть номера или текста для блокировки",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 5.dp)
                )*/

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newPattern,
                        onValueChange = { newPattern = it },
                        label = { Text("") },
                        placeholder = { Text("Паттерн - это часть номера или текста для блокировки") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (newPattern.isNotBlank()) {
                                val cleanPattern = newPattern.trim()
                                val userPattern = "user_$cleanPattern"

                                // Проверяем, нет ли уже такого паттерна
                                val alreadyExists = blockedPatterns.any { pattern ->
                                    val cleanExisting = if (pattern.startsWith("user_")) pattern.removePrefix("user_") else pattern
                                    cleanExisting.equals(cleanPattern, ignoreCase = true)
                                }

                                if (!alreadyExists) {
                                    blockedPatterns.add(userPattern)
                                    saveBlockedPatterns(context, blockedPatterns)
                                    newPattern = ""
                                    showToast(context, "Паттерн добавлен!")
                                } else {
                                    showToast(context, "Такой паттерн уже существует")
                                }
                            } else {
                                showToast(context, "Введите текст для блокировки")
                            }
                        },
                        enabled = newPattern.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить", modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Добавить")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Секция 2: Обновление базы паттернов
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f)
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Обновление базы паттернов",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (lastUpdateTime.isNotEmpty()) {
                            Text(
                                text = "Последнее обновление: $lastUpdateTime",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    // Статистика паттернов
                    val userCount = blockedPatterns.count { it.startsWith("user_") }
                    val internetCount = blockedPatterns.size - userCount
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "Всего: ${blockedPatterns.size}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Ваши: $userCount • База: $internetCount",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                    // Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            statusMessage = ""
                            val (count, message) = updatePatternsFromInternet(context, blockedPatterns)

                            // Обновляем время последнего обновления
                            val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
                            val lastUpdate = prefs.getLong("last_update_time", 0)
                            if (lastUpdate > 0) {
                                val date = Date(lastUpdate)
                                val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                lastUpdateTime = format.format(date)
                            }

                            statusMessage = message
                            isLoading = false
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить", modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Загрузить обновления")
                }

                // Показываем статус загрузки
                if (statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                statusMessage.contains("✅") || statusMessage.contains("Обновлено") ->
                                    MaterialTheme.colorScheme.primaryContainer
                                statusMessage.contains("❌") || statusMessage.contains("Ошибка") ->
                                    MaterialTheme.colorScheme.errorContainer
                                else ->
                                    MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = when {
                                statusMessage.contains("✅") || statusMessage.contains("Обновлено") -> Icons.Default.CheckCircle
                                statusMessage.contains("❌") || statusMessage.contains("Ошибка") -> Icons.Default.Close
                                else -> Icons.Default.Info
                            }
                            val tint = when {
                                statusMessage.contains("✅") || statusMessage.contains("Обновлено") ->
                                    MaterialTheme.colorScheme.primary
                                statusMessage.contains("❌") || statusMessage.contains("Ошибка") ->
                                    MaterialTheme.colorScheme.error
                                else ->
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }

                            Icon(
                                icon,
                                contentDescription = "Статус",
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusMessage,
                                fontSize = 12.sp,
                                color = tint,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Секция 3: Список паттернов
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Список паттернов",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Кнопка очистки всех пользовательских паттернов
                    if (blockedPatterns.any { it.startsWith("user_") }) {
                        TextButton(
                            onClick = {
                                val userPatterns = blockedPatterns.filter { it.startsWith("user_") }
                                blockedPatterns.removeAll(userPatterns)
                                saveBlockedPatterns(context, blockedPatterns)
                                showToast(context, "Удалены все пользовательские паттерны")
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Очистить все", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Очистить мои", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (blockedPatterns.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Нет паттернов",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Нет добавленных паттернов",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Добавьте свои или загрузите из интернета",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                } else {
                    // Фильтр для отображения
                    var showOnlyUserPatterns by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = showOnlyUserPatterns,
                            onClick = { showOnlyUserPatterns = !showOnlyUserPatterns },
                            label = { Text("Только мои") },
                            leadingIcon = if (showOnlyUserPatterns) {
                                { Icon(Icons.Default.Check, contentDescription = "Выбрано", modifier = Modifier.size(16.dp)) }
                            } else null
                        )

                        Text(
                            text = "Показано: ${blockedPatterns.count { !showOnlyUserPatterns || it.startsWith("user_") }}/${blockedPatterns.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.height(250.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val filteredPatterns = if (showOnlyUserPatterns) {
                            blockedPatterns.filter { it.startsWith("user_") }
                        } else {
                            blockedPatterns
                        }

                        items(filteredPatterns) { pattern ->
                            PatternItem(
                                pattern = pattern,
                                isUserPattern = pattern.startsWith("user_"),
                                onDelete = {
                                    patternToDelete = pattern
                                    showDeleteDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PatternItem(
    pattern: String,
    isUserPattern: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayPattern = if (isUserPattern) pattern.removePrefix("user_") else pattern

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isUserPattern)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Иконка для типа паттерна
                if (isUserPattern) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Пользовательский",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Из интернета",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = displayPattern,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isUserPattern) {
                        Text(
                            text = "Мой паттерн",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "Из базы данных",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Кнопка удаления только для пользовательских паттернов
                //if (isUserPattern) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
               // }
            }
        }
    }
}


@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    // Загружаем текущие настройки
    val settings = remember { mutableStateOf(loadSettings(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Настройки блокировки",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                // Настройка лимита истории
                Text(
                    text = "Количество звонков в истории:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                var sliderValue by remember { mutableStateOf(settings.value.callLogLimit.toFloat()) }

                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 10f..100f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "${sliderValue.toInt()} звонков",
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Button(
                    onClick = {
                        settings.value = settings.value.copy(callLogLimit = sliderValue.toInt())
                        saveSettings(context, settings.value)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("Сохранить лимит")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                // Настройки блокировки
                Text(
                    text = "Параметры блокировки:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Разрешить входящие из контактов
                var allowContacts by remember { mutableStateOf(settings.value.allowContacts) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Разрешить звонки из контактов")
                    Switch(
                        checked = allowContacts,
                        onCheckedChange = {
                            allowContacts = it
                            settings.value = settings.value.copy(allowContacts = it)
                            saveSettings(context, settings.value)
                        }
                    )
                }

                // Блокировать скрытые номера
                var blockHidden by remember { mutableStateOf(settings.value.blockHiddenNumbers) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Блокировать скрытые номера")
                    Switch(
                        checked = blockHidden,
                        onCheckedChange = {
                            blockHidden = it
                            settings.value = settings.value.copy(blockHiddenNumbers = it)
                            saveSettings(context, settings.value)
                        }
                    )
                }

                // Блокировать международные номера
                var blockInternational by remember { mutableStateOf(settings.value.blockInternational) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Блокировать международные звонки")
                    Switch(
                        checked = blockInternational,
                        onCheckedChange = {
                            blockInternational = it
                            settings.value = settings.value.copy(blockInternational = it)
                            saveSettings(context, settings.value)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

    }
}

@Composable
fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Необходимые разрешения:",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Column(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            PermissionItem("📇 Чтение контактов")
            PermissionItem("📞 Чтение состояния телефона")
            PermissionItem("📋 Чтение журнала вызовов")
            PermissionItem("📲 Ответ на входящие звонки")
            PermissionItem("🌐 Доступ в интернет")
            PermissionItem("🔔 Показ уведомлений")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Check, contentDescription = "Предоставить")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Предоставить все разрешения")
        }
    }
}

@Composable
fun PermissionItem(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
fun CallHistoryScreen(
    callLogs: List<CallLog>,
    isLoading: Boolean,
    onAddToPatterns: (String) -> Unit
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Загрузка истории...")
            }
        }
    } else if (callLogs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Нет записей о звонках",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(callLogs) { call ->
                CallHistoryItem(
                    call = call,
                    onAddToPatterns = {
                        // Используем cleanNumber для добавления в паттерны
                        if (call.cleanNumber.isNotBlank()) {
                            onAddToPatterns(call.cleanNumber)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun CallHistoryItem(
    call: CallLog,
    onAddToPatterns: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (call.shouldBlock)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            // Первая строка: информация о звонке и кнопка блокировки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = call.name ?: "Неизвестный абонент",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )

                    if (call.name != null && call.name != call.number) {
                        Text(
                            text = call.number,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // Кнопка добавления в паттерны
                IconButton(
                    onClick = onAddToPatterns,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.AddCircle,
                        contentDescription = "Заблокировать номер",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Вторая строка: тип звонка, продолжительность и статус блокировки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Левый блок: тип и продолжительность
                Column {
                    Text(
                        text = call.type,
                        fontSize = 12.sp,
                        color = if (call.shouldBlock)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )

                    if (call.duration.isNotEmpty() && call.duration != "0:00") {
                        Text(
                            text = "Длительность: ${call.duration}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // Правый блок: дата и статус блокировки
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    // Дата и время
                    Text(
                        text = call.timestamp,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )

                    // Статус блокировки (если применимо)
                    if (call.shouldBlock) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Заблокировано",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Будет заблокирован",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}