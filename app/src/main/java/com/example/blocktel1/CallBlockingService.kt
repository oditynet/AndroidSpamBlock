package com.example.blocktel1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class CallBlockingService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "call_blocking_channel",
                "Блокировка звонков",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Сервис блокировки нежелательных звонков"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, "call_blocking_channel")
            .setContentTitle("Блокировщик звонков активен")
            .setContentText("Отслеживание входящих вызовов")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        startForeground(1, notification)
    }
}