package com.example.blocktel1

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.blocktel1.ui.theme.BlockTel1Theme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Модель данных для звонков
data class CallLog(
    val number: String,
    val name: String?,
    val timestamp: String,
    val type: String,
    val shouldBlock: Boolean = false
)

class MainActivity : ComponentActivity() {

    private lateinit var callReceiver: CallReceiver

    // Регистрируем запрос разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        permissionGranted.value = allGranted
        if (allGranted) {
            registerCallReceiver()
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
            registerCallReceiver()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterCallReceiver()
    }

    private fun registerCallReceiver() {
        try {
            callReceiver = CallReceiver()
            val filter = IntentFilter()
            filter.addAction("android.intent.action.PHONE_STATE")
            registerReceiver(callReceiver, filter)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Ошибка регистрации приемника звонков", e)
        }
    }

    private fun unregisterCallReceiver() {
        try {
            unregisterReceiver(callReceiver)
        } catch (e: Exception) {
            // Игнорируем ошибку если приемник не был зарегистрирован
        }
    }

    private fun checkPermissions() {
        val permissions = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS
        )

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        permissionGranted.value = allGranted
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS
        )

        requestPermissionLauncher.launch(permissions)
    }
}

@Composable
fun CallMonitorApp(
    permissionGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    // Состояние для списка звонков
    val callLogs = remember { mutableStateListOf<CallLog>() }
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Состояние для списка блокируемых текстов
    val blockedPatterns = remember { mutableStateListOf<String>() }
    var newPattern by remember { mutableStateOf("") }

    // Загружаем сохраненные шаблоны при запуске
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
        val savedPatterns = prefs.getStringSet("blocked_patterns", emptySet()) ?: emptySet()
        blockedPatterns.clear()
        blockedPatterns.addAll(savedPatterns)
    }

    // Автоматически загружаем историю при получении разрешений
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            scope.launch {
                isLoading = true
                val history = loadCallHistory(context, blockedPatterns)
                callLogs.clear()
                callLogs.addAll(history)
                isLoading = false
            }
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Заголовок
            Text(
                text = "📞 Блокировщик звонков",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            if (!permissionGranted) {
                // Экран запроса разрешений
                PermissionScreen(
                    onRequestPermissions = onRequestPermissions
                )
            } else {
                // Секция добавления блокируемых номеров
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Добавить текст для блокировки:",
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
                                        val pattern = newPattern.trim()
                                        blockedPatterns.add(pattern)

                                        // Сохраняем в SharedPreferences
                                        val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
                                        prefs.edit {
                                            putStringSet("blocked_patterns", blockedPatterns.toSet())
                                        }

                                        newPattern = ""

                                        // Обновляем список звонков с новой проверкой
                                        scope.launch {
                                            val updatedLogs = loadCallHistory(context, blockedPatterns)
                                            callLogs.clear()
                                            callLogs.addAll(updatedLogs)
                                        }
                                    }
                                }
                            ) {
                                Text("Добавить")
                            }
                        }

                        if (blockedPatterns.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Заблокированные тексты:",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            blockedPatterns.forEach { pattern ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = pattern)
                                    Button(
                                        onClick = {
                                            blockedPatterns.remove(pattern)

                                            // Сохраняем в SharedPreferences
                                            val prefs = context.getSharedPreferences("blocktel_prefs", Context.MODE_PRIVATE)
                                            prefs.edit {
                                                putStringSet("blocked_patterns", blockedPatterns.toSet())
                                            }

                                            // Обновляем список звонков
                                            scope.launch {
                                                val updatedLogs = loadCallHistory(context, blockedPatterns)
                                                callLogs.clear()
                                                callLogs.addAll(updatedLogs)
                                            }
                                        },
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Удалить")
                                    }
                                }
                            }
                        }
                    }
                }

                // Основной экран с логами звонков
                CallLogsScreen(
                    callLogs = callLogs,
                    isLoading = isLoading
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Статус приложения
            Text(
                text = if (permissionGranted)
                    "✅ Приложение активно и отслеживает звонки"
                else
                    "⚠️ Требуются разрешения для работы",
                color = if (permissionGranted) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error
            )

            // Подсказка
            if (permissionGranted && blockedPatterns.isNotEmpty()) {
                Text(
                    text = "Звонки с номерами, содержащими указанные тексты, будут блокироваться",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

// Функция для загрузки истории звонков с проверкой блокировки
fun loadCallHistory(context: android.content.Context, blockedPatterns: List<String>): List<CallLog> {
    val callLogs = mutableListOf<CallLog>()

    try {
        // Проверяем разрешение на чтение журнала вызовов
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return callLogs
        }

        // Запрос к журналу вызовов
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

            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

            var count = 0
            while (c.moveToNext() && count < 20) {
                val number = c.getString(numberIndex) ?: "Неизвестный номер"
                val name = c.getString(nameIndex)
                val dateLong = c.getLong(dateIndex)
                val callType = c.getInt(typeIndex)

                // Преобразуем timestamp в читаемое время
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

                // Проверяем, нужно ли блокировать
                val shouldBlock = shouldBlockCall(formattedNumber, name, blockedPatterns)

                callLogs.add(
                    CallLog(
                        number = formattedNumber,
                        name = name,
                        timestamp = date,
                        type = typeText,
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

// Функция для проверки блокировки
fun shouldBlockCall(number: String, name: String?, blockedPatterns: List<String>): Boolean {
    if (blockedPatterns.isEmpty()) return false

    return blockedPatterns.any { pattern ->
        pattern.isNotBlank() && (
            number.contains(pattern, ignoreCase = true) ||
            (name?.contains(pattern, ignoreCase = true) == true)
        )
    }
}

// Функция для форматирования номера телефона
fun formatPhoneNumber(number: String): String {
    return if (number.length >= 10) {
        val last10 = number.takeLast(10)
        "+7 ${last10.substring(0, 3)} ${last10.substring(3, 6)}-${last10.substring(6, 8)}-${last10.substring(8)}"
    } else {
        number
    }
}

@Composable
fun PermissionScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Для работы приложения необходимы следующие разрешения:",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Список необходимых разрешений
        Column(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            PermissionItem("📇 Чтение контактов")
            PermissionItem("📞 Чтение состояния телефона")
            PermissionItem("📋 Чтение журнала вызовов")
            PermissionItem("📲 Ответ на входящие звонки")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Предоставить разрешения")
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
fun CallLogsScreen(
    callLogs: List<CallLog>,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Заголовок
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "История звонков:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            // Показать количество
            Text(
                text = "Всего: ${callLogs.size}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Загрузка истории звонков...")
            }
        } else {
            if (callLogs.isEmpty()) {
                // Экран при отсутствии звонков
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Нет записей о звонках",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "История будет загружена автоматически",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                // Список звонков
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(callLogs) { call ->
                        CallLogItem(call)
                    }
                }
            }
        }
    }
}

@Composable
fun CallLogItem(call: CallLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (call.shouldBlock) {
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        }
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
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (call.shouldBlock) MaterialTheme.colorScheme.onErrorContainer
                               else MaterialTheme.colorScheme.onSurface
                    )

                    if (call.name != null && call.name != call.number) {
                        Text(
                            text = call.number,
                            fontSize = 14.sp,
                            color = if (call.shouldBlock) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = call.type,
                        fontSize = 12.sp,
                        color = if (call.shouldBlock) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary
                    )

                    if (call.shouldBlock) {
                        Text(
                            text = "Будет заблокирован",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = "Время: ${call.timestamp}",
                fontSize = 12.sp,
                color = if (call.shouldBlock) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// Предварительный просмотр
@Preview(showBackground = true)
@Composable
fun PermissionScreenPreview() {
    BlockTel1Theme {
        PermissionScreen(onRequestPermissions = {})
    }
}

@Preview(showBackground = true)
@Composable
fun CallLogsScreenPreview() {
    BlockTel1Theme {
        CallLogsScreen(
            callLogs = listOf(
                CallLog("+7 999 123-45-67", "Иван Иванов", "01.02.2024 10:30", "📥 Входящий", false),
                CallLog("+7 999 987-65-43", "Реклама", "01.02.2024 11:45", "❌ Пропущенный", true),
                CallLog("+7 999 555-55-55", "Мария Петрова", "01.02.2024 12:15", "📤 Исходящий", false)
            ),
            isLoading = false
        )
    }
}