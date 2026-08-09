package com.localagenda.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes JVM da serialização que roda sem Android (org.json do Maven).
 *
 * O contrato testado é o que liga o Android ao desktop: as Settings são um
 * blob JSON em `meta.settings` do MESMO arquivo .db que o desktop lê/grava.
 *  - `fromBlob` precisa ler o que o desktop escreveu (e vice-versa).
 *  - `toBlob` precisa MERGE por cima do blob existente preservando chaves
 *    que o Android ainda não conhece (ex.: modelDir do desktop) — senão cada
 *    ajuste de tema no celular zeraria a configuração da IA do desktop.
 */
class SettingsTest {

    @Test
    fun `fromBlob le chaves do desktop com defaults sãos`() {
        val blob = """
            {
              "theme": "dark",
              "firstDayOfWeek": 1,
              "defaultDurationMin": 30,
              "dailySummary": true,
              "dailySummaryTime": "07:30",
              "closeToTray": false,
              "soundEnabled": false,
              "soundVolume": 0.25
            }
        """.trimIndent()

        val s = Settings.fromBlob(blob)

        assertEquals("dark", s.theme)
        assertEquals(1, s.firstDayOfWeek)
        assertEquals(30, s.defaultDurationMin)
        assertTrue(s.dailySummary)
        assertEquals("07:30", s.dailySummaryTime)
        assertFalse(s.closeToTray)
        assertFalse(s.soundEnabled)
        assertEquals(0.25, s.soundVolume, 1e-9)
        // Chaves que o desktop mandou sem default no construtor do Android:
        assertEquals("", s.modelDir)
        assertEquals(0, s.nGpuLayers)
    }

    @Test
    fun `fromBlob com json quebrado volta ao default`() {
        assertEquals(Settings.DEFAULT, Settings.fromBlob("{{{{ nao é json"))
    }

    @Test
    fun `fromBlob de blob vazio volta ao default`() {
        assertEquals(Settings.DEFAULT, Settings.fromBlob(""))
    }

    @Test
    fun `toBlob preserva chaves desconhecidas do blob existente`() {
        val existing = """
            {
              "theme": "light",
              "modelDir": "D:/modelos/llama-3.2-3b",
              "nGpuLayers": 8,
              "algumaCoisaFutura": 42
            }
        """.trimIndent()

        // Uso real: o app carrega o blob primeiro (fromBlob), o usuário edita
        // e o toBlob grava por cima — modelDir/nGpuLayers vêm do load, não do
        // default, então o desktop não é zerado.
        val loaded = Settings.fromBlob(existing)
        val merged = loaded.copy(theme = "dark").toBlob(existing)
        val json = org.json.JSONObject(merged)

        // O que o Android mexeu veio junto:
        assertEquals("dark", json.getString("theme"))
        assertEquals(60, json.getInt("defaultDurationMin"))
        // O que o Android não conhece ficou INTACTO:
        assertEquals("D:/modelos/llama-3.2-3b", json.getString("modelDir"))
        assertEquals(8, json.getInt("nGpuLayers"))
        assertEquals(42, json.getInt("algumaCoisaFutura"))
    }

    @Test
    fun `toBlob sem blob existente cria o conjunto completo`() {
        val json = org.json.JSONObject(Settings().toBlob(null))
        assertEquals("system", json.getString("theme"))
        assertEquals(60, json.getInt("defaultDurationMin"))
        assertEquals(0.7, json.getDouble("soundVolume"), 1e-9)
        assertEquals(10, json.length())
    }

    @Test
    fun `round-trip completa do blob do desktop`() {
        val desktop = """
            {"theme":"light","closeToTray":true,"firstDayOfWeek":0,
             "defaultDurationMin":60,"dailySummary":true,"dailySummaryTime":"09:00",
             "modelDir":"/home/joao/models","nGpuLayers":16,
             "soundEnabled":true,"soundVolume":0.5}
        """.trimIndent()

        val back = Settings.fromBlob(desktop).toBlob(desktop)
        val json = org.json.JSONObject(back)

        assertEquals("light", json.getString("theme"))
        assertTrue(json.getBoolean("dailySummary"))
        assertEquals("/home/joao/models", json.getString("modelDir"))
        assertEquals(16, json.getInt("nGpuLayers"))
        assertEquals(0.5, json.getDouble("soundVolume"), 1e-9)
    }
}

/** Colunas JSON (exdates/reminders/days) — mesma representação do desktop. */
class JsonListsTest {

    @Test
    fun `ints fazem round-trip`() {
        assertEquals(listOf(5, 10, 15), JsonLists.decode(JsonLists.encode(listOf(5, 10, 15))))
    }

    @Test
    fun `strings fazem round-trip inclusive vazias`() {
        val items = listOf("2026-08-08T10:00", "", "com acento: ção")
        assertEquals(items, JsonLists.decodeStrings(JsonLists.encodeStrings(items)))
    }

    @Test
    fun `json inválido vira lista vazia em vez de crashar`() {
        assertEquals(emptyList<Int>(), JsonLists.decode("isso não é json"))
        assertEquals(emptyList<String>(), JsonLists.decodeStrings(""))
    }

    @Test
    fun `lista vazia vira colchetes vazios`() {
        assertEquals("[]", JsonLists.encode(emptyList()))
        assertEquals("[]", JsonLists.encodeStrings(emptyList()))
        assertEquals(emptyList<Int>(), JsonLists.decode("[]"))
    }
}
