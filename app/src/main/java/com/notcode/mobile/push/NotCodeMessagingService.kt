package com.notcode.mobile.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.notcode.mobile.MainActivity
import com.notcode.mobile.R

class NotCodeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, token).apply()
        sendBroadcast(
            Intent(ACTION_TOKEN)
                .setPackage(packageName)
                .putExtra(EXTRA_TOKEN, token),
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "NotCode", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val action = message.data["action"].orEmpty()
        val target = message.data["target"].orEmpty()
        val notificationId = message.messageId?.hashCode() ?: System.currentTimeMillis().toInt()
        val requestCode = notificationId * 31 + action.hashCode()
        val contentIntent = if (action == PushActionTarget.OPEN_URL && target.startsWith("https://")) {
            PendingIntent.getActivity(this, requestCode, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, requestCode, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PUSH_ACTION, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val actionLabel = when (action) {
            PushActionTarget.OPEN_URL -> "Открыть сайт"
            PushActionTarget.OPEN_UPDATES -> "Обновления"
            PushActionTarget.OPEN_PROJECTS -> "Проекты"
            PushActionTarget.OPEN_SETTINGS -> "Настройки"
            else -> "Открыть NotCode"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.notification?.title ?: message.data["title"].orEmpty())
            .setContentText(message.notification?.body ?: message.data["body"].orEmpty())
            .setContentIntent(contentIntent)
            .addAction(0, actionLabel, contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    companion object {
        const val ACTION_TOKEN = "com.notcode.mobile.PUSH_TOKEN"
        const val EXTRA_TOKEN = "com.notcode.mobile.PUSH_TOKEN_VALUE"
        const val PREF_NAME = "push"
        const val TOKEN_KEY = "token"
        private const val CHANNEL_ID = "notcode_updates"
    }
}
