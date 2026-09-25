package app.ee.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = EePrimary,
    onPrimary = EeOnPrimary,
    primaryContainer = EePrimaryContainer,
    onPrimaryContainer = EeOnPrimaryContainer,
    secondary = EeSecondary,
    onSecondary = EeOnSecondary,
    secondaryContainer = EeSecondaryContainer,
    onSecondaryContainer = EeOnSecondaryContainer,
    tertiary = EeTertiary,
    tertiaryContainer = EeTertiaryContainer,
    surface = EeSurface,
    onSurface = EeOnSurface,
    surfaceVariant = EeSurfaceVariant,
    onSurfaceVariant = EeOnSurfaceVariant,
    error = EeError,
    errorContainer = EeErrorContainer,
)

private val DarkColors = darkColorScheme(
    primary = EePrimaryDark,
    onPrimary = EeOnPrimaryDark,
    primaryContainer = EePrimaryContainerDark,
    onPrimaryContainer = EeOnPrimaryContainerDark,
    surface = EeSurfaceDark,
    onSurface = EeOnSurfaceDark,
    surfaceVariant = EeSurfaceVariantDark,
    onSurfaceVariant = EeOnSurfaceVariantDark,
)

/**
 * App-wide theme. `dynamicColor` defaults to true on Android 12+ and can be
 * disabled in settings (feature-settings, M1).
 */
@Composable
fun EeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EeTypography,
        content = content,
    )
}
