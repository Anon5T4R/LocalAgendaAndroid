package com.localagenda.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.localagenda.android.R
import com.localagenda.android.data.AgendaEvent
import com.localagenda.android.data.Alarm
import com.localagenda.android.data.Calendar
import com.localagenda.android.data.Settings
import com.localagenda.android.data.Task
import kotlinx.coroutines.delay

/**
 * Tela principal: dados carregados do .db em seções (calendários, eventos,
 * tarefas, alarmes), barra com Importar/Exportar/Salvar/Travar, FAB pra criar
 * evento/tarefa e o diálogo de conflito de sincronização.
 *
 * Os editores completos (recorrência, lembretes, modal de evento etc.) são
 * trabalho futuro — aqui os diálogos de criação cobrem o essencial e exercem
 * o pipeline inteiro (mutação → auto-save debounced → persist no .db).
 */
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

    var fabOpen by remember { mutableStateOf(false) }
    var showEventDialog by rememberSaveable { mutableStateOf(false) }
    var showTaskDialog by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onPickImport, enabled = !busy) {
                        Text(stringResource(R.string.screen_import))
                    }
                    TextButton(onClick = onExport, enabled = !busy) {
                        Text(stringResource(R.string.screen_export))
                    }
                    TextButton(onClick = onSave, enabled = !busy) {
                        Text(stringResource(R.string.screen_save))
                    }
                    TextButton(onClick = onLock, enabled = !busy) {
                        Text(stringResource(R.string.screen_lock))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.screen_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.screen_add))
                }
                DropdownMenu(expanded = fabOpen, onDismissRequest = { fabOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_new_event)) },
                        onClick = {
                            fabOpen = false
                            showEventDialog = true
                        },
                        leadingIcon = { Icon(Icons.Filled.Event, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_new_task)) },
                        onClick = {
                            fabOpen = false
                            showTaskDialog = true
                        },
                        leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                    )
                }
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        val hasData = state.calendars.isNotEmpty() ||
            state.events.isNotEmpty() ||
            state.tasks.isNotEmpty() ||
            state.alarms.isNotEmpty()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.dirty) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.dirty_unsaved),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (state.error != null) {
                item {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!hasData) {
                item {
                    Text(
                        text = stringResource(R.string.empty_state),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                item { SectionHeader(stringResource(R.string.sec_calendars, state.calendars.size)) }
                items(state.calendars, key = { "cal-${it.id}" }) { cal ->
                    CalendarRow(cal = cal, busy = busy, onDelete = { onDeleteCalendar(cal.id) })
                }
                item { SectionHeader(stringResource(R.string.sec_events, state.events.size)) }
                items(state.events, key = { "ev-${it.id}" }) { ev ->
                    EventRow(ev = ev, busy = busy, onDelete = { onDeleteEvent(ev.id) })
                }
                item { SectionHeader(stringResource(R.string.sec_tasks, state.tasks.size)) }
                items(state.tasks, key = { "task-${it.id}" }) { task ->
                    TaskRow(task = task, busy = busy, onDelete = { onDeleteTask(task.id) })
                }
                item { SectionHeader(stringResource(R.string.sec_alarms, state.alarms.size)) }
                items(state.alarms, key = { "alarm-${it.id}" }) { alarm ->
                    AlarmRow(alarm = alarm, busy = busy, onDelete = { onDeleteAlarm(alarm.id) })
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
            onSave = { event ->
                onSaveEvent(event)
                showEventDialog = false
            },
            onDismiss = { showEventDialog = false },
        )
    }

    if (showTaskDialog) {
        CreateTaskDialog(
            busy = busy,
            onNewId = onNewId,
            onSave = { task ->
                onSaveTask(task)
                showTaskDialog = false
            },
            onDismiss = { showTaskDialog = false },
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun CalendarRow(cal: Calendar, busy: Boolean, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dot = runCatching { Color(android.graphics.Color.parseColor(cal.color)) }
                .getOrDefault(MaterialTheme.colorScheme.primary)
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = cal.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (!cal.visible) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun EventRow(ev: AgendaEvent, busy: Boolean, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = ev.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${ev.start} — ${ev.end}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, busy: Boolean, onDelete: () -> Unit) {
    val prioLabel = when (task.priority) {
        1 -> stringResource(R.string.prio_low)
        2 -> stringResource(R.string.prio_medium)
        3 -> stringResource(R.string.prio_high)
        else -> stringResource(R.string.prio_none)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (task.doneAt != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.doneAt != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (task.doneAt != null) stringResource(R.string.task_done) else prioLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, busy: Boolean, onDelete: () -> Unit) {
    val daysLabel = if (alarm.days.isEmpty()) {
        stringResource(R.string.alarm_daily)
    } else {
        alarm.days.sorted().joinToString(", ")
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = alarm.time,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(64.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                if (alarm.label.isNotBlank()) {
                    Text(text = alarm.label, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = daysLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!alarm.enabled) {
                Text(
                    text = "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

/** Diálogo mínimo de evento novo: título + calendário (o resto é editor futuro). */
@Composable
private fun CreateEventDialog(
    calendars: List<Calendar>,
    defaultDurationMin: Int,
    busy: Boolean,
    onNewId: (String) -> String,
    onSave: (AgendaEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedCal by remember { mutableStateOf(calendars.firstOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_new_title)) },
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
                    val start = AgendaViewModel.nowWallClock()
                    onSave(
                        AgendaEvent(
                            id = onNewId("ev"),
                            calendarId = selectedCal ?: calendars.first().id,
                            title = title.trim(),
                            start = start,
                            end = AgendaViewModel.shiftWallClock(start, defaultDurationMin),
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

/** Diálogo mínimo de tarefa nova: título + prioridade. */
@Composable
private fun CreateTaskDialog(
    busy: Boolean,
    onNewId: (String) -> String,
    onSave: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_new_title)) },
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
                            id = onNewId("task"),
                            title = title.trim(),
                            priority = priority,
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
