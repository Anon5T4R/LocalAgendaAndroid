package com.localagenda.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localagenda.android.R
import com.localagenda.android.data.AgendaEvent
import com.localagenda.android.data.Alarm
import com.localagenda.android.data.Calendar
import com.localagenda.android.data.Settings
import com.localagenda.android.data.Task
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tela principal: dados carregados do .db em seções (calendários, eventos,
 * tarefas, alarmes), barra com Salvar/Travar/Configurações + menu Importar/
 * Exportar, resumo do dia no topo, FAB pra criar evento/tarefa e o diálogo de
 * conflito de sincronização.
 *
 * Os editores completos (recorrência, lembretes, modal de evento etc.) são
 * trabalho futuro — aqui os diálogos de criação cobrem o essencial e exercem
 * o pipeline inteiro (mutação → auto-save debounced → persist no .db).
 */

/** Letras compactas dos dias da semana (0=domingo…6=sábado) pro alarme. */
private val DAY_LETTERS = listOf("D", "S", "T", "Q", "Q", "S", "S")

private val PT_BR = Locale("pt", "BR")

/** "YYYY-MM-DDTHH:MM" — hora de parede local, formato do desktop. */
private val WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

// â”€â”€ Helpers de data/hora (mesmo formato de parede do desktop) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private fun parseDateTime(raw: String): LocalDateTime? {
    val withSeconds = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    return runCatching { LocalDateTime.parse(raw, WALL_CLOCK) }.getOrElse {
        runCatching { LocalDateTime.parse(raw, withSeconds) }.getOrNull()
    }
}

private fun parseDateOnly(raw: String): LocalDate? =
    runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()

private fun formatDue(due: String): String {
    val full = DateTimeFormatter.ofPattern("EEE, d 'de' MMM Â· HH:mm", PT_BR)
    val day = DateTimeFormatter.ofPattern("EEE, d 'de' MMM", PT_BR)
    if (due.contains('T')) {
        parseDateTime(due)?.let { return it.format(full) }
    } else {
        parseDateOnly(due)?.let { return it.format(day) }
    }
    return due
}

private fun isOverdue(due: String): Boolean {
    val now = LocalDateTime.now()
    return if (due.contains('T')) {
        parseDateTime(due)?.isBefore(now) == true
    } else {
        parseDateOnly(due)?.isBefore(LocalDate.now()) == true
    }
}

private fun formatDateLong(): String {
    val s = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT_BR))
    return s.replaceFirstChar { it.titlecase(PT_BR) }
}

@Composable
private fun greetingText(): String {
    val hour = LocalDateTime.now().hour
    return when {
        hour < 12 -> stringResource(R.string.greeting_morning)
        hour < 18 -> stringResource(R.string.greeting_afternoon)
        else -> stringResource(R.string.greeting_evening)
    }
}

/** Nome de exibição do arquivo (último segmento da URI de conteúdo, sem query params). */
private fun fileDisplayName(uri: String?): String? {
    if (uri == null) return null
    val last = Uri.parse(uri).lastPathSegment ?: return null
    val decoded = runCatching { Uri.decode(last) }.getOrNull() ?: last
    // Remove query string (ex.: "?RefreshOption=...") se vier junto
    val name = decoded.substringBefore('?').substringAfterLast('/')
    return name.takeIf { it.isNotBlank() }
}

@Composable
private fun priorityColor(priority: Int): Color = when (priority) {
    1 -> Color(0xFF2E7D32)
    2 -> Color(0xFFEF6C00)
    3 -> Color(0xFFC62828)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun parseCalendarColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }
        .getOrDefault(MaterialTheme.colorScheme.primary)

