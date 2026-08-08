package com.localagenda.android.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * O arquivo no disco mudou desde a última leitura/gravação conhecida (ex.: o
 * OneDrive/Google Drive sincronizou uma versão nova vinda do desktop). Lançada
 * por [AgendaRepository.persist] ANTES de gravar, para nunca sobrescrever em
 * silêncio o trabalho do outro dispositivo. A UI oferece recarregar ou
 * sobrescrever (mesmo papel do [ExternalChangeException] do LocalKeys).
 */
class ExternalChangeException : IOException("o arquivo foi modificado fora do app")

/**
 * Único ponto de acesso ao banco SQLite do LocalAgenda, que vive num documento
 * SAF (OneDrive/Google Drive/local) escolhido pelo usuário — o MESMO arquivo
 * que o desktop lê/grava (schema de src-tauri/src/db.rs, versão 2).
 *
 * ## Por que SEM conexão longa?
 *
 * Um documento SAF é um arquivo que OUTRO dispositivo pode substituir por cima
 * (o provedor de nuvem baixa uma versão nova). Uma conexão SQLite mantida
 * aberta continuaria gravando no inode ANTIGO (que o provedor trocou) e as
 * gravações sumiriam. Por isso:
 *
 *  - `readAll` (open/reload) e `writeAll` (persist) ABREM uma conexão nova —
 *    fd fresco via [ContentResolver.openFileDescriptor] — e fecham em seguida.
 *    Cada gravação toca o arquivo atual, nunca um inode órfão.
 *  - `journal_mode=DELETE` (nunca WAL): o WAL precisaria de sidecars
 *    `-wal`/`-shm` ao lado do documento, que o SAF não deixa criar. No modo
 *    DELETE o arquivo único fica íntegro e sem sidecar após cada commit.
 *  - Antes de gravar, [externalChangeDetected] compara os bytes atuais do
 *    disco com a última versão conhecida (com fallback pro mtime do provedor)
 *    e lança [ExternalChangeException] em vez de sobrescrever — a menos que o
 *    usuário confirme com `force = true`.
 *
 * ## Modelo de escrita
 *
 * As funções CRUD (suspend) mutam o modelo em memória e marcam `dirty`; o
 * [AgendaRepository.persist] reescreve as 4 tabelas num único transaction —
 * o arquivo inteiro é re-gerado do estado em memória (mesma filosofia do blob
 * do LocalKeys, com a conveniência de o formato ser SQLite legível).
 */
class AgendaRepository(private val resolver: ContentResolver) {

    var calendars: List<Calendar> = emptyList()
        private set
    var events: List<AgendaEvent> = emptyList()
        private set
    var tasks: List<Task> = emptyList()
        private set
    var alarms: List<Alarm> = emptyList()
        private set
    var settings: Settings = Settings.DEFAULT
        private set

    /** Há mutação não persistida no modelo em memória. */
    var dirty: Boolean = false
        private set

    /** Baseline da detecção de mudança externa (bytes + mtime do disco). */
    private var lastKnownBytes: ByteArray? = null
    private var lastKnownModified: Long? = null

    private val idCounter = AtomicLong(0)

