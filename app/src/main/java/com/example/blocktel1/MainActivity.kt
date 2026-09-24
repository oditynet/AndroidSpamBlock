package com.example.blocktel1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.blocktel1.ui.theme.BlockTel1Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults

import androidx.compose.material.icons.filled.Check

// Модель для хранения информации о SIM-карте
data class SimCardInfo(
    val id: Int,
    val carrierName: String,
    val phoneNumber: String?,
    val slotIndex: Int
)

// Функция для получения списка активных SIM-карт
@SuppressLint("MissingPermission") // Добавьте эту строку
fun getActiveSimCards(context: Context): List<SimCardInfo> {
    val simList = mutableListOf<SimCardInfo>()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        != PackageManager.PERMISSION_GRANTED) {
        return simList
    }

    val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    val activeSubscriptions: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList

    activeSubscriptions?.forEach { info ->
        simList.add(
            SimCardInfo(
                id = info.subscriptionId,
                carrierName = info.carrierName.toString(),
                phoneNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try { subscriptionManager.getPhoneNumber(info.subscriptionId) } catch (e: Exception) { null }
                } else {
                    @Suppress("DEPRECATION") info.number
                },
                slotIndex = info.simSlotIndex
            )
        )
    }
    return simList
}
// Функция для декодирования base64
fun decodeBase64(encoded: String): String {
    return try {
        val decodedBytes = Base64.decode(encoded, Base64.DEFAULT)
        String(decodedBytes, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        Log.e("Base64", "Ошибка декодирования: ${e.message}")
        ""
    }
}

// Функция для проверки строки на base64
fun isBase64(str: String): Boolean {
    return try {
        Base64.decode(str, Base64.DEFAULT)
        str.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
    } catch (e: Exception) {
        false
    }
}

// Модель данных для звонков
data class CallLog(
    val number: String,
    val cleanNumber: String,
    val name: String?,
    val timestamp: String,
    val type: String,
    val duration: String = "",
    val shouldBlock: Boolean = false
)

// Модель контакта
data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null
)

// Модель настроек
data class AppSettings(
    val callLogLimit: Int = 50,
    val allowContacts: Boolean = false,
    val blockHiddenNumbers: Boolean = false,
    val blockInternational: Boolean = false,
    val isDefaultDialer: Boolean = false
)

class MainActivity : ComponentActivity() {

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
    private val isDefaultDialerState = mutableStateOf(false)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkPermissions()
        checkDefaultDialer()

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
        checkDefaultDialer()
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
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CALL_PHONE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

        permissionGranted.value = allGranted
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CALL_PHONE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkDefaultDialer() {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val isDefault = packageName == telecomManager.defaultDialerPackage
        isDefaultDialerState.value = isDefault
        val settings = loadSettings(this)
        if (settings.isDefaultDialer != isDefault) {
            saveSettings(this, settings.copy(isDefaultDialer = isDefault))
        }

        Log.d("MainActivity", "Is default dialer: $isDefault")
    }

