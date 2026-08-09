package com.localagenda.android.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localagenda.android.R
import com.localagenda.android.alarm.AlarmScheduler
import com.localagenda.android.alarm.ReminderScheduler
import com.localagenda.android.data.AgendaEvent
import com.localagenda.android.data.AgendaRepository
import com.localagenda.android.data.Alarm
import com.localagenda.android.data.Calendar
import com.localagenda.android.data.ExternalChangeException
import com.localagenda.android.data.Settings
import com.localagenda.android.data.SettingsStore
import com.localagenda.android.data.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Estado da UI + ponte entre tela e banco.
 *
 * Fluxo: o [AgendaRepository] guarda o modelo em memória; as mutações marcam
 * `dirty` e agendam o auto-save DEBOUNCED (2 s após a última mutação); o
 * persist reescreve o .db inteiro num transaction. Antes de gravar, o repo
 * compara o disco com a última versão conhecida — se outro dispositivo
 * sincronizou uma versão nova, sobe o diálogo de conflito (Recarregar /
 * Sobrescrever) em vez de sobrescrever em silêncio.
 *
 * Os launchers de SAF (abrir/criar/importar/exportar) vivem na [MainActivity]
 * — mesmo padrão do LocalKeys — e chegam aqui como URI já escolhida.
 */
class AgendaViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val calendars: List<Calendar> = emptyList(),
        val events: List<AgendaEvent> = emptyList(),
        val tasks: List<Task> = emptyList(),
        val alarms: List<Alarm> = emptyList(),
        val settings: Settings = Settings.DEFAULT,
        val agendaUri: String? = null,
        val biometricEnabled: Boolean = false,
        /** Arquivo mudou no disco fora do app → UI mostra o diálogo de conflito. */
        val externalChange: Boolean = false,
        val dirty: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
    )

    private val repository = AgendaRepository(getApplication<Application>().contentResolver)
    private val settingsStore = SettingsStore(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Job do auto-save debounced: cancelado a cada mutação, refeito por cima. */
    private var saveJob: Job? = null
    private var biometricOn = false

    init {
        viewModelScope.launch {
            settingsStore.agendaUri.collect { uri ->
                _state.update { it.copy(agendaUri = uri) }
                if (uri != null) load(uri)
            }
        }
        viewModelScope.launch {
            settingsStore.biometricEnabled.collect { enabled ->
                biometricOn = enabled
                _state.update { it.copy(biometricEnabled = enabled) }
            }
        }
    }

    // ── Abrir / criar banco ──────────────────────────────────────────────

    /** URI escolhida no SAF (abrir existente). Persiste e o collect carrega. */
    fun onDocumentChosen(uri: Uri) {
        viewModelScope.launch { settingsStore.setAgendaUri(uri.toString()) }
    }

    /** URI do documento criado no SAF (CreateDocument). Idem. */
    fun onCreatedDocument(uri: Uri) {
        viewModelScope.launch { settingsStore.setAgendaUri(uri.toString()) }
    }

    /** Abre o último banco (tela de boas-vindas depois de travar). */
    fun loadLast() {
        val uri = _state.value.agendaUri ?: return
        load(uri)
    }

    /** Abre o repo: lê o .db inteiro pro modelo em memória. */
    private fun load(uri: String) {
        saveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null, externalChange = false) }
            try {
                repository.open(Uri.parse(uri))
                syncRepoToState(loaded = true, loading = false, notice = str(R.string.msg_loaded))
                syncNotifications()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = str(R.string.msg_load_failed, e.message ?: ""),
                    )
                }
            }
        }
    }

    // ── Mutações (agendam o auto-save) ───────────────────────────────────

    fun saveEvent(event: AgendaEvent) = mutate { repository.upsertEvent(event) }
    fun deleteEvent(id: String) = mutate { repository.deleteEvent(id) }

    fun saveTask(task: Task) = mutate { repository.upsertTask(task) }
    fun deleteTask(id: String) = mutate { repository.deleteTask(id) }

    fun saveCalendar(calendar: Calendar) = mutate { repository.upsertCalendar(calendar) }
    fun deleteCalendar(id: String) = mutate { repository.deleteCalendar(id) }

    fun saveAlarm(alarm: Alarm) = mutate { repository.upsertAlarm(alarm) }
    fun deleteAlarm(id: String) = mutate { repository.deleteAlarm(id) }

    fun saveSettings(settings: Settings) = mutate { repository.updateSettings(settings) }

    fun toggleBiometric(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBiometricEnabled(enabled) }
    }

    /** ID novo no esquema do desktop (events "ev", tasks "task", alarmes…). */
    fun newId(prefix: String): String = repository.genId(prefix)

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            syncRepoToState()
            syncNotifications()
            scheduleAutoSave()
        }
    }

    private fun syncRepoToState(
        loaded: Boolean = _state.value.loaded,
        loading: Boolean = _state.value.loading,
        notice: String? = _state.value.notice,
        error: String? = _state.value.error,
    ) {
        _state.update {
            it.copy(
                loaded = loaded,
                loading = loading,
                calendars = repository.calendars,
                events = repository.events,
                tasks = repository.tasks,
                alarms = repository.alarms,
                settings = repository.settings,
                dirty = repository.dirty,
                notice = notice,
                error = error,
            )
        }
    }

    /**
     * Re-sincroniza alarmes e lembretes no AlarmManager a partir do modelo em
     * memória. Chamado a cada mutação e na abertura/reload do banco — o
     * AlarmManager não sabe o que mudou, quem reconcilia é este sync.
     */
    private fun syncNotifications() {
        val app = getApplication<Application>()
        AlarmScheduler(app).sync(repository.alarms)
        ReminderScheduler(app).sync(repository.events, repository.tasks)
    }

    // ── Salvar (auto-save debounced 2 s + manual) ────────────────────────

    /** A cada mutação: cancela o save pendente e agenda outro daqui a 2 s. */
    private fun scheduleAutoSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            persistSafely(notice = null)
        }
    }

    /** Salvar manual (botão da barra): imediato. */
    fun save() {
        saveJob?.cancel()
        viewModelScope.launch { persistSafely(notice = str(R.string.msg_saved)) }
    }

    /**
     * Sobrescreve o arquivo mesmo havendo mudança externa (usuário confirmou
     * no diálogo de conflito). As edições pendentes já estão em memória.
     */
    fun forceSave() {
        saveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, externalChange = false, error = null) }
            try {
                val uri = _state.value.agendaUri ?: throw IOException(str(R.string.err_no_file))
                repository.persist(Uri.parse(uri), force = true)
                _state.update { it.copy(busy = false, notice = str(R.string.msg_saved)) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "") }
            }
        }
    }

    /**
     * Recarrega o arquivo do disco, adotando a versão externa e descartando
     * as edições em memória não salvas (usuário confirmou no diálogo).
     */
    fun reloadFromDisk() {
        saveJob?.cancel()
        val uri = _state.value.agendaUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, externalChange = false, error = null) }
            try {
                repository.reload(Uri.parse(uri))
                syncRepoToState(notice = str(R.string.msg_reloaded))
                syncNotifications()
                _state.update { it.copy(busy = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = str(R.string.msg_reload_failed, e.message ?: ""))
                }
            }
        }
    }

    /** Fecha o diálogo de conflito sem agir (as edições continuam pendentes). */
    fun dismissExternalChange() {
        _state.update { it.copy(externalChange = false) }
    }

    /**
     * Sincroniza ao retomar o app: verifica se o arquivo no disco mudou
     * externamente (outro dispositivo sincronizou). Se mudou e não há edições
     * pendentes (dirty=false), recarrega automaticamente. Se há edições,
     * marca externalChange para o diálogo decidir.
     */
    fun syncOnResume() {
        val uri = _state.value.agendaUri ?: return
        if (_state.value.busy || _state.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            val changed = repository.checkExternalChange(Uri.parse(uri))
            if (changed) {
                if (_state.value.dirty) {
                    _state.update { it.copy(externalChange = true) }
                } else {
                    // Sem edições pendentes: recarrega silenciosamente
                    repository.reload(Uri.parse(uri))
                    syncNotifications()
                    _state.update { it.copy(notice = str(R.string.msg_reloaded)) }
                }
            }
        }
    }

    /** Grava com a checagem de mudança externa; conflito vira o diálogo. */
    private suspend fun persistSafely(notice: String?) {
        val uri = _state.value.agendaUri ?: return
        _state.update { it.copy(busy = true, error = null) }
        try {
            repository.persist(Uri.parse(uri))
            _state.update { it.copy(busy = false, dirty = false, notice = notice) }
        } catch (e: ExternalChangeException) {
            _state.update { it.copy(busy = false, externalChange = true) }
        } catch (e: Exception) {
            failSave(e, R.string.op_save)
        }
    }

    // ── Importar / exportar ──────────────────────────────────────────────

    /**
     * Importar = adotar outro .db como agenda (o desktop valida e substitui a
     * base; aqui, como o arquivo É o banco, importar é trocar o documento).
     */
    fun onImportChosen(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                if (!repository.verifyIsAgendaDb(uri)) {
                    _state.update { it.copy(busy = false, error = str(R.string.msg_import_invalid)) }
                    return@launch
                }
                settingsStore.setAgendaUri(uri.toString())
                // O collect do agendaUri chama load() — nada mais a fazer aqui.
                _state.update { it.copy(busy = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = str(R.string.msg_import_generic, e.message ?: ""))
                }
            }
        }
    }

    /** Exportar = copiar o .db atual pra um documento novo (backup). */
    fun exportTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val src = _state.value.agendaUri ?: throw IOException(str(R.string.err_no_file))
                // Flush das edições pendentes antes de copiar o arquivo.
                if (repository.dirty) {
                    try {
                        repository.persist(Uri.parse(src))
                    } catch (e: ExternalChangeException) {
                        throw e
                    }
                }
                val bytes = repository.readBytes(Uri.parse(src))
                    ?: throw IOException(str(R.string.err_file_not_found))
                val resolver = getApplication<Application>().contentResolver
                val written = withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                }
                if (!written) throw IOException(str(R.string.err_write_failed))
                _state.update { it.copy(busy = false, notice = str(R.string.msg_export_saved)) }
            } catch (e: ExternalChangeException) {
                _state.update { it.copy(busy = false, externalChange = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = str(R.string.msg_op_failed, str(R.string.op_export), e.message ?: ""),
                    )
                }
            }
        }
    }

    // ── Travar / destroy ─────────────────────────────────────────────────

    /**
     * Fecha o repo (descarta o modelo). Edições pendentes são gravadas antes
     * — se a gravação esbarrar em conflito, trava mesmo assim (não segura o
     * usuário; o conflito reaparece na próxima abertura).
     */
    fun lock() {
        saveJob?.cancel()
        val uri = _state.value.agendaUri
        if (uri != null && repository.dirty) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { repository.persist(Uri.parse(uri)) }
                repository.close()
                _state.update {
                    UiState(agendaUri = uri, biometricEnabled = biometricOn)
                }
            }
        } else {
            repository.close()
            _state.update { UiState(agendaUri = it.agendaUri, biometricEnabled = it.biometricEnabled) }
        }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        saveJob?.cancel()
        repository.close()
        super.onCleared()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Tratamento comum de falha: ExternalChange → diálogo; resto → erro. */
    private fun failSave(e: Exception, @StringRes context: Int) {
        if (e is ExternalChangeException) {
            _state.update { it.copy(externalChange = true, busy = false) }
        } else {
            _state.update {
                it.copy(
                    error = str(R.string.msg_op_failed, str(context), e.message ?: ""),
                    busy = false,
                )
            }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 2000L

        /** "YYYY-MM-DDTHH:MM" — hora de parede local, formato do desktop. */
        private val WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        /** Data/hora atual no formato de parede do desktop (eventos). */
        fun nowWallClock(): String = LocalDateTime.now().format(WALL_CLOCK)

        /** Soma `minutes` a uma hora de parede "YYYY-MM-DDTHH:MM" (novo evento). */
        fun shiftWallClock(start: String, minutes: Int): String = runCatching {
            LocalDateTime.parse(start, WALL_CLOCK).plusMinutes(minutes.toLong()).format(WALL_CLOCK)
        }.getOrDefault(start)
    }
}
