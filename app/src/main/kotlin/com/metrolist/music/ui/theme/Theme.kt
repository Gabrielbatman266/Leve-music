/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score

val DefaultThemeColor = Color(0xFFED5564)

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    // Leve Music: tema fixo escuro no estilo Spotify (sem cálculo de cor dinâmica = mais leve)
    // Leve Music: tamanho de letra escolhido pelo usuário vale pro app inteiro
    val fontScale by com.metrolist.music.utils.rememberPreference(com.metrolist.music.utils.LeveFontScaleKey, 1f)
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * fontScale),
    ) {
        MaterialTheme(
            colorScheme = if (pureBlack) LeveColorScheme.pureBlack(true) else LeveColorScheme,
            content = content,
        )
    }
}

private val LeveGreen = Color(0xFF1DB954)

val LeveColorScheme: ColorScheme =
    darkColorScheme(
        primary = LeveGreen,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF1ED760),
        onPrimaryContainer = Color.Black,
        inversePrimary = Color(0xFF117A37),
        secondary = Color(0xFFB3B3B3),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF2A2A2A),
        onSecondaryContainer = Color.White,
        tertiary = LeveGreen,
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF1A3D27),
        onTertiaryContainer = Color.White,
        background = Color(0xFF121212),
        onBackground = Color.White,
        surface = Color(0xFF121212),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF2A2A2A),
        onSurfaceVariant = Color(0xFFB3B3B3),
        surfaceTint = Color(0xFF121212),
        inverseSurface = Color.White,
        inverseOnSurface = Color.Black,
        error = Color(0xFFF15E6C),
        onError = Color.Black,
        errorContainer = Color(0xFF5C1A21),
        onErrorContainer = Color.White,
        outline = Color(0xFF535353),
        outlineVariant = Color(0xFF3E3E3E),
        scrim = Color.Black,
        surfaceBright = Color(0xFF2A2A2A),
        surfaceDim = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF242424),
        surfaceContainerHighest = Color(0xFF2E2E2E),
        surfaceContainerLow = Color(0xFF181818),
        surfaceContainerLowest = Color(0xFF000000),
    )

fun Bitmap.extractThemeColor(): Color = Color(
    Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, DefaultThemeColor.toArgb())
        .first()
)

internal fun Palette.rankedColors(
    desiredColorCount: Int,
    fallbackColor: Int,
): List<Int> = Score.score(
    swatches.associate { it.rgb to it.population },
    desiredColorCount,
    fallbackColor,
    true,
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}
