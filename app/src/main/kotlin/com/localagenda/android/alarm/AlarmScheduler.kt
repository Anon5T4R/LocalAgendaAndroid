package com.localagenda.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.localagenda.android.data.Alarm
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Agenda os alarmes no AlarmManager do sistema. Cada alarme vira um
 * PendingIntent único (requestCode = hash do id) que o [AlarmReceiver]
 * transforma em notificação e re-agenda pra próxima ocorrência.
 *
 * O [sync] é o ponto único de reconciliação: cancela o que saiu da lista
 * (deletado/desativado) e agenda o que está ativo — chamado a cada mutação
 * e na abertura do banco.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Ids com alarme pendente no sistema — pra saber o que cancelar no sync. */
    private val scheduledIds = mutableSetOf<String>()

    fun sync(alarms: List<Alarm>) {
        val activeIds = alarms.filter { it.enabled }.map { it.id }.toSet()
        (scheduledIds - activeIds).forEach { cancel(it) }
        scheduledIds.clear()
        alarms.filter { it.enabled }.forEach { schedule(it) }
    }

    fun schedule(alarm: Alarm) {
        val fireAt = nextFireAt(alarm) ?: return
        val pi = pendingIntent(alarm.id, alarm)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }
        scheduledIds += alarm.id
    }

    fun cancel(id: String) {
        alarmManager.cancel(pendingIntent(id, null))
        scheduledIds -= id
    }

    /** Próximo disparo (epoch-ms) do alarme; null se a hora for inválida. */
    fun nextFireAt(alarm: Alarm): Long? {
        val parts = alarm.time.split(":").map { it.trim().toIntOrNull() }
        if (parts.size != 2) return null
        val hour = parts[0] ?: return null
        val minute = parts[1] ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        val now = LocalDateTime.now()
        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)

        if (alarm.days.isNotEmpty()) {
            // days: 0=domingo…6=sábado; dayOfWeek.value: 1=segunda…7=domingo.
            // value % 7 casa exatamente com o índice do dia.
            repeat(8) {
                if (candidate.dayOfWeek.value % 7 in alarm.days) {
                    return candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                candidate = candidate.plusDays(1)
            }
            return null
        }
        return candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(id: String, alarm: Alarm?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .putExtra(EXTRA_ALARM_ID, id)
        if (alarm != null) {
            intent
                .putExtra(EXTRA_ALARM_TIME, alarm.time)
                .putExtra(EXTRA_ALARM_LABEL, alarm.label)
                .putExtra(EXTRA_ALARM_DAYS, alarm.days.toIntArray())
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_ALARM = "com.localagenda.android.action.ALARM"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_TIME = "alarm_time"
        const val EXTRA_ALARM_LABEL = "alarm_label"
        const val EXTRA_ALARM_DAYS = "alarm_days"
    }
}