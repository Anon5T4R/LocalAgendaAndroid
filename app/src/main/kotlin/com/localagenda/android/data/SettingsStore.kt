package com.localagenda.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Preferências locais do aparelho (DataStore) — só configuração de APARELHO:
 *
 *  - `agenda_uri`: URI de documento (SAF) do último banco aberto/criado. O
 *    arquivo em si fica no armazenamento do usuário; aqui só a referência.
 *  - `biometric_enabled`: opt-in de desbloqueio rápido por biometria (pref
 *    local; o banco não tem senha nem cifra, então é só preferência de UI).
 *
 * As configurações do APP (tema etc.) NÃO ficam aqui: vivem no `meta` do
 * próprio .db (igual ao desktop) para sincronizar entre dispositivos.
 */
private val Context.agendaDataStore by preferencesDataStore(name = "localagenda")

class SettingsStore(private val context: Context) {

    val agendaUri: Flow<String?> = context.agendaDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.AGENDA_URI] }

    val biometricEnabled: Flow<Boolean> = context.agendaDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.BIOMETRIC_ENABLED] ?: false }

    suspend fun setAgendaUri(uri: String?) {
        context.agendaDataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.AGENDA_URI) else prefs[Keys.AGENDA_URI] = uri
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.agendaDataStore.edit { prefs -> prefs[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    private object Keys {
        val AGENDA_URI = stringPreferencesKey("agenda_uri")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
    }
}
