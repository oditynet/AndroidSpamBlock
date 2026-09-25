package com.example.blocktel1

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object BaserowClient {
    private const val TAG = "BaserowClient"

    // ГЛОБАЛЬНЫЕ НАСТРОЙКИ (Задаются 1 раз)
    private const val ROWS_URL = "https://baserow.ru/api/database/rows/table/2911/" // ID таблицы из вашего запроса
    private const val AUTH_TOKEN = "LiR4OP95XGCGZkl62u5NQGQ6bAMi8oUG" // Замените на ваш реальный токен Baserow

    // Порог доверия для глобального спама (сумма кармы должна быть >= 5)
    private const val GLOBAL_SPAM_THRESHOLD = 5

    /**
     * Проверка номера по двухуровневой схеме консенсуса.
     * Возвращает true, если номер заблокирован глобально ИЛИ лично для этого пользователя.
     */
    suspend fun checkIsSpam(phoneNumber: String, currentUserId: String): Boolean = withContext(Dispatchers.IO) {
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanNumber.isBlank()) return@withContext false

        // Делаем выборку по конкретному номеру с флагом текстовых имен полей
        val urlString = "$ROWS_URL?user_field_names=true&filter__field_phone__equal=$cleanNumber"
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Token $AUTH_TOKEN")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val rootJson = JSONObject(responseText)
                val resultsArray: JSONArray = rootJson.optJSONArray("results") ?: return@withContext false

                var totalKarmaScore = 0
                var isBlockedByMe = false

                // Защита от накрутки: один пользователь = один голос
                val votedUsers = mutableSetOf<String>()

                for (i in 0 until resultsArray.length()) {
                    val row = resultsArray.getJSONObject(i)
                    val rowUserId = row.optString("user_id")
                    val rowKarma = row.optInt("karma", 1)

                    // 1-й уровень: Личный черный список
                    if (rowUserId == currentUserId) {
                        isBlockedByMe = true
                    }

                    // 2-й уровень: Глобальный список (считаем только уникальные ID устройств)
                    if (rowUserId.isNotBlank() && !votedUsers.contains(rowUserId)) {
                        votedUsers.add(rowUserId)
                        totalKarmaScore += rowKarma
                    }
                }

                // Вердикт алгоритма консенсуса
                if (isBlockedByMe || totalKarmaScore >= GLOBAL_SPAM_THRESHOLD) {
                    Log.d(TAG, "Номер $cleanNumber ЗАБЛОКИРОВАН. Мой бан: $isBlockedByMe, Общая карма: $totalKarmaScore")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки номера: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return@withContext false
    }

    /**
     * ЗАПИСЬ В ТАБЛИЦУ (Строго по вашему cURL образцу)
     */
    suspend fun addSpamVote(phoneNumber: String, currentUserId: String, userKarma: Int): Boolean = withContext(Dispatchers.IO) {
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanNumber.isBlank()) return@withContext false

        var connection: HttpURLConnection? = null
        try {
            // URL строго по вашей документации: со словом rows и флагом user_field_names=true
            val url = URL("$ROWS_URL?user_field_names=true")

            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Token $AUTH_TOKEN")
            connection.setRequestProperty("Content-Type", "application/json") // Формат из доков
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            // Таймштамп в формате ISO 8601, как требует образец (например, 2026-09-25T13:30:00Z)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val currentIsoDate = dateFormat.format(java.util.Date())

            // JSON-тело запроса, полностью повторяющее структуру вашей таблицы
            val jsonBody = JSONObject().apply {
                put("phone", cleanNumber)
                put("user_id", currentUserId)
                put("karma", userKarma.toInt())
                put("created_at", currentIsoDate)
            }

            connection.outputStream.use { os ->
                val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                Log.d(TAG, "Жалоба на $cleanNumber успешно добавлена в Baserow!")
                return@withContext true
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Ошибка добавления строки: $responseCode | Ответ сервера: $errorText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сети при POST-запросе: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return@withContext false
    }
}