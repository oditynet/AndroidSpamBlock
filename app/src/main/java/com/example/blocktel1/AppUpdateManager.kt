package com.example.blocktel1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val RELEASES_PAGE_URL = "https://github.com/oditynet/AndroidSpamBlock/releases"

    // 1. СКАНИРУЕМ СТРАНИЦУ И ВЫВОДИМ ДОСТУПНУЮ ВЕРСИЮ В ЛОГ
    suspend fun checkLatestVersion(currentVersion: String): Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        try {
            Log.d(TAG, "==> Запрос страницы релизов: " + RELEASES_PAGE_URL)
            val url = java.net.URL(RELEASES_PAGE_URL)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val htmlText = conn.inputStream.bufferedReader().use { it.readText() }

                val versionPattern = Pattern.compile("ver_([0-9.]+)")
                val matcher = versionPattern.matcher(htmlText)

                if (matcher.find()) {
                    val latestVersion = matcher.group(1) ?: ""
                    val cleanCurrent = currentVersion.replace("ver_", "").trim()

                    Log.i(TAG, "--------------------------------------------------")
                    Log.i(TAG, "[ДОСТУПНАЯ ВЕРСИЯ НА GITHUB]: ver_" + latestVersion)
                    Log.i(TAG, "[ТЕКУЩАЯ ВЕРСИЯ НА ТЕЛЕФОНЕ]: ver_" + cleanCurrent)
                    Log.i(TAG, "--------------------------------------------------")

                    val hasUpdate = latestVersion.isNotBlank() && isServerVersionNewer(latestVersion, cleanCurrent)
                    val downloadUrl = if (hasUpdate) {

                        "https://github.com/oditynet/AndroidSpamBlock/releases/download/ver_" + latestVersion + "/AndroidSpamBlock-" + latestVersion + ".apk"
                    } else ""

                    // Возвращаем три параметра, включая саму найденную версию
                    return@withContext Triple(hasUpdate, downloadUrl, latestVersion)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки версий на странице: " + e.message)
        }
        return@withContext Triple(false, "", "")
    }

    // Вспомогательная функция: возвращает true, если версия на сервере строго БОЛЬШЕ текущей
    private fun isServerVersionNewer(serverVer: String, currentVer: String): Boolean {
        try {
            val serverParts = serverVer.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVer.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLength = maxOf(serverParts.size, currentParts.size)
            for (i in 0 until maxLength) {
                val serverPart = serverParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }

                if (serverPart > currentPart) return true
                if (serverPart < currentPart) return false
            }
        } catch (e: Exception) {
            return serverVer != currentVer // Резервный вариант при сбое
        }
        return false
    }
    // 2. СКАЧИВАНИЕ APK-ФАЙЛА С АВТОМАТИЧЕСКИМ ПРОХОЖДЕНИЕМ РЕДИРЕКТОВ ГИТХАБА
    suspend fun downloadAndInstallApk(context: Context, downloadUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "==> Начинаю скачивание APK по адресу: " + downloadUrl)
            var currentUrl = downloadUrl
            var redirectCount = 0
            val maxRedirects = 5

            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection

                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false // Перехватываем 302 редирект вручную
                conn.connect()

                val responseCode = conn.responseCode
                Log.d(TAG, "<== Ответ сервера (шаг " + (redirectCount + 1) + "), код: " + responseCode)

                // Если GitHub перенаправляет на сервера объектов AWS/Azure (://githubusercontent.com)
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    val newUrl = conn.getHeaderField("Location")
                    if (newUrl != null && newUrl.isNotBlank()) {
                        currentUrl = newUrl
                        redirectCount++
                        conn.disconnect()
                        Log.d(TAG, "ЛОГ: Перенаправление (Редирект) на: " + currentUrl)
                        continue
                    }
                }

                // Когда успешно дошли до конечной точки скачивания
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val updateDir = File(context.cacheDir, "updates")
                    if (!updateDir.exists()) updateDir.mkdirs()

                    val apkFile = File(updateDir, "update.apk")
                    if (apkFile.exists()) apkFile.delete()

                    conn.inputStream.use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    Log.i(TAG, "ЛОГ: Самая последняя версия скачана в кэш! Размер: " + apkFile.length() + " байт.")
                    conn.disconnect()

                    // Запуск установки на главном UI-потоке Android
                    withContext(Dispatchers.Main) {
                        installApkSystem(context, apkFile)
                    }
                    return@withContext true
                } else {
                    Log.e(TAG, "Ошибка: Итоговый сервер вернул код ошибки: " + responseCode)
                    conn.disconnect()
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка скачивания APK: " + e.message)
        }
        return@withContext false
    }

    // 3. СИСТЕМНЫЙ УСТАНОВЩИК ДЛЯ СВЕЖИХ ВЕРСИЙ ANDROID (14/15/16)
    private fun installApkSystem(context: Context, file: File) {
        try {
            Log.d(TAG, "==> Запуск системного PackageInstaller...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:" + context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val apkUri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Сбой системного инсталлятора Android: " + e.message)
        }
    }
}