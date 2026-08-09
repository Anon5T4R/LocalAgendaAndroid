package com.localagenda.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Dispara quando um lembrete de evento/tarefa chega na hora: mostra a
 * notificação com o título e o horário que vieram no PendingIntent.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_KEY) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TITLE) ?: return
        val whenText = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_WHEN).orEmpty()
        NotificationChannels.ensure(context)
        NotificationHelper.showReminder(context, key, title, whenText)
    }
}