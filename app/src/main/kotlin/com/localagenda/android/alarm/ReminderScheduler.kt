package com.localagenda.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.localagenda.android.data.AgendaEvent
import com.localagenda.android.data.Task
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Agenda os lembretes de eventos/tarefas (minutos antes do início/prazo) como
 * notificações únicas no AlarmManager. Reconciliado pelo [sync] a cada
 * mutação/abertura — o que já passou do horário simplesmente não é re-agendado.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scheduledKeys = mutableSetOf<String>()

    fun sync(events: List<AgendaEvent>, tasks: List<Task>) {
        val keys = buildSet {
            events.forEach { ev -> ev.reminders.forEach { add(reminderKey(ev.id, it)) } }
            tasks.forEach { t -> t.reminders.forEach { add(reminderKey(t.id, it)) } }
        }
        (scheduledKeys - keys).forEach { cancel(it) }
        scheduledKeys.clear()
        events.forEach { ev -> ev.reminders.forEach { scheduleEvent(ev, it) } }
        tasks.forEach { t -> t.reminders.forEach { scheduleTask(t, it) } }
    }

    private fun scheduleEvent(ev: AgendaEvent, minutesBefore: Int) {
        val fireAt = parseWallClock(ev.start)?.minusMinutes(minutesBefore.toLong()) ?: return
        scheduleOne(reminderKey(ev.id, minutesBefore), fireAt, ev.title, ev.start)
    }

    private fun scheduleTask(t: Task, minutesBefore: Int) {
        val fireAt = parseWallClock(t.due)?.minusMinutes(minutesBefore.toLong()) ?: return
        scheduleOne(reminderKey(t.id, minutesBefore), fireAt, t.title, t.due)
    }

    private fun scheduleOne(key: String, fireAt: LocalDateTime, title: String, whenText: String) {
        if (!fireAt.isAfter(LocalDateTime.now())) return
        val pi = pendingIntent(key, title, whenText)
        val triggerAt = fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        scheduledKeys += key
    }

    fun cancel(key: String) {
        alarmManager.cancel(pendingIntent(key, "", ""))
        scheduledKeys -= key
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(key: String, title: String, whenText: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .putExtra(EXTRA_REMINDER_KEY, key)
            .putExtra(EXTRA_REMINDER_TITLE, title)
            .putExtra(EXTRA_REMINDER_WHEN, whenText)
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REMINDER = "com.localagenda.android.action.REMINDER"
        const val EXTRA_REMINDER_KEY = "reminder_key"
        const val EXTRA_REMINDER_TITLE = "reminder_title"
        const val EXTRA_REMINDER_WHEN = "reminder_when"

        fun reminderKey(refId: String, minutesBefore: Int) = "$refId:$minutesBefore"

        /** "YYYY-MM-DDTHH:MM" ou "YYYY-MM-DD" — hora de parede local do desktop. */
        private val WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        private fun parseWallClock(raw: String): LocalDateTime? {
            if (!raw.contains('T')) return null
            return runCatching { LocalDateTime.parse(raw, WALL_CLOCK) }.getOrNull()
        }
    }
}