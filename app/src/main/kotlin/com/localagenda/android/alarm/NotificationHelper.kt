package com.localagenda.android.alarm

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.localagenda.android.R
import com.localagenda.android.data.Alarm

/** Monta e posta as notificações de alarme e lembrete. */
object NotificationHelper {

    fun showAlarm(context: Context, alarm: Alarm) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val title = alarm.label.ifBlank { context.getString(R.string.notif_alarm_title) }
        val notification = NotificationCompat.Builder(context, NotificationChannels.ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_alarm_text, alarm.time))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        manager.notify(alarm.id.hashCode(), notification)
    }

    fun showReminder(context: Context, key: String, title: String, whenText: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_reminder_text, whenText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(key.hashCode(), notification)
    }
}