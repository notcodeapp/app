package com.notcode.mobile.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.notcode.mobile.R

/** Keeps the process alive while a PTY session or agent run is active in the background. */
class TerminalKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Фоновая работа", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("NotCode работает в фоне")
            .setContentText("Терминал и агент продолжают работу")
            .setOngoing(true)
            .build()
        // Android 14 требует передать тип foreground-сервиса, объявленный в манифесте.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // С targetSdk 34 система не даёт поднимать foreground-сервис из фона, а вместе с сервисом
        // умирают и терминал, и агент — перерождение удерживать нечего.
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "notcode-background"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TerminalKeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalKeepAliveService::class.java))
        }
    }
}