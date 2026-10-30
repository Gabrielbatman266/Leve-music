/**
 * Leve Music — preferências de acessibilidade
 */

package com.metrolist.music.utils

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/** Multiplica o tamanho das letras do app inteiro. */
val LeveFontScaleKey = floatPreferencesKey("leveAppFontScale")

/** Tamanho do texto dentro do YouTube (porcentagem, 100 = normal). */
val LeveWebTextZoomKey = intPreferencesKey("leveWebTextZoom")

val LeveFontScaleOptions = listOf(1f to "Normal", 1.15f to "Grande", 1.3f to "Maior", 1.45f to "Enorme")

val LeveWebTextZoomOptions = listOf(100 to "Normal", 125 to "Grande", 150 to "Maior", 175 to "Enorme")
