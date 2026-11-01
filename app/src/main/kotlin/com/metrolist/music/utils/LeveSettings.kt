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

/** Tema visual do app. */
val LeveThemeKey = androidx.datastore.preferences.core.stringPreferencesKey("leveAppTheme")

data class LeveThemeOption(
    val key: String,
    val label: String,
    val background: Long,
    val card: Long,
    val accent: Long,
    val onAccentWhite: Boolean = false,
    val animated: Boolean = false,
)

val LeveThemes =
    listOf(
        LeveThemeOption("spotify", "Verde (padrão)", 0xFF121212, 0xFF242424, 0xFF1DB954),
        LeveThemeOption("amoled", "AMOLED verde", 0xFF000000, 0xFF161616, 0xFF1DB954),
        LeveThemeOption("amoled_red", "AMOLED vermelho", 0xFF000000, 0xFF181010, 0xFFE53935, onAccentWhite = true),
        LeveThemeOption("ps2", "Console azul (animado)", 0xFF040A1E, 0xFF0F1B3D, 0xFF4FA3FF, animated = true),
        LeveThemeOption("purple", "Roxo noite", 0xFF0D0916, 0xFF1D1530, 0xFFB388FF),
        LeveThemeOption("ocean", "Oceano", 0xFF061619, 0xFF102A30, 0xFF26C6DA),
        LeveThemeOption("sunset", "Pôr do sol", 0xFF140C08, 0xFF2A1A12, 0xFFFF8A3D),
    )

fun leveThemeFor(key: String?): LeveThemeOption = LeveThemes.firstOrNull { it.key == key } ?: LeveThemes.first()