    companion object {
        const val SCHEMA_VERSION = 3L
        private val SQLITE_MAGIC = byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────

    /** Abre (ou cria o schema, se o arquivo for novo) e carrega tudo em memória. */
    suspend fun open(uri: Uri) = withContext(Dispatchers.IO) { readAll(uri) }

    /** Relê o arquivo do disco, adotando a versão externa. */
    suspend fun reload(uri: Uri) = withContext(Dispatchers.IO) { readAll(uri) }

    /**
     * Grava o estado em memória no documento. Antes de gravar detecta mudança
     * externa ([ExternalChangeException] a menos que `force = true`); depois
     * relê e confere que o arquivo gravado é um SQLite válido.
     */
    suspend fun persist(uri: Uri, force: Boolean = false) =
        withContext(Dispatchers.IO) { writeAll(uri, force) }

    /** Descarta o modelo (lock do app / destroy). Nada é gravado. */
    fun close() {
        calendars = emptyList()
        events = emptyList()
        tasks = emptyList()
        alarms = emptyList()
        settings = Settings.DEFAULT
        lastKnownBytes = null
        lastKnownModified = null
        dirty = false
    }

    /** Confere que o arquivo é uma base do LocalAgenda (probe do import). */
    fun verifyIsAgendaDb(uri: Uri): Boolean = runCatching {
        var ok = false
        withDb(uri) { db ->
            db.rawQuery("SELECT COUNT(*) FROM calendars", null).use { ok = it.moveToFirst() }
        }
        ok
    }.getOrDefault(false)

    /** Lê os bytes atuais do documento (export). */
    suspend fun readBytes(uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) { readBytesInternal(uri) }

    // ── CRUD (suspend; mutam o modelo em memória e marcam dirty) ─────────

    suspend fun upsertCalendar(cal: Calendar) {
        val exists = calendars.any { it.id == cal.id }
        calendars = if (exists) calendars.map { if (it.id == cal.id) cal else it } else calendars + cal
        dirty = true
    }

    /** Apaga o calendário e os eventos dele. Nunca deixa a lista vazia. */
    suspend fun deleteCalendar(id: String) {
        if (calendars.size <= 1) return
        if (calendars.none { it.id == id }) return
        calendars = calendars.filterNot { it.id == id }
        events = events.filterNot { it.calendarId == id }
        dirty = true
    }

    suspend fun upsertEvent(event: AgendaEvent) {
        val now = System.currentTimeMillis()
        val finalEv = if (event.createdAt == 0L) {
            event.copy(createdAt = now, updatedAt = now)
        } else {
            event.copy(updatedAt = now)
        }
        val exists = events.any { it.id == finalEv.id }
        events = if (exists) events.map { if (it.id == finalEv.id) finalEv else it } else events + finalEv
        dirty = true
    }

    /** Apaga a série e as exceções dela juntas (mesma regra do db.rs). */
    suspend fun deleteEvent(id: String) {
        val before = events.size
        events = events.filterNot { it.id == id || it.seriesId == id }
        if (events.size != before) dirty = true
    }

    suspend fun upsertTask(task: Task) {
        val now = System.currentTimeMillis()
        val finalTask = if (task.createdAt == 0L) {
            task.copy(createdAt = now, updatedAt = now)
        } else {
            task.copy(updatedAt = now)
        }
        val exists = tasks.any { it.id == finalTask.id }
        tasks = if (exists) tasks.map { if (it.id == finalTask.id) finalTask else it } else tasks + finalTask
        dirty = true
    }

    /** Apaga a tarefa e as subtarefas dela (mesma regra do db.rs). */
    suspend fun deleteTask(id: String) {
        val before = tasks.size
        tasks = tasks.filterNot { it.id == id || it.parentId == id }
        if (tasks.size != before) dirty = true
    }

    suspend fun upsertAlarm(alarm: Alarm) {
        val exists = alarms.any { it.id == alarm.id }
        alarms = if (exists) alarms.map { if (it.id == alarm.id) alarm else it } else alarms + alarm
        dirty = true
    }

    suspend fun deleteAlarm(id: String) {
        val before = alarms.size
        alarms = alarms.filterNot { it.id == id }
        if (alarms.size != before) dirty = true
    }

    /** Configurações do APP: vão pro blob `meta.settings` do próprio .db. */
    suspend fun updateSettings(settings: Settings) {
        this.settings = settings
        dirty = true
    }

    /**
     * ID curto e estável, monotônico (nanos + contador), mesmo esquema do
     * `gen_id` do db.rs — o front do desktop usa `crypto.randomUUID`, e IDs
     * diferentes por dispositivo são o esperado.
     */
    fun genId(prefix: String): String {
        val nanos = System.nanoTime()
        val c = idCounter.getAndIncrement()
        return "${prefix}_${java.lang.Long.toHexString(nanos)}${java.lang.Long.toHexString(c)}"
    }

    // ── Leitura completa ─────────────────────────────────────────────────

    private fun readAll(uri: Uri) {
        // Primeiro tenta ler sem criar o calendário padrão
        val tempFile = File.createTempFile("agenda_", ".db")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openDatabase(
                    tempFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
                db.rawQuery("PRAGMA journal_mode = DELETE", null).use { }
                db.rawQuery("PRAGMA foreign_keys = ON", null).use { }
                ensureSchema(db)
                calendars = queryCalendars(db)
                events = queryEvents(db)
                tasks = queryTasks(db)
                alarms = queryAlarms(db)
                settings = querySettings(db)
            } finally {
                db?.close()
            }
        } finally {
            tempFile.delete()
        }
        // Se não há calendários, semeia via writeAll (que copia de volta pro SAF)
        if (calendars.isEmpty()) {
            val cal = Calendar(id = genId("cal"), name = "Pessoal", color = "#2563eb")
            calendars = listOf(cal)
            dirty = true
            // writeAll vai copiar pro SAF
            writeAll(uri, force = true)
            return
        }
        lastKnownBytes = readBytesInternal(uri)
        lastKnownModified = queryLastModified(uri)
        dirty = false
    }

