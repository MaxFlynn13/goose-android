package io.github.gooseandroid.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore

/**
 * Goose theme — two modes only: Dark and Light.
 * Dark is the default. Very dark Material 3 design.
 * No user-configurable colors. No text scaling. No failure points.
 */
@Composable
fun GooseTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val themePref by settingsStore.getString(SettingsKeys.THEME_MODE, "DARK")
        .collectAsState(initial = "DARK")

    val useDarkTheme = when (themePref) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = Color(0xFFFF6B35),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF3D2010),
            onPrimaryContainer = Color(0xFFFFB899),
            secondary = Color(0xFF8ECAE6),
            onSecondary = Color.Black,
            tertiary = Color(0xFF00D632),
            background = Color(0xFF0A0A0A),
            onBackground = Color(0xFFE2E2E2),
            surface = Color(0xFF121212),
            onSurface = Color(0xFFE2E2E2),
            surfaceVariant = Color(0xFF1E1E1E),
            onSurfaceVariant = Color(0xFFB0B0B0),
            surfaceContainerHighest = Color(0xFF2A2A2A),
            outline = Color(0xFF3A3A3A),
            outlineVariant = Color(0xFF282828),
            error = Color(0xFFCF6679),
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFFF6B35),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDBCC),
            onPrimaryContainer = Color(0xFF3D1600),
            secondary = Color(0xFF625B71),
            onSecondary = Color.White,
            tertiary = Color(0xFF7D5260),
            background = Color(0xFFFFFBFE),
            onBackground = Color(0xFF1C1B1F),
            surface = Color(0xFFFFFBFE),
            onSurface = Color(0xFF1C1B1F),
            surfaceVariant = Color(0xFFF3F0F4),
            onSurfaceVariant = Color(0xFF49454F),
            surfaceContainerHighest = Color(0xFFE8E5E9),
            outline = Color(0xFF79747E),
            outlineVariant = Color(0xFFCAC4D0),
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
