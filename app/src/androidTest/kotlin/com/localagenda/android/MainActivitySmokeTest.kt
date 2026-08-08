package com.localagenda.android

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke no emulador do CI: abre a MainActivity de verdade e confere que a
 * UI sobe. Existe porque o build sozinho não pega crash de startup — o que
 * matou a v0.1.0 do LocalKeys (Application inexistente no manifest).
 *
 * Em banco novo (DataStore vazio no emulador) o app cai na tela de
 * boas-vindas, que é o que este teste verifica.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun app_abre_na_tela_de_boas_vindas() {
        composeRule.onNodeWithText("Bem-vindo ao LocalAgenda").assertExists()
    }
}