    // ── Gravação completa (auto-checkpoint) ──────────────────────────────

    private fun writeAll(uri: Uri, force: Boolean) {
        if (!force && externalChangeDetected(uri)) throw ExternalChangeException()
        val tempFile = File.createTempFile("agenda_", ".db")
        try {
            // Copia SAF -> temp (para preservar o conteúdo atual se for update)
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openDatabase(
                    tempFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
                db.rawQuery("PRAGMA journal_mode = DELETE", null).use { }
                db.rawQuery("PRAGMA foreign_keys = ON", null).use { }
                writeAllData(db)
                runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { } }
            } finally {
                db?.close()
            }
            // Copia temp -> SAF (sobrescreve o documento)
            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            // Sanidade pós-gravação
            val bytes = readBytesInternal(uri)
                ?: throw IOException("não foi possível reler o arquivo gravado")
            if (!isSqliteHeader(bytes)) throw IOException("arquivo gravado não é um SQLite válido")
            lastKnownBytes = bytes
            lastKnownModified = queryLastModified(uri)
            dirty = false
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Copia o documento SAF para um arquivo temporário, abre o SQLite nele,
     * executa `block` e copia de volta se houver escrita (o caller controla
     * via `persist`). Isso evita problemas de WAL/sidecars e incompatibilidade
     * de API do openDatabase(ParcelFileDescriptor).
     */
    private inline fun <T> withDb(uri: Uri, block: (SQLiteDatabase) -> T): T {
        val tempFile = File.createTempFile("agenda_", ".db")
        try {
            // Copia SAF -> temp
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openDatabase(
                    tempFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
                db.rawQuery("PRAGMA journal_mode = DELETE", null).use { }
                db.rawQuery("PRAGMA foreign_keys = ON", null).use { }
                return block(db)
            } finally {
                db?.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    /** Mudança externa = bytes atuais do disco ≠ última versão conhecida. */
    private fun externalChangeDetected(uri: Uri): Boolean {
        val known = lastKnownBytes ?: return false
        val current = readBytesInternal(uri)
        return when {
            current != null -> !current.contentEquals(known)
            // Provedor que não deixa reler bytes: cai pro mtime.
            else -> {
                val diskMtime = queryLastModified(uri)
                val lastMtime = lastKnownModified
                diskMtime != null && lastMtime != null && diskMtime != lastMtime
            }
        }
    }

    // ── Schema (espelho do db.rs) ────────────────────────────────────────

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS calendars (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                color TEXT NOT NULL,
                visible INTEGER NOT NULL DEFAULT 1,
                sort INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS events (
                id TEXT PRIMARY KEY,
                calendar_id TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                start TEXT NOT NULL,
                "end" TEXT NOT NULL,
                all_day INTEGER NOT NULL DEFAULT 0,
                rrule TEXT NOT NULL DEFAULT '',
                exdates TEXT NOT NULL DEFAULT '[]',
                series_id TEXT NOT NULL DEFAULT '',
                recurrence_id TEXT NOT NULL DEFAULT '',
                reminders TEXT NOT NULL DEFAULT '[]',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                due TEXT NOT NULL DEFAULT '',
                priority INTEGER NOT NULL DEFAULT 0,
                rrule TEXT NOT NULL DEFAULT '',
                reminders TEXT NOT NULL DEFAULT '[]',
                parent_id TEXT NOT NULL DEFAULT '',
                done_at INTEGER,
                sort INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS reminders (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                ref_id TEXT NOT NULL DEFAULT '',
                occ TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                fire_at INTEGER NOT NULL,
                fired INTEGER NOT NULL DEFAULT 0,
                snoozed INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS alarms (
                id TEXT PRIMARY KEY,
                time TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                days TEXT NOT NULL DEFAULT '[]',
                enabled INTEGER NOT NULL DEFAULT 1,
                sort INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_cal ON events(calendar_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reminders_due ON reminders(fired, fire_at)")

        // Versão + migração (v1 → v2: series_id/recurrence_id nos events).
        val ver = queryString(db, "SELECT value FROM meta WHERE key='schema_version'")?.toLongOrNull()
        when {
            ver == null -> db.execSQL(
                "INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', ?)",
                arrayOf(SCHEMA_VERSION.toString()),
            )
            ver < SCHEMA_VERSION -> {
                migrate(db, ver)
                db.execSQL(
                    "UPDATE meta SET value=? WHERE key='schema_version'",
                    arrayOf(SCHEMA_VERSION.toString()),
                )
            }
        }
        // Índice de coluna NOVA só depois da migração (mesmo motivo do db.rs).
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_series ON events(series_id)")
    }

    /**
     * Só ADD COLUMN com DEFAULT: nenhuma linha existente é reescrita. Espelho
     * da `migrate` do db.rs do desktop (v1→v2 events.series_id/recurrence_id;
     * v2→v3 reminders.snoozed).
     */
    private fun migrate(db: SQLiteDatabase, from: Long) {
        if (from < 2) {
            for (col in listOf("series_id", "recurrence_id")) {
                if (!hasColumn(db, "events", col)) {
                    db.execSQL("ALTER TABLE events ADD COLUMN $col TEXT NOT NULL DEFAULT ''")
                }
            }
        }
        if (from < 3) {
            if (!hasColumn(db, "reminders", "snoozed")) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN snoozed INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == column) return@use true
            }
            false
        }

    // ── Queries ──────────────────────────────────────────────────────────

    private fun queryCalendars(db: SQLiteDatabase): List<Calendar> =
        db.rawQuery(
            "SELECT id, name, color, visible, sort FROM calendars ORDER BY sort, name",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Calendar(
                            id = c.getString(0),
                            name = c.getString(1),
                            color = c.getString(2),
                            visible = c.getInt(3) != 0,
                            sort = c.getInt(4),
                        )
                    )
                }
            }
        }

    private fun queryEvents(db: SQLiteDatabase): List<AgendaEvent> =
        db.rawQuery(
            """SELECT id, calendar_id, title, description, location, start, "end", all_day,
               rrule, exdates, series_id, recurrence_id, reminders, created_at, updated_at
               FROM events""",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        AgendaEvent(
                            id = c.getString(0),
                            calendarId = c.getString(1),
                            title = c.getString(2),
                            description = c.getString(3),
                            location = c.getString(4),
                            start = c.getString(5),
                            end = c.getString(6),
                            allDay = c.getInt(7) != 0,
                            rrule = c.getString(8),
                            exdates = JsonLists.decodeStrings(c.getString(9)),
                            seriesId = c.getString(10),
                            recurrenceId = c.getString(11),
                            reminders = JsonLists.decode(c.getString(12)),
                            createdAt = c.getLong(13),
                            updatedAt = c.getLong(14),
                        )
                    )
                }
            }
        }

    private fun queryTasks(db: SQLiteDatabase): List<Task> =
        db.rawQuery(
            """SELECT id, title, notes, due, priority, rrule, reminders, parent_id, done_at,
               sort, created_at, updated_at FROM tasks ORDER BY sort, created_at""",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Task(
                            id = c.getString(0),
                            title = c.getString(1),
                            notes = c.getString(2),
                            due = c.getString(3),
                            priority = c.getInt(4),
                            rrule = c.getString(5),
                            reminders = JsonLists.decode(c.getString(6)),
                            parentId = c.getString(7),
                            doneAt = if (c.isNull(8)) null else c.getLong(8),
                            sort = c.getInt(9),
                            createdAt = c.getLong(10),
                            updatedAt = c.getLong(11),
                        )
                    )
                }
            }
        }

    private fun queryAlarms(db: SQLiteDatabase): List<Alarm> =
        db.rawQuery(
            "SELECT id, time, label, days, enabled, sort FROM alarms ORDER BY sort, time",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Alarm(
                            id = c.getString(0),
                            time = c.getString(1),
                            label = c.getString(2),
                            days = JsonLists.decode(c.getString(3)),
                            enabled = c.getInt(4) != 0,
                            sort = c.getInt(5),
                        )
                    )
                }
            }
        }

    private fun querySettings(db: SQLiteDatabase): Settings {
        val raw = queryString(db, "SELECT value FROM meta WHERE key='settings'")
            ?: return Settings.DEFAULT
        return Settings.fromBlob(raw)
    }

    private fun queryString(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }

    // ── Escrita completa ─────────────────────────────────────────────────

    /** Reescreve as 4 tabelas + meta num único transaction (rollback-seguro). */
    private fun writeAllData(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.delete("calendars", null, null)
            db.delete("events", null, null)
            db.delete("tasks", null, null)
            db.delete("alarms", null, null)
            calendars.forEach { insertCalendar(db, it) }
            events.forEach { insertEvent(db, it) }
            tasks.forEach { insertTask(db, it) }
            alarms.forEach { insertAlarm(db, it) }

            // Settings: merge preservando chaves que o desktop entende.
            val raw = queryString(db, "SELECT value FROM meta WHERE key='settings'")
            db.execSQL(
                "INSERT OR REPLACE INTO meta(key, value) VALUES('settings', ?)",
                arrayOf(settings.toBlob(raw)),
            )

            // Lembretes materializados pelo desktop: limpa órfãos (a mesma
            // limpeza que o db.rs faz no delete de evento/tarefa/alarme).
            db.execSQL(
                "DELETE FROM reminders WHERE (kind='event' AND ref_id NOT IN (SELECT id FROM events))" +
                    " OR (kind='task' AND ref_id NOT IN (SELECT id FROM tasks))" +
                    " OR (kind='alarm' AND ref_id NOT IN (SELECT id FROM alarms))",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertCalendar(db: SQLiteDatabase, c: Calendar) {
        db.execSQL(
            "INSERT OR REPLACE INTO calendars(id, name, color, visible, sort) VALUES(?,?,?,?,?)",
            arrayOf(c.id, c.name, c.color, boolToInt(c.visible), c.sort),
        )
    }

    private fun insertEvent(db: SQLiteDatabase, ev: AgendaEvent) {
        // `"end"` entre aspas: END é palavra reservada do SQLite (igual ao db.rs).
        db.execSQL(
            """INSERT OR REPLACE INTO events(
                   id, calendar_id, title, description, location, start, "end", all_day,
                   rrule, exdates, series_id, recurrence_id, reminders, created_at, updated_at
               ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(
                ev.id, ev.calendarId, ev.title, ev.description, ev.location, ev.start, ev.end,
                boolToInt(ev.allDay), ev.rrule,
                JsonLists.encodeStrings(ev.exdates),
                ev.seriesId, ev.recurrenceId,
                JsonLists.encode(ev.reminders),
                ev.createdAt, ev.updatedAt,
            ),
        )
    }

    private fun insertTask(db: SQLiteDatabase, t: Task) {
        db.execSQL(
            """INSERT OR REPLACE INTO tasks(
                   id, title, notes, due, priority, rrule, reminders, parent_id, done_at,
                   sort, created_at, updated_at
               ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(
                t.id, t.title, t.notes, t.due, t.priority, t.rrule,
                JsonLists.encode(t.reminders), t.parentId, t.doneAt, t.sort,
                t.createdAt, t.updatedAt,
            ),
        )
    }

    private fun insertAlarm(db: SQLiteDatabase, a: Alarm) {
        db.execSQL(
            "INSERT OR REPLACE INTO alarms(id, time, label, days, enabled, sort) VALUES(?,?,?,?,?,?)",
            arrayOf(a.id, a.time, a.label, JsonLists.encode(a.days), boolToInt(a.enabled), a.sort),
        )
    }

    // ── Helpers de I/O ───────────────────────────────────────────────────

    private fun readBytesInternal(uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /** mtime do documento (coluna padrão dos providers de documentos). */
    private fun queryLastModified(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }.getOrNull()

    private fun isSqliteHeader(bytes: ByteArray): Boolean =
        bytes.size >= SQLITE_MAGIC.size &&
            bytes.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)

    private fun boolToInt(b: Boolean): Int = if (b) 1 else 0
}
