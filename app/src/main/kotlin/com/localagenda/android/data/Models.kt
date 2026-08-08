package com.localagenda.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modelos espelhando o desktop (src/lib/types.ts, camelCase) e as tabelas do
 * banco (src-tauri/src/db.rs). O banco é o MESMO arquivo SQLite que o desktop
 * lê/grava — por isso cada campo reflete a coluna exata e a serialização JSON
 * do [Settings] usa as mesmas chaves do blob `meta.settings` do desktop.
 *
 * Fusos: tudo em hora local, sem TZ — datas/horas são strings de parede
 * ("YYYY-MM-DDTHH:MM" ou "YYYY-MM-DD" pro dia inteiro), igual ao desktop.
 */

/** `calendars` — cor é hex "#rrggbb" (mesma paleta do desktop). */
data class Calendar(
    val id: String,
    val name: String,
    val color: String,
    val visible: Boolean = true,
    val sort: Int = 0,
)

/**
 * `events` — espelho do `Event` do Rust. `rrule` vazia = evento único;
 * `seriesId`/`recurrenceId` = exceção de série (RECURRENCE-ID do iCal).
 */
data class AgendaEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    /** Hora de parede local: "YYYY-MM-DDTHH:MM" ou "YYYY-MM-DD" (dia inteiro). */
    val start: String,
    val end: String,
    val allDay: Boolean = false,
    /** RRULE RFC 5545 sem DTSTART; "" = evento único. */
    val rrule: String = "",
    /** Ocorrências CANCELADAS da série (EXDATE): início ISO de cada uma. */
    val exdates: List<String> = emptyList(),
    /** Série que esta exceção substitui ("" = não é exceção). */
    val seriesId: String = "",
    /** Chave da ocorrência ORIGINAL substituída (RECURRENCE-ID do iCal). */
    val recurrenceId: String = "",
    /** Lembretes: minutos antes do início. */
    val reminders: List<Int> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** `tasks` — `doneAt` null = aberta; `parentId` preenchido = subtarefa. */
data class Task(
    val id: String,
    val title: String,
    val notes: String = "",
    /** Prazo ISO local (ou data pura); "" = sem prazo. */
    val due: String = "",
    /** 0 nenhuma · 1 baixa · 2 média · 3 alta. */
    val priority: Int = 0,
    val rrule: String = "",
    val reminders: List<Int> = emptyList(),
    val parentId: String = "",
    /** Epoch-ms de conclusão; null = aberta. */
    val doneAt: Long? = null,
    val sort: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** `alarms` — dias da semana (0=domingo…6=sábado); vazio = todo dia. */
data class Alarm(
    val id: String,
    /** "HH:MM" local. */
    val time: String,
    val label: String = "",
    val days: List<Int> = emptyList(),
    val enabled: Boolean = true,
    val sort: Int = 0,
)

/**
 * Configurações — blob JSON em `meta` (chave "settings") do próprio .db, igual
 * ao desktop. Por isso elas "sincronizam sozinhas": estão no arquivo, não numa
 * preferência local. O Android só expõe no UI o subconjunto que faz sentido
 * num celular (tema, biometria local), mas o blob preserva as chaves que o
 * desktop entende (closeToTray, modelDir, nGpuLayers…).
 */
data class Settings(
    val theme: String = "system",
    val closeToTray: Boolean = true,
    /** Domingo=0 … Sábado=6. Padrão: domingo. */
    val firstDayOfWeek: Int = 0,
    /** Duração padrão de um evento novo, em minutos. */
    val defaultDurationMin: Int = 60,
    val dailySummary: Boolean = false,
    /** Horário do resumo do dia, "HH:MM". */
    val dailySummaryTime: String = "08:00",
    /** Pasta dos modelos GGUF (IA) — ignorada no Android por enquanto. */
    val modelDir: String = "",
    /** Camadas na GPU pro llama (0 = só CPU). */
    val nGpuLayers: Int = 0,
    val soundEnabled: Boolean = true,
    /** Volume do som (0..1). */
    val soundVolume: Double = 0.7,
) {

    /**
     * Escreve ESTE objeto por cima do blob existente preservando chaves
     * desconhecidas (o desktop faz merge — `set_setting_bool` em db.rs).
     */
    fun toBlob(existing: String?): String {
        val obj = existing?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        obj.put("theme", theme)
        obj.put("closeToTray", closeToTray)
        obj.put("firstDayOfWeek", firstDayOfWeek)
        obj.put("defaultDurationMin", defaultDurationMin)
        obj.put("dailySummary", dailySummary)
        obj.put("dailySummaryTime", dailySummaryTime)
        obj.put("modelDir", modelDir)
        obj.put("nGpuLayers", nGpuLayers)
        obj.put("soundEnabled", soundEnabled)
        obj.put("soundVolume", soundVolume)
        return obj.toString()
    }

    companion object {
        val DEFAULT = Settings()

        /** Lê o blob do `meta.settings`; chaves ausentes viram o padrão. */
        fun fromBlob(raw: String): Settings {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return DEFAULT
            return Settings(
                theme = o.optString("theme", DEFAULT.theme),
                closeToTray = o.optBoolean("closeToTray", DEFAULT.closeToTray),
                firstDayOfWeek = o.optInt("firstDayOfWeek", DEFAULT.firstDayOfWeek),
                defaultDurationMin = o.optInt("defaultDurationMin", DEFAULT.defaultDurationMin),
                dailySummary = o.optBoolean("dailySummary", DEFAULT.dailySummary),
                dailySummaryTime = o.optString("dailySummaryTime", DEFAULT.dailySummaryTime),
                modelDir = o.optString("modelDir", DEFAULT.modelDir),
                nGpuLayers = o.optInt("nGpuLayers", DEFAULT.nGpuLayers),
                soundEnabled = o.optBoolean("soundEnabled", DEFAULT.soundEnabled),
                soundVolume = o.optDouble("soundVolume", DEFAULT.soundVolume),
            )
        }
    }
}

/** Colunas JSON (exdates/reminders/days) — o desktop guarda como texto JSON. */
object JsonLists {
    fun encode(items: List<Int>): String = JSONArray(items).toString()

    fun decode(raw: String): List<Int> = runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { arr.optInt(it) }
    }.getOrDefault(emptyList())

    fun encodeStrings(items: List<String>): String = JSONArray(items).toString()

    fun decodeStrings(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { arr.optString(it) }
    }.getOrDefault(emptyList())
}
