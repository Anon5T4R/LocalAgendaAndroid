package com.localagenda.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Dispara quando um alarme chega na hora: mostra a notificação e re-agenda a
 * próxima ocorrência. Lê o alarme do banco (via URI persistida) pra respeitar
 * o estado atual — se foi desativado/apagado depois do agendamento, não toca.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val alarm = NotificationDb.readAlarm(context, id)
                if (alarm != null && alarm.enabled) {
                    NotificationChannels.ensure(context)
                    NotificationHelper.showAlarm(context, alarm)
                    AlarmScheduler(context).schedule(alarm)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}