// â”€â”€ Tela principal â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    state: AgendaViewModel.UiState,
    onSave: () -> Unit,
    onLock: () -> Unit,
    onPickImport: () -> Unit,
    onExport: () -> Unit,
    onSaveEvent: (AgendaEvent) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onSaveTask: (Task) -> Unit,
    onDeleteTask: (String) -> Unit,
    onDeleteCalendar: (String) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onSaveAlarm: (Alarm) -> Unit,
    onSaveSettings: (Settings) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onNewId: (String) -> String,
    onReloadFromDisk: () -> Unit,
    onForceSave: () -> Unit,
    onDismissExternalChange: () -> Unit,
    onNoticeShown: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val busy = state.busy

    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showEventDialog by rememberSaveable { mutableStateOf(false) }
    var showTaskDialog by rememberSaveable { mutableStateOf(false) }
    var showAlarmDialog by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editingEvent by rememberSaveable { mutableStateOf<AgendaEvent?>(null) }
    var editingTask by rememberSaveable { mutableStateOf<Task?>(null) }
    var editingAlarm by rememberSaveable { mutableStateOf<Alarm?>(null) }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            snackbarHostState.showSnackbar(state.notice)
            onNoticeShown()
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(6000)
            onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = fileDisplayName(state.agendaUri)
                    Text(
                        text = name ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onSave, enabled = !busy) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = stringResource(R.string.screen_save),
                        )
                    }
                    IconButton(onClick = onLock, enabled = !busy) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.screen_lock),
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.screen_settings),
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.screen_import),
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.screen_import)) },
                                onClick = {
                                    showOverflow = false
                                    onPickImport()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                                },
                                enabled = !busy,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.screen_export)) },
                                onClick = {
                                    showOverflow = false
                                    onExport()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                                },
                                enabled = !busy,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.screen_add)) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        val hasData = state.calendars.isNotEmpty() ||
            state.events.isNotEmpty() ||
            state.tasks.isNotEmpty() ||
            state.alarms.isNotEmpty()

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.error != null) {
                item { ErrorBanner(message = state.error, onDismiss = onErrorShown) }
            }
            if (state.loading) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (state.loaded) {
                item { DayHero(eventsToday = state.events.count { it.start.startsWith(LocalDate.now().toString()) }, openTasks = state.tasks.count { it.doneAt == null }) }
            }
            if (state.dirty) {
                item {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.saving_changes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            if (!hasData) {
                item { EmptyState() }
            } else {
                item { SectionHeader(stringResource(R.string.sec_calendars, state.calendars.size), state.calendars.size) }
                items(state.calendars, key = { "cal-${it.id}" }) { cal ->
                    CalendarRow(cal = cal, busy = busy, onDelete = { onDeleteCalendar(cal.id) })
                }
                item { SectionHeader(stringResource(R.string.sec_events, state.events.size), state.events.size) }
                items(state.events, key = { "ev-${it.id}" }) { ev ->
                    EventRow(
                        ev = ev,
                        accent = parseCalendarColor(
                            state.calendars.find { it.id == ev.calendarId }?.color.orEmpty()
                        ),
                        busy = busy,
                        onDelete = { onDeleteEvent(ev.id) },
                        onClick = { editingEvent = ev; showEventDialog = true },
                    )
                }
                item { SectionHeader(stringResource(R.string.sec_tasks, state.tasks.size), state.tasks.size) }
                items(state.tasks, key = { "task-${it.id}" }) { task ->
                    TaskRow(
                        task = task,
                        busy = busy,
                        onDelete = { onDeleteTask(task.id) },
                        onClick = { editingTask = task; showTaskDialog = true },
                        onToggleDone = {
                            onSaveTask(
                                task.copy(
                                    doneAt = if (task.doneAt == null) System.currentTimeMillis() else null
                                )
                            )
                        },
                    )
                }
                item { SectionHeader(stringResource(R.string.sec_alarms, state.alarms.size), state.alarms.size) }
                items(state.alarms, key = { "alarm-${it.id}" }) { alarm ->
                    AlarmRow(
                        alarm = alarm,
                        busy = busy,
                        onDelete = { onDeleteAlarm(alarm.id) },
                        onClick = { editingAlarm = alarm; showAlarmDialog = true },
                    )
                }
            }
        }
    }

    if (showNewSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showNewSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.new_item_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Card(
                    onClick = {
                        showNewSheet = false
                        showEventDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Event,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.menu_new_event),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(
                    onClick = {
                        showNewSheet = false
                        showTaskDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Task,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.menu_new_task),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(
                    onClick = {
                        showNewSheet = false
                        showAlarmDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Alarm,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.menu_new_alarm),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }

    if (showEventDialog) {
        CreateEventDialog(
            calendars = state.calendars,
            defaultDurationMin = state.settings.defaultDurationMin,
            busy = busy,
            onNewId = onNewId,
            editingEvent = editingEvent,
            onSave = { event ->
                onSaveEvent(event)
                editingEvent = null
                showEventDialog = false
            },
            onDismiss = {
                editingEvent = null
                showEventDialog = false
            },
        )
    }

    if (showTaskDialog) {
        CreateTaskDialog(
            busy = busy,
            onNewId = onNewId,
            editingTask = editingTask,
            onSave = { task ->
                onSaveTask(task)
                editingTask = null
                showTaskDialog = false
            },
            onDismiss = {
                editingTask = null
                showTaskDialog = false
            },
        )
    }

    if (showAlarmDialog) {
        CreateAlarmDialog(
            busy = busy,
            onNewId = onNewId,
            editingAlarm = editingAlarm,
            onSave = { alarm ->
                onSaveAlarm(alarm)
                editingAlarm = null
                showAlarmDialog = false
            },
            onDismiss = {
                editingAlarm = null
                showAlarmDialog = false
            },
        )
    }

if (showSettings) {
        SettingsDialog(
            settings = state.settings,
            biometricEnabled = state.biometricEnabled,
            onSaveSettings = onSaveSettings,
            onToggleBiometric = onToggleBiometric,
            onDismiss = { showSettings = false },
        )
    }

    if (state.externalChange) {
        AlertDialog(
            onDismissRequest = onDismissExternalChange,
            title = { Text(stringResource(R.string.external_change_title)) },
            text = { Text(stringResource(R.string.external_change_message)) },
            confirmButton = {
                TextButton(onClick = onReloadFromDisk) {
                    Text(stringResource(R.string.external_change_reload))
                }
            },
            dismissButton = {
                TextButton(onClick = onForceSave) {
                    Text(stringResource(R.string.external_change_overwrite))
                }
            },
        )
    }
}

// ── Componentes de conteúdo ────────────────────────────────────────────────

/** Cartão de boas-vindas: saudação + data + resumo do dia. */
@Composable
private fun DayHero(eventsToday: Int, openTasks: Int) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = greetingText(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatDateLong(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                HeroStat(stringResource(R.string.hero_events_today, eventsToday))
                Spacer(Modifier.height(8.dp))
                HeroStat(stringResource(R.string.hero_tasks_open, openTasks))
            }
        }
    }
}

