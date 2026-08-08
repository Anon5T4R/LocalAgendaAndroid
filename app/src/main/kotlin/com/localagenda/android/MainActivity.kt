package com.localagenda.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.localagenda.android.ui.AgendaViewModel
import com.localagenda.android.ui.theme.LocalAgendaTheme

class MainActivity : FragmentActivity() {

    private val viewModel: AgendaViewModel by viewModels()

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
        setContent {
            LocalAgendaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