    companion object {
        const val REQUEST_CODE_SET_DEFAULT_DIALER = 1001
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
    editor.putBoolean("is_default_dialer", settings.isDefaultDialer)
    editor.apply()
}

fun loadSettings(context: Context): AppSettings {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
    return AppSettings(
        callLogLimit = prefs.getInt("call_log_limit", 50),
        allowContacts = prefs.getBoolean("allow_contacts", false),
        blockHiddenNumbers = prefs.getBoolean("block_hidden", false),
        blockInternational = prefs.getBoolean("block_international", false),
        isDefaultDialer = prefs.getBoolean("is_default_dialer", false)
    )
}

// Сохранение и загрузка паттернов
fun saveBlockedPatterns(context: Context, patterns: List<String>) {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
    val userPatterns = patterns.filter { it.startsWith("user_") }.toSet()
    val internetPatterns = patterns.filterNot { it.startsWith("user_") }.toSet()

    prefs.edit()
        .putStringSet("user_patterns", userPatterns)
        .putStringSet("internet_patterns", internetPatterns)
        .putLong("last_update_time", System.currentTimeMillis())
        .apply()

    Log.d("SavePatterns", "Сохранено: ${userPatterns.size} пользовательских + ${internetPatterns.size} интернет")
}

fun loadBlockedPatterns(context: Context): List<String> {
    val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
    val userPatterns = prefs.getStringSet("user_patterns", emptySet()) ?: emptySet()
    val internetPatterns = prefs.getStringSet("internet_patterns", emptySet()) ?: emptySet()
    return (userPatterns + internetPatterns).sortedBy { !it.startsWith("user_") }
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

// Функция определения блокировки
fun shouldBlockCall(
    number: String,
    name: String?,
    blockedPatterns: List<String>,
    settings: AppSettings,
    context: Context
): Boolean {
    val cleanNumber = number.replace(Regex("[^0-9+]"), "")

    // 1. Проверка по паттернам
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

        if (hasBlockingPattern) return true
    }

    // 2. Проверка: является ли номер контактом
    val isContact = name != null && name != number

    if (isContact && settings.allowContacts) {
        return false
    }

    // 3. Проверка на скрытые номера
    if (settings.blockHiddenNumbers && (number.isBlank() || number == "Неизвестный номер")) {
        return true
    }

    // 4. Проверка на международные номера
    if (settings.blockInternational && number.startsWith("+") && !number.startsWith("+7")) {
        return true
    }

    return false
}

// Загрузка истории звонков
fun loadCallHistory(context: Context, blockedPatterns: List<String>, limit: Int = 50): List<CallLog> {
    val callLogs = mutableListOf<CallLog>()
    val settings = loadSettings(context)

    try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
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
                val cachedName = c.getString(nameIndex)
                val dateLong = c.getLong(dateIndex)
                val callType = c.getInt(typeIndex)
                val duration = if (durationIndex != -1) c.getString(durationIndex) ?: "0" else "0"

                val date = if (dateLong > 0) dateFormat.format(Date(dateLong)) else "Неизвестно"

                val typeText = when (callType) {
                    android.provider.CallLog.Calls.INCOMING_TYPE -> "📥 Входящий"
                    android.provider.CallLog.Calls.OUTGOING_TYPE -> "📤 Исходящий"
                    android.provider.CallLog.Calls.MISSED_TYPE -> "❌ Пропущенный"
                    android.provider.CallLog.Calls.REJECTED_TYPE -> "🚫 Отклоненный"
                    android.provider.CallLog.Calls.BLOCKED_TYPE -> "⛔ Заблокированный"
                    else -> "❓ Неизвестно"
                }

                val formattedNumber = formatPhoneNumber(number)
                val cleanNumber = number.replace(Regex("[^0-9+]"), "")

                var displayName = cachedName
                if (displayName.isNullOrBlank() && cleanNumber.isNotBlank()) {
                    displayName = getContactNameFromPhoneBook(context, cleanNumber)
                }

                val shouldBlock = shouldBlockCall(
                    formattedNumber, displayName, blockedPatterns, settings, context
                )

                val durationText = if (duration.toIntOrNull() ?: 0 > 0) {
                    "${duration.toInt() / 60}:${String.format("%02d", duration.toInt() % 60)}"
                } else "0:00"

                callLogs.add(CallLog(
                    number = formattedNumber,
                    cleanNumber = cleanNumber,
                    name = displayName,
                    timestamp = date,
                    type = typeText,
                    duration = durationText,
                    shouldBlock = shouldBlock
                ))
                count++
            }
        }
    } catch (e: Exception) {
        Log.e("CallMonitor", "Ошибка загрузки истории", e)
    }

    return callLogs
}

// Получение имени из телефонной книги
fun getContactNameFromPhoneBook(context: Context, phoneNumber: String): String? {
    if (phoneNumber.isEmpty()) return null

    return try {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")

        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(cleanNumber)
            .build()

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex != -1) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        null
    } catch (e: Exception) {
        Log.e("CallMonitor", "Ошибка получения имени", e)
        null
    }
}

