package com.localagenda.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.localagenda.android.ui.AgendaViewModel
import com.localagenda.android.ui.theme.LocalAgendaTheme

class MainActivity : FragmentActivity() {

    private val viewModel: AgendaViewModel by viewModels()

    /** API 33+: notificações de alarme/lembrete precisam de permissão em runtime. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Abrir um .db existente (ACTION_OPEN_DOCUMENT). */
    private val openDbLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistableUriPermission(uri)
                viewModel.onDocumentChosen(uri)
            }
        }

    /** Criar um .db novo (ACTION_CREATE_DOCUMENT). */
    private val createDbLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri ->
            if (uri != null) {
                takePersistableUriPermission(uri)
                viewModel.onCreatedDocument(uri)
            }
        }

    /** Importar: adotar outro .db como agenda. */
    private val importLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistableUriPermission(uri)
                viewModel.onImportChosen(uri)
            }
        }

    /** Exportar: salvar uma cópia do .db atual. */
    private val exportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri ->
            if (uri != null) viewModel.exportTo(uri)
        }

    /** Documentos escolhidos no SAF vêm com leitura+escrita; torna persistente. */
    private fun takePersistableUriPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val state by viewModel.state.collectAsState()
            val darkTheme = when (state.settings.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            LocalAgendaTheme(darkTheme = darkTheme) {
                LocalAgendaApp(
                    viewModel = viewModel,
                    onPickDocument = {
                        openDbLauncher.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "*/*"))
                    },
                    onCreateDocument = {
                        createDbLauncher.launch("agenda.db")
                    },
                    onPickImport = {
                        importLauncher.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "*/*"))
                    },
                    onExport = {
                        exportLauncher.launch("agenda-backup.db")
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncOnResume()
    }
}