@Composable
private fun HeroStat(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Banner de erro com botão de fechar (some sozinho após 6 s). */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dialog_close),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Estado vazio: ícone + título + dica. */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CalendarToday,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.empty_state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CalendarRow(cal: Calendar, busy: Boolean, onDelete: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(parseCalendarColor(cal.color)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = cal.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (!cal.visible) {
                Icon(
                    Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    ev: AgendaEvent,
    accent: Color,
    busy: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    text = ev.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatEventRange(ev.start, ev.end, ev.allDay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ev.location.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = ev.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun formatEventRange(start: String, end: String, allDay: Boolean): String {
    val full = DateTimeFormatter.ofPattern("EEE, d 'de' MMM Â· HH:mm", PT_BR)
    val day = DateTimeFormatter.ofPattern("EEE, d 'de' MMM", PT_BR)
    val timeOnly = DateTimeFormatter.ofPattern("HH:mm", PT_BR)
    if (allDay) {
        val s = parseDateOnly(start)
        return if (s != null) {
            "${s.format(day)} Â· ${stringResource(R.string.event_all_day)}"
        } else start
    }
    val s = parseDateTime(start) ?: return start
    val e = parseDateTime(end)
    return if (e != null) "${s.format(full)} – ${e.format(timeOnly)}" else s.format(full)
}

@Composable
private fun TaskRow(
    task: Task,
    busy: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
) {
    val done = task.doneAt != null
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleDone, enabled = !busy),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (task.priority > 0) priorityColor(task.priority)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (done) FontWeight.Normal else FontWeight.Medium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(2.dp))
                if (done) {
                    Text(
                        text = stringResource(R.string.task_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (task.priority > 0) {
                            val color = priorityColor(task.priority)
                            val label = when (task.priority) {
                                1 -> stringResource(R.string.prio_low)
                                2 -> stringResource(R.string.prio_medium)
                                else -> stringResource(R.string.prio_high)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = color.copy(alpha = 0.14f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Flag,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                    )
                                }
                            }
                        }
                        if (task.due.isNotBlank()) {
                            if (task.priority > 0) Spacer(Modifier.width(8.dp))
                            val overdue = isOverdue(task.due)
                            Text(
                                text = if (overdue) {
                                    "${formatDue(task.due)} Â· ${stringResource(R.string.task_overdue)}"
                                } else formatDue(task.due),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (overdue) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    busy: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val allDays = alarm.days.isEmpty()
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Alarm,
                contentDescription = null,
                tint = if (alarm.enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = alarm.time,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!alarm.enabled) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = stringResource(R.string.alarm_off),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (alarm.label.isNotBlank()) {
                    Text(
                        text = alarm.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_LETTERS.forEachIndexed { index, letter ->
                        val on = allDays || alarm.days.contains(index)
                        Surface(
                            shape = CircleShape,
                            color = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(
                                modifier = Modifier.size(22.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = letter,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Diálogo de evento (novo ou edição): título + calendário. */
@Composable
private fun CreateEventDialog(
    calendars: List<Calendar>,
    defaultDurationMin: Int,
    busy: Boolean,
    onNewId: (String) -> String,
    editingEvent: AgendaEvent?,
    onSave: (AgendaEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(editingEvent?.title ?: "") }
    var selectedCal by remember { mutableStateOf(editingEvent?.calendarId ?: calendars.firstOrNull()?.id) }
    val isEditing = editingEvent != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.event_new_title else R.string.event_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.editor_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.event_calendar_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    calendars.forEach { cal ->
                        FilterChip(
                            selected = selectedCal == cal.id,
                            onClick = { selectedCal = cal.id },
                            label = { Text(cal.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val start = editingEvent?.start ?: AgendaViewModel.nowWallClock()
                    val end = editingEvent?.end ?: AgendaViewModel.shiftWallClock(start, defaultDurationMin)
                    onSave(
                        AgendaEvent(
                            id = editingEvent?.id ?: onNewId("ev"),
                            calendarId = selectedCal ?: calendars.first().id,
                            title = title.trim(),
                            start = start,
                            end = end,
                            allDay = editingEvent?.allDay ?: false,
                            description = editingEvent?.description ?: "",
                            location = editingEvent?.location ?: "",
                            rrule = editingEvent?.rrule ?: "",
                            exdates = editingEvent?.exdates ?: emptyList(),
                            seriesId = editingEvent?.seriesId ?: "",
                            recurrenceId = editingEvent?.recurrenceId ?: "",
                            reminders = editingEvent?.reminders ?: emptyList(),
                            createdAt = editingEvent?.createdAt ?: 0,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                },
                enabled = title.isNotBlank() && calendars.isNotEmpty() && !busy,
            ) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_cancel))
            }
        },
    )
}

/** Diálogo de tarefa (nova ou edição): título + prioridade. */
@Composable
private fun CreateTaskDialog(
    busy: Boolean,
    onNewId: (String) -> String,
    editingTask: Task?,
    onSave: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(editingTask?.title ?: "") }
    var priority by remember { mutableStateOf(editingTask?.priority ?: 0) }
    val isEditing = editingTask != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.task_new_title else R.string.task_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.editor_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.task_priority_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = listOf(
                        stringResource(R.string.prio_none),
                        stringResource(R.string.prio_low),
                        stringResource(R.string.prio_medium),
                        stringResource(R.string.prio_high),
                    )
                    labels.forEachIndexed { index, label ->
                        FilterChip(
                            selected = priority == index,
                            onClick = { priority = index },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        Task(
                            id = editingTask?.id ?: onNewId("task"),
                            title = title.trim(),
                            notes = editingTask?.notes ?: "",
                            due = editingTask?.due ?: "",
                            priority = priority,
                            rrule = editingTask?.rrule ?: "",
                            reminders = editingTask?.reminders ?: emptyList(),
                            parentId = editingTask?.parentId ?: "",
                            doneAt = editingTask?.doneAt,
                            sort = editingTask?.sort ?: 0,
                            createdAt = editingTask?.createdAt ?: 0,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                },
                enabled = title.isNotBlank() && !busy,
            ) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_cancel))
            }
        },
    )
}

/** Diálogo de alarme (novo ou edição): hora, rótulo, dias, ativado. */
@Composable
private fun CreateAlarmDialog(
    busy: Boolean,
    onNewId: (String) -> String,
    editingAlarm: Alarm?,
    onSave: (Alarm) -> Unit,
    onDismiss: () -> Unit,
) {
    var time by remember { mutableStateOf(editingAlarm?.time ?: "08:00") }
    var label by remember { mutableStateOf(editingAlarm?.label ?: "") }
    var days by remember { mutableStateOf(editingAlarm?.days?.toMutableList() ?: mutableListOf<Int>()) }
    var enabled by remember { mutableStateOf(editingAlarm?.enabled ?: true) }
    val isEditing = editingAlarm != null

AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.alarm_edit_title else R.string.alarm_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text(stringResource(R.string.alarm_time_label)) },
                    placeholder = { Text("HH:mm") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().width(200.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.alarm_label_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.alarm_days_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        R.string.alarm_day_d to 0,
                        R.string.alarm_day_s to 1,
                        R.string.alarm_day_t to 2,
                        R.string.alarm_day_q to 3,
                        R.string.alarm_day_q2 to 4,
                        R.string.alarm_day_s2 to 5,
                        R.string.alarm_day_s3 to 6,
                    ).forEach { (stringRes, dayIndex) ->
                        val on = days.contains(dayIndex)
                        FilterChip(
                            selected = on,
                            onClick = {
                                if (on) days.remove(dayIndex) else days.add(dayIndex)
                                days.sort()
                            },
                            label = { Text(stringResource(stringRes)) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.alarm_enabled_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        Alarm(
                            id = editingAlarm?.id ?: onNewId("alarm"),
                            time = time,
                            label = label.trim(),
                            days = days.toList(),
                            enabled = enabled,
                            sort = editingAlarm?.sort ?: 0,
                        )
                    )
                },
                enabled = time.isNotBlank() && !busy,
            ) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_cancel))
            }
        },
    )
}

/**
 * Configurações do APP: vão pro .db (meta.settings) e sincronizam sozinhas.
 * O tema de "Sistema/Claro/Escuro" é o subconjunto que o Android entende —
 * os temas nomeados do desktop ficam pra depois. A biometria é preferência
 * LOCAL (DataStore).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    settings: Settings,
    biometricEnabled: Boolean,
    onSaveSettings: (Settings) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        stringResource(R.string.theme_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )
    val values = listOf("system", "light", "dark")
    val selectedIndex = values.indexOf(settings.theme).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = selectedIndex == index,
                            onClick = { onSaveSettings(settings.copy(theme = values[index])) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) {
                            Text(label)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.biometric_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.biometric_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = onToggleBiometric,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )
}