// Загрузка контактов
fun loadContacts(context: Context, searchQuery: String = ""): List<Contact> {
    val contacts = mutableListOf<Contact>()

    try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return contacts
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        val selection = if (searchQuery.isNotBlank()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        } else null

        val selectionArgs = if (searchQuery.isNotBlank()) {
            arrayOf("%$searchQuery%", "%$searchQuery%")
        } else null

        val cursor = context.contentResolver.query(
            uri, projection, selection, selectionArgs,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        cursor?.use { c ->
            val idIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

            while (c.moveToNext()) {
                val id = c.getString(idIndex)
                val name = c.getString(nameIndex) ?: "Без имени"
                val number = c.getString(numberIndex) ?: ""
                val photoUri = if (photoIndex != -1) c.getString(photoIndex) else null

                if (number.isNotBlank()) {
                    contacts.add(Contact(
                        id = id,
                        name = name,
                        phoneNumber = formatPhoneNumber(number),
                        photoUri = photoUri
                    ))
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Contacts", "Ошибка загрузки контактов", e)
    }

    return contacts
}

// Запрос на статус по умолчанию
fun requestDefaultDialer(context: Context) {
    val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

// Открыть настройки приложений по умолчанию
fun openPhoneAppSettings(context: Context) {
    try {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:" + context.packageName)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("Settings", "Cannot open settings", e)
            android.widget.Toast.makeText(
                context,
                "Не удалось открыть настройки",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}

// Функция для обновления паттернов из интернета
suspend fun updatePatternsFromInternet(context: Context, currentPatterns: MutableList<String>): Pair<Int, String> {
    var addedCount = 0
    var statusMessage = ""

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null && (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }

    withContext(Dispatchers.Main) {
        android.widget.Toast.makeText(context, "Начинаю загрузку паттернов...", android.widget.Toast.LENGTH_SHORT).show()
    }

    return try {
        withContext(Dispatchers.IO) {
            if (!isNetworkAvailable(context)) {
                return@withContext Pair(0, "❌ Нет интернета")
            }

            val url = "https://raw.githubusercontent.com/oditynet/AndroidSpamBlock/main/updatepattern.txt"
            var connection: java.net.HttpURLConnection? = null

            try {
                val urlObj = java.net.URL(url)
                connection = urlObj.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val patternsText = connection.inputStream.bufferedReader().use { it.readText() }
                    val lines = patternsText.lines()
                    val newPatterns = mutableListOf<String>()

                    lines.forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                            if (isBase64(trimmedLine)) {
                                val decoded = decodeBase64(trimmedLine)
                                if (decoded.isNotBlank()) {
                                    val cleaned = decoded.replace(Regex("[^0-9a-zA-Z]"), "")
                                    if (cleaned.isNotBlank()) newPatterns.add(cleaned)
                                }
                            } else {
                                val cleaned = trimmedLine.replace(Regex("[^0-9a-zA-Z]"), "")
                                if (cleaned.isNotBlank()) newPatterns.add(cleaned)
                            }
                        }
                    }

                    val userPatterns = currentPatterns.filter { it.startsWith("user_") }.toMutableList()
                    currentPatterns.clear()
                    currentPatterns.addAll(userPatterns)

                    for (pattern in newPatterns) {
                        val alreadyExists = currentPatterns.any {
                            val cleanExisting = if (it.startsWith("user_")) it.removePrefix("user_") else it
                            cleanExisting.equals(pattern, ignoreCase = true)
                        }
                        if (!alreadyExists) {
                            currentPatterns.add(pattern)
                            addedCount++
                        }
                    }

                    saveBlockedPatterns(context, currentPatterns)

                    statusMessage = if (addedCount > 0) {
                        "✅ Добавлено $addedCount новых паттернов"
                    } else {
                        "ℹ️ Все паттерны уже актуальны"
                    }

                } else {
                    statusMessage = "❌ Ошибка сервера: ${connection.responseCode}"
                }
            } catch (e: Exception) {
                statusMessage = "❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}"
            } finally {
                connection?.disconnect()
            }

            Pair(addedCount, statusMessage)
        }
    } finally {
        withContext(Dispatchers.Main) {
            if (statusMessage.isNotBlank()) {
                android.widget.Toast.makeText(context, statusMessage, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

// Главный экран приложения
@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun CallMonitorApp(
    permissionGranted: Boolean = true, // Добавили = true
    isDefaultDialer: Boolean = false, // Передаем статус сюда
    onRequestPermissions: () -> Unit = {} // Добавили = {}
) {

    // Наблюдаем за появлением звонков из нашего сервиса
    val activeCall by MyInCallService.currentCall

    // Если есть активный вызов — перекрываем весь интерфейс экраном звонка
    if (activeCall != null) {
        ActiveCallScreen(call = activeCall!!) {
            MyInCallService.currentCall.value = null
        }
    } else {

        var selectedTab by remember { mutableStateOf(0) }
        val context = LocalContext.current
        val settings = remember { mutableStateOf(loadSettings(context)) }

        LaunchedEffect(Unit) {
            settings.value = loadSettings(context)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("📞 Телефон")
                            if (settings.value.isDefaultDialer) {
                                Text(
                                    text = "версия 0.3.1",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Звонки") },
                        label = { Text("Звонки") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Face, contentDescription = "Контакты") },
                        label = { Text("Контакты") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Menu, contentDescription = "История") },
                        label = { Text("История") },
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Clear, contentDescription = "Блокировки") },
                        label = { Text("Блокировки") },
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                        label = { Text("Настройки") },
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (selectedTab) {
                    0 -> DialerScreen(permissionGranted,   onRequestPermissions)
                    1 -> ContactsScreen()
                    2 -> CallHistoryScreen()
                    3 -> BlockingPatternsScreen()
                    4 -> SettingsScreen()
                }
            }
        }
    }
}

// Экран набора номера
@Composable
fun DialerScreen(
    permissionGranted: Boolean,
   // isDefault: Boolean,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Contact>>(emptyList()) }
    val focusRequester = remember { FocusRequester() }

    // Переменные для поддержки нескольких SIM-карт
    val simCards = remember { mutableStateOf(listOf<SimCardInfo>()) }
    var showSimDialog by remember { mutableStateOf(false) }

    // Храним выбранную SIM-карту (null означает "SIM по умолчанию")
    var selectedSim by remember { mutableStateOf<SimCardInfo?>(null) }
    // Управление показом выпадающего меню
    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = loadSettings(context)
        isDefault = settings.isDefaultDialer
    }

    // Загрузка активных SIM-карт
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            val cards = getActiveSimCards(context)
            simCards.value = cards
            // Автоматически выбираем первую SIM-карту, если они доступны
            if (cards.isNotEmpty() && selectedSim == null) {
                selectedSim = cards.first()
            }
        }
    }

    LaunchedEffect(phoneNumber) {
        if (phoneNumber.length >= 2) {
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            suggestions = loadContacts(context, cleanNumber).take(3)
        } else {
            suggestions = emptyList()
        }
    }

    LaunchedEffect(phoneNumber) {
        if (phoneNumber.length >= 2) {
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            suggestions = loadContacts(context, cleanNumber).take(3)
        } else {
            suggestions = emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (!permissionGranted) {
            PermissionRequestScreen(onRequestPermissions)
        } else if (!isDefault) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⚠️ Приложение не является приложением по умолчанию",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Для совершения звонков установите приложение по умолчанию",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { openPhoneAppSettings(context) }
                    ) {
                        Text("Открыть настройки")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

        // Поле ввода номера с кнопкой удаления
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Поле ввода со скругленными углами
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { focusRequester.requestFocus() }
            ) {
                BasicTextField(
                    value = phoneNumber,
                    onValueChange = {
                        phoneNumber = it.filter { char ->
                            char.isDigit() || char == '+' || char == '*' || char == '#'
                        }
                    },
                    readOnly = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    decorationBox = { innerTextField ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            if (phoneNumber.isEmpty()) {
                                Text(
                                    text = "Введите номер",
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Кнопка удаления справа от поля ввода
            Button(
                onClick = {
                    if (phoneNumber.isNotEmpty()) {
                        phoneNumber = phoneNumber.dropLast(1)
                    }
                },
                modifier = Modifier
                    .height(64.dp)
                    .width(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                ),
                enabled = phoneNumber.isNotEmpty()
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Удалить",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Подсказки контактов
        if (suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(suggestions) { contact ->
                    SuggestionChip(
                        contact = contact,
                        onClick = {
                            val cleanNumber = contact.phoneNumber.replace(Regex("[^0-9+]"), "")
                            phoneNumber = cleanNumber
                            suggestions = emptyList()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Кнопки набора
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Первый ряд: 1 2 3
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DialerButton(
                    digit = "1",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "1" }
                )
                DialerButton(
                    digit = "2",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "2" }
                )
                DialerButton(
                    digit = "3",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "3" }
                )
            }

            // Второй ряд: 4 5 6
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DialerButton(
                    digit = "4",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "4" }
                )
                DialerButton(
                    digit = "5",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "5" }
                )
                DialerButton(
                    digit = "6",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "6" }
                )
            }

            // Третий ряд: 7 8 9
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DialerButton(
                    digit = "7",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "7" }
                )
                DialerButton(
                    digit = "8",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "8" }
                )
                DialerButton(
                    digit = "9",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "9" }
                )
            }

            // Четвертый ряд: * 0 #
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Кнопка *
                DialerButton(
                    digit = "*",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "*" }
                )

                // Кнопка 0 с долгим нажатием
                ZeroButton(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "0" },
                    onLongPress = { phoneNumber += "+" }
                )

                // Кнопка #
                DialerButton(
                    digit = "#",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { phoneNumber += "#" }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box {
    IconButton(
        onClick = { if (simCards.value.size > 1) dropdownExpanded = true },
        modifier = Modifier
            .height(64.dp)
            .width(56.dp)
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Call, contentDescription = "Выбор SIM")
            Text(
                text = selectedSim?.let { "SIM ${it.slotIndex + 1}" } ?: "SIM",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // Всплывающее меню выбора оператора
    DropdownMenu(
        expanded = dropdownExpanded,
        onDismissRequest = { dropdownExpanded = false }
    ) {
        simCards.value.forEach { sim ->
            DropdownMenuItem(
                text = { Text("Слот ${sim.slotIndex + 1}: ${sim.carrierName}") },
                onClick = {
                    selectedSim = sim
                    dropdownExpanded = false
                }
            )
        }
    }
}

        // Кнопка звонка
        Button(
            onClick = {
                if (phoneNumber.isNotBlank()) {
                    try {
                        val cleanNumber = phoneNumber.replace(Regex("[^0-9*#+]"), "")
                        if (cleanNumber.isNotBlank()) {

                            // 1. Проверяем, является ли номер USSD-запросом (содержит * или #)
                            if (cleanNumber.contains("*") || cleanNumber.contains("#")) {
                                val encodedNumber = cleanNumber.replace("#", Uri.encode("#"))
                                val intent = Intent(Intent.ACTION_CALL).apply {
                                    data = Uri.parse("tel:$encodedNumber")
                                }

                                // Жестко привязываем слот SIM для USSD через разные форматы прошивок
                                selectedSim?.let { sim ->
                                    intent.putExtra("simSlot", sim.slotIndex)
                                    intent.putExtra("com.android.phone.extra.slot", sim.slotIndex)
                                    intent.putExtra("phone", sim.slotIndex)
                                    intent.putExtra("slot", sim.slotIndex)

                                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                                    val callCapableAccounts = telecomManager.getCallCapablePhoneAccounts()
                                    val matchedHandle = callCapableAccounts.find { it.id.contains(sim.id.toString()) || it.id.contains(sim.slotIndex.toString()) }
                                    if (matchedHandle != null) {
                                        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, matchedHandle)
                                    }
                                }
                                context.startActivity(intent)

                                // 2. Для обычных исходящих звонков через TelecomManager
                            } else {
                                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                                val uri = Uri.fromParts("tel", cleanNumber, null)
                                val extras = Bundle()

                                selectedSim?.let { sim ->
                                    val callCapableAccounts = telecomManager.getCallCapablePhoneAccounts()

                                    // Улучшенный поиск аккаунта: проверяем вхождение как по ID подписки, так и по индексу слота
                                    val matchedHandle = callCapableAccounts.find { handle ->
                                        handle.id.contains(sim.id.toString()) ||
                                                handle.id.contains(sim.slotIndex.toString())
                                    }

                                    if (matchedHandle != null) {
                                        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, matchedHandle)
                                    }

                                    // Дублируем скрытые флаги слотов для вендорных прошивок (MIUI, OneUI)
                                    extras.putInt("com.android.phone.extra.slot", sim.slotIndex)
                                    extras.putInt("simSlot", sim.slotIndex)
                                    extras.putInt("android.telecom.extra.START_CALL_WITH_KEYPAD", 1)
                                }

                                telecomManager.placeCall(uri, extras)
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e("Dialer", "Security error: ${e.message}")
                        android.widget.Toast.makeText(context, "Нет разрешения на звонки", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("Dialer", "Error: ${e.message}")
                        android.widget.Toast.makeText(context, "Ошибка при звонке", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = phoneNumber.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
            )
        ) {
            Icon(Icons.Default.Call, contentDescription = "Позвонить")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Позвонить", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DialerButton(
    digit: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(digit, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ZeroButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongPress()
                    true
                },
                onLongClickLabel = "Ввести +"
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("0", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "+",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(y = (-4).dp)
            )
        }
    }
}

@Composable
fun SuggestionChip(
    contact: Contact,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = {
            Column {
                Text(
                    text = contact.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = contact.phoneNumber,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        },
        leadingIcon = {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.wrapContentWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    )
}

// Экран контактов
@Composable
fun ContactsScreen() {
    val context = LocalContext.current
    val contacts = remember { mutableStateListOf<Contact>() }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        isLoading = true
        contacts.clear()
        contacts.addAll(loadContacts(context, searchQuery))
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Поиск контактов") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Контакты не найдены")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(contacts) { contact ->
                    ContactItem(
                        contact = contact,
                        onCallClick = {
                            try {
                                val cleanNumber = contact.phoneNumber.replace(Regex("[^0-9+]"), "")
                                if (cleanNumber.isNotBlank()) {
                                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                                    val uri = Uri.fromParts("tel", cleanNumber, null)
                                    val extras = Bundle()
                                    telecomManager.placeCall(uri, extras)
                                }
                            } catch (e: SecurityException) {
                                Log.e("Contacts", "Security error: ${e.message}")
                                android.widget.Toast.makeText(
                                    context,
                                    "Нет разрешения на звонки",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Log.e("Contacts", "Error: ${e.message}")
                                android.widget.Toast.makeText(
                                    context,
                                    "Ошибка при звонке",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ContactItem(
    contact: Contact,
    onCallClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = contact.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = contact.phoneNumber,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            IconButton(
                onClick = onCallClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Call, contentDescription = "Позвонить")
            }
        }
    }
}

// Экран истории звонков
@Composable
fun CallHistoryScreen() {
    val context = LocalContext.current
    val callLogs = remember { mutableStateListOf<CallLog>() }
    var isLoading by remember { mutableStateOf(false) }
    val blockedPatterns = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        isLoading = true
        blockedPatterns.clear()
        blockedPatterns.addAll(loadBlockedPatterns(context))
        callLogs.clear()
        callLogs.addAll(loadCallHistory(context, blockedPatterns))
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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
                    isLoading = true
                    blockedPatterns.clear()
                    blockedPatterns.addAll(loadBlockedPatterns(context))
                    callLogs.clear()
                    callLogs.addAll(loadCallHistory(context, blockedPatterns))
                    isLoading = false
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (callLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("История звонков пуста")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(callLogs) { call ->
                    CallHistoryItem(
                        call = call,
                        onAddToPatterns = {
                            if (call.cleanNumber.isNotBlank()) {
                                val userPattern = "user_${call.cleanNumber}"
                                if (!blockedPatterns.contains(userPattern)) {
                                    blockedPatterns.add(userPattern)
                                    saveBlockedPatterns(context, blockedPatterns)
                                    android.widget.Toast.makeText(
                                        context,
                                        "Номер добавлен в блокировку",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun CallHistoryItem(
    call: CallLog,
    onAddToPatterns: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    val displayName = when {
                        !call.name.isNullOrBlank() && call.name != call.number -> call.name
                        else -> "Неизвестный абонент"
                    }

                    Text(
                        text = displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (!call.number.contains("Неизвестный")) {
                        Text(
                            text = call.number,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                if (!call.cleanNumber.isNullOrBlank()) {
                    IconButton(
                        onClick = onAddToPatterns,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.AddCircle,
                            contentDescription = "Заблокировать",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = call.type,
                        fontSize = 12.sp,
                        color = when {
                            call.type.contains("Пропущенный") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    if (call.duration != "0:00") {
                        Text(
                            text = "⏱️ ${call.duration}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = call.timestamp,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )

                    if (call.shouldBlock) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
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
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

// Экран блокировки
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
    var searchPatternQuery by remember { mutableStateOf("") }

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
                        //showToast(context, "Паттерн удален")
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
                                   // showToast(context, "Паттерн добавлен!")
                                }
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
                            val localCopy = blockedPatterns.toList().toMutableList()
                            val (count, message) = updatePatternsFromInternet(context, localCopy)

                            // 2. ИСПРАВЛЕНИЕ: Мгновенно подменяем данные на главном потоке после скачивания
                            blockedPatterns.clear()
                            blockedPatterns.addAll(localCopy)

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
                               // showToast(context, "Удалены все пользовательские паттерны")
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

                        // ТЕКСТОВОЕ ПОЛЕ ДЛЯ ЖИВОГО ПОИСКА СПРАВА ОТ КНОПКИ
                        TextField(
                            value = searchPatternQuery,
                            onValueChange = { searchPatternQuery = it },
                            placeholder = { Text("Поиск...", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Лупа", modifier = Modifier.size(16.dp)) },
                            trailingIcon = {
                                if (searchPatternQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchPatternQuery = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Сброс", modifier = Modifier.size(14.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f) // Занимает всё оставшееся пространство справа
                                .height(48.dp) // Компактная высота под размер чипа
                        )

                        Text(
                            text = "Показано: ${blockedPatterns.count { !showOnlyUserPatterns || it.startsWith("user_") }}/${blockedPatterns.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 1. ВЫНОСИМ ПОИСК И ФИЛЬТР В КЭШ (пишется ПЕРЕД LazyColumn)
                    val finalFilteredList by remember(searchPatternQuery, showOnlyUserPatterns, blockedPatterns.size) {
                        derivedStateOf {
                            blockedPatterns.filter { pattern ->
                                // Фильтр по кнопке "Только мои"
                                val matchesType = !showOnlyUserPatterns || pattern.startsWith("user_")

                                // Фильтр по строке поиска
                                val clean = if (pattern.startsWith("user_")) pattern.removePrefix("user_") else pattern
                                val matchesSearch = clean.contains(searchPatternQuery.trim(), ignoreCase = true)

                                matchesType && matchesSearch
                            }
                        }
                    }

// 2. САМ СПИСОК ТЕПЕРЬ ПРОСТО ЧИТАЕТ СТАБИЛЬНЫЙ РЕЗУЛЬТАТ
                    LazyColumn(
                        modifier = Modifier.height(250.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = finalFilteredList,
                            key = { it } // Ключ гарантирует, что Compose не запутается при обновлении или удалении строк
                        ) { pattern ->
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
    modifier: Modifier
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
            modifier = modifier
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (isUserPattern) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Мой",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Из базы",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = displayPattern,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }

                //if (isUserPattern) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
               // }
            }
        }
    }
}

// Экран настроек
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { mutableStateOf(loadSettings(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Настройки",
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
                Text(
                    text = "Приложение по умолчанию",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (settings.value.isDefaultDialer)
                            "✅ Приложение установлено по умолчанию"
                        else
                            "❌ Не является приложением по умолчанию"
                    )
                    if (!settings.value.isDefaultDialer) {
                        Button(
                            onClick = { openPhoneAppSettings(context) }
                        ) {
                            Text("Назначить")
                        }
                    }
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
                Text(
                    text = "Параметры блокировки",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                var allowContacts by remember { mutableStateOf(settings.value.allowContacts) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Разрешить звонки из контактов")
                    Switch(
                        checked = allowContacts,
                        onCheckedChange = {
                            allowContacts = it
                            settings.value = settings.value.copy(allowContacts = it)
                            saveSettings(context, settings.value)
                        }
                    )
                }

                var blockHidden by remember { mutableStateOf(settings.value.blockHiddenNumbers) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Блокировать скрытые номера")
                    Switch(
                        checked = blockHidden,
                        onCheckedChange = {
                            blockHidden = it
                            settings.value = settings.value.copy(blockHiddenNumbers = it)
                            saveSettings(context, settings.value)
                        }
                    )
                }

                var blockInternational by remember { mutableStateOf(settings.value.blockInternational) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Блокировать международные звонки")
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

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Лимит истории звонков",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сохранить лимит")
                }
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
            PermissionItem("📞 Совершение звонков")
            PermissionItem("🔔 Показ уведомлений")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
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
fun ActiveCallScreen(
    call: android.telecom.Call,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    var isSpeakerOn by remember { mutableStateOf(false) }

    // Наблюдаем за системным состоянием звонка в реальном времени
    var callState by remember { mutableStateOf(call.state) }

    // Получаем номер телефона из параметров вызова
    val number = call.details.handle?.schemeSpecificPart ?: "Неизвестный номер"
    val isIdVerified = call.details.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED

    // ПЕРЕМЕННАЯ ДЛЯ ХРАНЕНИЯ ОПРЕДЕЛЕННОГО ИМЕНИ АБОНЕНТА
    var displayName by remember { mutableStateOf("Загрузка...") }

    // ПОИСК ИМЕНИ ПО ЦЕПОЧКЕ: КОНТАКТЫ -> АОН -> НЕИЗВЕСТНЫЙ
    LaunchedEffect(call, number) {
        // Шаг 1: Ищем в локальной телефонной книге устройства
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val localContactName = getContactNameFromPhoneBook(context, cleanNumber)

        if (!localContactName.isNullOrBlank()) {
            displayName = localContactName
        } else {
            // Шаг 2: Если в контактах нет, проверяем имя из системного АОН (Google/Telecom)
            val systemAonName = call.details.callerDisplayName

            if (isIdVerified && !systemAonName.isNullOrBlank()) {
                displayName = systemAonName
            } else {
                // Шаг 3: Если и АОН пустой, выводим "Неизвестный"
                displayName = "Неизвестный"
            }
        }
    }

    // Слушатель изменения состояния звонка (оставляем как был)
    DisposableEffect(call) {
        val callback = object : android.telecom.Call.Callback() {
            override fun onStateChanged(call: android.telecom.Call, state: Int) {
                callState = state
            }
        }
        call.registerCallback(callback)
        onDispose { call.unregisterCallback(callback) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. ИНФОРМАЦИЯ О ЗВОНКЕ (Номер и Статус всегда сверху)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 48.dp)
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))

            // КРУПНЫЙ НОМЕР ТЕЛЕФОНА
            Text(
                text = number,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ТЕКСТ СТАТУСА В ЗАВИСИМОСТИ ОТ СОСТОЯНИЯ
            val statusText = when (callState) {
                android.telecom.Call.STATE_RINGING -> "Входящий звонок..."
                android.telecom.Call.STATE_DIALING -> "Набор номера..."
                android.telecom.Call.STATE_ACTIVE -> "Разговор..."
                android.telecom.Call.STATE_HOLDING -> "Удержание..."
                else -> "Соединение..."
            }
            Text(text = statusText, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        }

        // 2. ДИНАМИЧЕСКИЕ КНОПКИ УПРАВЛЕНИЯ
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ЕСЛИ ЗВОНОК ТОЛЬКО ПОСТУПАЕТ (STATE_RINGING — показываем ДВЕ кнопки: Принять и Сбросить)
            if (callState == android.telecom.Call.STATE_RINGING) {

                // КНОПКА ОТКЛОНИТЬ (Красная)
                IconButton(
                    onClick = {
                        call.reject(false, null)
                        onDisconnect()
                    },
                    modifier = Modifier.size(72.dp).background(
                        MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(50)
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Отклонить", tint = MaterialTheme.colorScheme.onError)
                }

                // КНОПКА ОТВЕТИТЬ / ПОДНЯТЬ ТРУБКУ (Зеленая)
                IconButton(
                    onClick = {
                        call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                    },
                    modifier = Modifier.size(72.dp).background(
                        androidx.compose.ui.graphics.Color(0xFF4CAF50), // Насыщенный зеленый цвет
                        shape = RoundedCornerShape(50)
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Ответить", tint = androidx.compose.ui.graphics.Color.White)
                }

            } else {
                // ЕСЛИ ТРУБКА УЖЕ ПОДНЯТА (Разговор активен — показываем Громкую связь и Сброс)

                // Кнопка громкой связи (Спикер)
                IconButton(
                    onClick = {
                        isSpeakerOn = !isSpeakerOn
                        // Безопасно вызываем переключение через наш сервис
                        MyInCallService.toggleSpeaker(isSpeakerOn)
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            if (isSpeakerOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50)
                        )
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.stat_sys_speakerphone),
                        contentDescription = "Громкая связь",
                        tint = if (isSpeakerOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Кнопка завершения начатого разговора (Сброс)
                IconButton(
                    onClick = {
                        call.disconnect()
                        onDisconnect()
                    },
                    modifier = Modifier.size(64.dp).background(
                        MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(50)
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Завершить вызов", tint = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}


@Composable
fun SimSelectionDialog(
    simCards: List<SimCardInfo>,
    onSimSelected: (SimCardInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите SIM-карту для звонка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                simCards.forEach { sim ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSimSelected(sim) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Слот ${sim.slotIndex + 1}: ${sim.carrierName}",
                                    fontWeight = FontWeight.Bold
                                )
                                if (!sim.phoneNumber.isNullOrBlank()) {
                                    Text(text = sim.phoneNumber, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}