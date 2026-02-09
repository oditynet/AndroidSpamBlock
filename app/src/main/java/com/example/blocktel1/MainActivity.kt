package com.example.blocktel1

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

// Модель данных для звонков
data class CallLog(
    val number: String,
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
    // Помечаем пользовательские паттерны
    val userPatterns = patterns.filter { it.startsWith("user_") }
    val otherPatterns = patterns.filterNot { it.startsWith("user_") }

    val editor = prefs.edit()
    editor.putStringSet("user_patterns", userPatterns.toSet())
    editor.putStringSet("other_patterns", otherPatterns.toSet())
    editor.apply()
}

fun loadBlockedPatterns(context: Context): List<String> {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
    val userPatterns = prefs.getStringSet("user_patterns", emptySet()) ?: emptySet()
    val otherPatterns = prefs.getStringSet("other_patterns", emptySet()) ?: emptySet()

    return (userPatterns + otherPatterns).toList()
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

                // Определяем тип звонка
                val typeText = when (callType) {
                    android.provider.CallLog.Calls.INCOMING_TYPE -> "📥 Входящий"
                    android.provider.CallLog.Calls.OUTGOING_TYPE -> "📤 Исходящий"
                    android.provider.CallLog.Calls.MISSED_TYPE -> "❌ Пропущенный"
                    android.provider.CallLog.Calls.REJECTED_TYPE -> "🚫 Отклоненный"
                    android.provider.CallLog.Calls.BLOCKED_TYPE -> "⛔ Заблокированный"
                    android.provider.CallLog.Calls.VOICEMAIL_TYPE -> "📩 Голосовая почта"
                    else -> "❓ Неизвестно"
                }

                // Форматируем номер
                val formattedNumber = formatPhoneNumber(number)

                // Проверяем, нужно ли блокировать с учетом контактов
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
                        name = name,
                        timestamp = date,
                        type = typeText,
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

            //Log.d("UpdatePatterns", "Начинаю загрузку с URL: $url")

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

                    // Разбиваем на строки и фильтруем
                    val lines = patternsText.lines()
                    Log.d("UpdatePatterns", "Всего строк в файле: ${lines.size}")

                    val newPatterns = lines
                        .filter { line ->
                            line.isNotBlank() && !line.trim().startsWith("#")
                        }
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                    Log.d("UpdatePatterns", "Обработано паттернов: ${newPatterns.size}")

                    // Добавляем только новые уникальные паттерны
                    var skippedCount = 0
                    for (pattern in newPatterns) {
                        val alreadyExists = currentPatterns.any {
                            val cleanPattern = if (it.startsWith("user_")) it.removePrefix("user_") else it
                            cleanPattern.equals(pattern, ignoreCase = true)
                        }

                        if (!alreadyExists) {
                            currentPatterns.add(pattern)
                            addedCount++
                        } else {
                            skippedCount++
                        }
                    }

                    // Сохраняем обновленный список
                    saveBlockedPatterns(context, currentPatterns)

                    statusMessage = when {
                        addedCount > 0 && skippedCount > 0 ->
                            "Добавлено $addedCount новых паттернов. $skippedCount уже существовали."
                        addedCount > 0 ->
                            "Успешно добавлено $addedCount новых паттернов!"
                        skippedCount > 0 ->
                            "Все паттерны уже есть в базе."
                        newPatterns.isEmpty() ->
                            "Файл с паттернами пуст."
                        else ->
                            "Не удалось добавить новые паттерны."
                    }

                } else {
                    statusMessage = "Ошибка сервера: $responseCode - $responseMessage"
                    Log.e("UpdatePatterns", "HTTP Error: $responseCode - $responseMessage")
                }

            } catch (e: java.net.SocketTimeoutException) {
                statusMessage = "Таймаут соединения"
                Log.e("UpdatePatterns", "SocketTimeoutException", e)

            } catch (e: java.net.UnknownHostException) {
                statusMessage = "Не удалось найти сервер"
                Log.e("UpdatePatterns", "UnknownHostException", e)

            } catch (e: java.net.MalformedURLException) {
                statusMessage = "Некорректный URL"
                Log.e("UpdatePatterns", "MalformedURLException", e)

            } catch (e: java.io.IOException) {
                statusMessage = "Ошибка ввода-вывода: ${e.message ?: "Unknown IO error"}"
                Log.e("UpdatePatterns", "IOException", e)

            } catch (e: SecurityException) {
                statusMessage = "Ошибка безопасности"
                Log.e("UpdatePatterns", "SecurityException", e)

            } catch (e: Exception) {
                statusMessage = "Неизвестная ошибка: ${e.javaClass.simpleName}"
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
                title = { Text("📞 Блокировщик звонков Pro") },
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
                    icon = { Icon(Icons.Default.Close, contentDescription = "Блокировки") },
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
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Обновить")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Основной экран с логами звонков
            CallHistoryScreen(
                callLogs = callLogs,
                isLoading = isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Статус приложения
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
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
    var downloadCount by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf("") }

    // Загружаем сохраненные паттерны
    LaunchedEffect(Unit) {
        blockedPatterns.clear()
        blockedPatterns.addAll(loadBlockedPatterns(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Управление блокировками",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Секция добавления нового паттерна
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Добавить свой паттерн:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newPattern,
                        onValueChange = { newPattern = it },
                        label = { Text("Текст или часть номера") },
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (newPattern.isNotBlank()) {
                                val pattern = "user_" + newPattern.trim()
                                if (!blockedPatterns.contains(pattern)) {
                                    blockedPatterns.add(pattern)
                                    saveBlockedPatterns(context, blockedPatterns)
                                    newPattern = ""
                                    showToast(context, "Паттерн добавлен!")
                                } else {
                                    showToast(context, "Такой паттерн уже существует")
                                }
                            } else {
                                showToast(context, "Введите текст для блокировки")
                            }
                        }
                    ) {
                        Text("Добавить")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Секция обновления паттернов
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Обновить базу паттернов",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Загрузит свежие паттерны из интернета. Ваши личные паттерны не будут удалены.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            statusMessage = ""
                            val (count, message) = updatePatternsFromInternet(context, blockedPatterns)
                            downloadCount = count
                            statusMessage = message
                            isLoading = false
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Загрузить обновления")
                }

                // Показываем статус загрузки
                if (statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (downloadCount > 0)
                                MaterialTheme.colorScheme.primaryContainer
                            else if (statusMessage.contains("Ошибка", ignoreCase = true))
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            if (downloadCount > 0) {
                                Text(
                                    text = "✅ Успешно!",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = statusMessage,
                                fontSize = 12.sp,
                                color = if (downloadCount > 0)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else if (statusMessage.contains("Ошибка", ignoreCase = true))
                                    MaterialTheme.colorScheme.onErrorContainer
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = if (downloadCount > 0) 4.dp else 0.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Список паттернов с информацией
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Список паттернов:",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Всего: ${blockedPatterns.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "${blockedPatterns.count { it.startsWith("user_") }} пользовательских",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${blockedPatterns.count { !it.startsWith("user_") }} из интернета",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (blockedPatterns.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Нет паттернов",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Нет добавленных паттернов",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Добавьте свои или загрузите из интернета",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                } else {
                    // Кнопка для просмотра статистики
                    Button(
                        onClick = {
                            val userPatterns = blockedPatterns.count { it.startsWith("user_") }
                            val internetPatterns = blockedPatterns.count { !it.startsWith("user_") }
                            showLongToast(context,
                                "Статистика:\n" +
                                "Всего паттернов: ${blockedPatterns.size}\n" +
                                "Пользовательских: $userPatterns\n" +
                                "Из интернета: $internetPatterns"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Статистика", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Показать статистику")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.height(250.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(blockedPatterns) { pattern ->
                            PatternItem(
                                pattern = pattern,
                                onDelete = {
                                    if (pattern.startsWith("user_")) {
                                        blockedPatterns.remove(pattern)
                                        saveBlockedPatterns(context, blockedPatterns)
                                        showToast(context, "Паттерн удален")
                                    } else {
                                        showToast(context, "Можно удалять только пользовательские паттерны")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PatternItem(pattern: String, onDelete: () -> Unit) {
    val isUserPattern = pattern.startsWith("user_")
    val displayPattern = if (isUserPattern) pattern.removePrefix("user_") else pattern

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(text = displayPattern)
                if (isUserPattern) {
                    Text(
                        text = "Пользовательский",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isUserPattern) {
                IconButton(
                    onClick = onDelete
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                }
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
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
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
                    steps = 9,
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
                modifier = Modifier.padding(16.dp)
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

        // Информация о приложении
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Информация:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "• Использует CallScreeningService для быстрой блокировки",
                    fontSize = 12.sp
                )
                Text(
                    text = "• Приоритетный BroadcastReceiver для раннего перехвата",
                    fontSize = 12.sp
                )
                Text(
                    text = "• Foreground Service для постоянной работы",
                    fontSize = 12.sp
                )
                Text(
                    text = "• Блокирует до того, как зазвонит телефон",
                    fontSize = 12.sp
                )
            }
        }
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
    isLoading: Boolean
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
                CallHistoryItem(call)
            }
        }
    }
}

@Composable
fun CallHistoryItem(call: CallLog) {
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
            modifier = Modifier.padding(16.dp)
        ) {
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

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = call.type,
                        fontSize = 11.sp,
                        color = if (call.shouldBlock)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = call.duration,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    if (call.shouldBlock) {
                        Text(
                            text = "Будет заблокирован",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = call.timestamp,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}