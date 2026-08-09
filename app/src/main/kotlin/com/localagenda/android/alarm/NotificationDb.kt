package com.localagenda.android.alarm

import android.content.Context
import android.net.Uri
import com.localagenda.android.data.AgendaRepository
import com.localagenda.android.data.Alarm
import com.localagenda.android.data.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Leitura do banco pra fora do app (receivers): abre o documento SAF pela URI
 * persistida, lê o que precisa e fecha. O app tem permissão persistente de
 * leitura/escrita do documento escolhido, então isso funciona mesmo com o
 * processo morto (boot, alarme disparando).
 */
object NotificationDb {

    /** Reagenda alarmes + lembretes a partir do disco (boot, primeira abertura). */
    suspend fun syncAll(context: Context) {
        val uri = SettingsStore(context).agendaUri.first() ?: return
        val repo = AgendaRepository(context.contentResolver)
        try {
            repo.open(Uri.parse(uri))
            AlarmScheduler(context).sync(repo.alarms)
            ReminderScheduler(context).sync(repo.events, repo.tasks)
        } finally {
            repo.close()
        }
    }

    /** Lê um alarme pelo id; null se não existir mais (apagado/banco inacessível). */
    suspend fun readAlarm(context: Context, id: String): Alarm? {
        val uri = SettingsStore(context).agendaUri.first() ?: return null
        val repo = AgendaRepository(context.contentResolver)
        return try {
            repo.open(Uri.parse(uri))
            repo.alarms.find { it.id == id }
        } catch (e: Exception) {
            null
        } finally {
            repo.close()
        }
    }
}