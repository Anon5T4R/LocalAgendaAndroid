package com.localagenda.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localagenda.android.ui.AgendaScreen
import com.localagenda.android.ui.AgendaViewModel

/**
 * Raiz da navegação:
 *  - Sem banco ainda (`agendaUri == null`): tela de boas-vindas com abrir/criar.
 *  - URI guardada mas trancada/`!loaded`: boas-vindas + "abrir o último banco".
 *  - Carregando: progresso.
 *  - Carregado: [AgendaScreen] com os dados e as ações da barra.
 *
 * Os gatilhos de SAF (abrir/criar documento, importar/exportar) vivem na
 * [MainActivity] e chegam como lambdas — mesmo padrão do LocalKeysAndroid.
 */
@Composable
fun LocalAgendaApp(
    viewModel: AgendaViewModel,
    onPickDocument: () -> Unit,
    onCreateDocument: () -> Unit,
    onPickImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    when {
        state.agendaUri == null -> WelcomeScreen(
            hasLast = false,
            onPickDocument = onPickDocument,
            onCreateDocument = onCreateDocument,
            onOpenLast = null,
            error = state.error,
            modifier = modifier,
        )

        !state.loaded -> {
            if (state.loading) {
                Column(
                    modifier = modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                WelcomeScreen(
                    hasLast = true,
                    onPickDocument = onPickDocument,
                    onCreateDocument = null,
                    onOpenLast = viewModel::loadLast,
                    error = state.error,
                    modifier = modifier,
                )
            }
        }

        else -> AgendaScreen(
            state = state,
            onSave = viewModel::save,
            onLock = viewModel::lock,
            onPickImport = onPickImport,
            onExport = onExport,
            onSaveEvent = viewModel::saveEvent,
            onDeleteEvent = viewModel::deleteEvent,
            onSaveTask = viewModel::saveTask,
            onDeleteTask = viewModel::deleteTask,
            onDeleteCalendar = viewModel::deleteCalendar,
            onDeleteAlarm = viewModel::deleteAlarm,
            onSaveSettings = viewModel::saveSettings,
            onToggleBiometric = viewModel::toggleBiometric,
            onNewId = viewModel::newId,
            onReloadFromDisk = viewModel::reloadFromDisk,
            onForceSave = viewModel::forceSave,
            onDismissExternalChange = viewModel::dismissExternalChange,
            onNoticeShown = viewModel::consumeNotice,
            onErrorShown = viewModel::clearError,
            modifier = modifier,
        )
    }
}

/** Primeira execução / banco trancado: abrir existente ou criar novo. */
@Composable
private fun WelcomeScreen(
    hasLast: Boolean,
    onPickDocument: () -> Unit,
    onCreateDocument: (() -> Unit)?,
    onOpenLast: (() -> Unit)?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPickDocument,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.welcome_open))
        }
        if (onCreateDocument != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCreateDocument,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.welcome_create))
            }
        }
        if (hasLast && onOpenLast != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenLast,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.welcome_open_last))
            }
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
