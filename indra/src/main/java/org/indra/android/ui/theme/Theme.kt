package org.indra.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = IndraPrimary,
    onPrimary = IndraOnPrimary,
    primaryContainer = IndraPrimaryContainer,
    onPrimaryContainer = IndraOnPrimaryContainer,
    secondary = IndraSecondary,
    onSecondary = IndraOnSecondary,
    secondaryContainer = IndraSecondaryContainer,
    onSecondaryContainer = IndraOnSecondaryContainer,
    tertiary = IndraTertiary,
    onTertiary = IndraOnTertiary,
    tertiaryContainer = IndraTertiaryContainer,
    onTertiaryContainer = IndraOnTertiaryContainer,
    error = IndraError,
    onError = IndraOnError,
    errorContainer = IndraErrorContainer,
    onErrorContainer = IndraOnErrorContainer,
    background = IndraBackground,
    onBackground = IndraOnBackground,
    surface = IndraSurface,
    onSurface = IndraOnSurface,
    surfaceVariant = IndraSurfaceVariant,
    onSurfaceVariant = IndraOnSurfaceVariant,
    outline = IndraOutline,
    outlineVariant = IndraOutlineVariant,
    scrim = IndraScrim
)

private val DarkColorScheme = darkColorScheme(
    primary = IndraPrimaryDark,
    onPrimary = IndraOnPrimaryDark,
    primaryContainer = IndraPrimaryContainerDark,
    onPrimaryContainer = IndraOnPrimaryContainerDark,
    secondary = IndraSecondaryDark,
    onSecondary = IndraOnSecondaryDark,
    secondaryContainer = IndraSecondaryContainerDark,
    onSecondaryContainer = IndraOnSecondaryContainerDark,
    tertiary = IndraTertiaryDark,
    onTertiary = IndraOnTertiaryDark,
    tertiaryContainer = IndraTertiaryContainerDark,
    onTertiaryContainer = IndraOnTertiaryContainerDark,
    error = IndraErrorDark,
    onError = IndraOnErrorDark,
    errorContainer = IndraErrorContainerDark,
    onErrorContainer = IndraOnErrorContainerDark,
    background = IndraBackgroundDark,
    onBackground = IndraOnBackgroundDark,
    surface = IndraSurfaceDark,
    onSurface = IndraOnSurfaceDark,
    surfaceVariant = IndraSurfaceVariantDark,
    onSurfaceVariant = IndraOnSurfaceVariantDark,
    outline = IndraOutlineDark,
    outlineVariant = IndraOutlineVariantDark,
    scrim = IndraScrimDark
)

@Composable
fun IndraTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IndraTypography,
        content = content
    )
}
