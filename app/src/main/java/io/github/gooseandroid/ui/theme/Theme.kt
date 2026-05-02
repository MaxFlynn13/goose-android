package io.github.gooseandroid.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.ui.settings.ThemeMode

// Default Goose colors
val GooseOrange = Color(0xFFFF6B35)
val CashGreen = Color(0xFF00D632)

/**
 * Dynamic theme that reads from persisted settings.
 *
 * Color strategy:
 * - Primary color: ONLY affects primary buttons, links, active indicators
 * - Everything else uses standard Material 3 surface/background tokens
 * - This prevents "color bleeding" into surfaces, cards, etc.
 *
 * Text scale: Applied via custom Density that scales font sizes.
 */
@Composable
fun GooseTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }

    // Read persisted theme settings
    val themeModeStr by settingsStore.getString(SettingsKeys.THEME_MODE, "SYSTEM").collectAsState(initial = "SYSTEM")
    val primaryColorInt by settingsStore.getInt(SettingsKeys.PRIMARY_COLOR, GooseOrange.toArgb()).collectAsState(initial = GooseOrange.toArgb())
    val textScale by settingsStore.getFloat(SettingsKeys.TEXT_SCALE, 1.0f).collectAsState(initial = 1.0f)

    val themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (e: Exception) { ThemeMode.SYSTEM }
    val primaryColor = Color(primaryColorInt)

    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.BLACK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val isPureBlack = themeMode == ThemeMode.BLACK

    // Color scheme: only primary is user-customizable
    // All other colors are standard Material 3 dark/light tokens
    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = Color.White,
            primaryContainer = primaryColor.copy(alpha = 0.15f),
            onPrimaryContainer = primaryColor,
            // Standard dark surfaces — NOT affected by user color choice
            background = if (isPureBlack) Color.Black else Color(0xFF0D0D0D),
            surface = if (isPureBlack) Color(0xFF050505) else Color(0xFF1A1A1A),
            surfaceVariant = if (isPureBlack) Color(0xFF151515) else Color(0xFF2A2A2A),
            surfaceContainerHighest = if (isPureBlack) Color(0xFF1A1A1A) else Color(0xFF333333),
            onBackground = Color(0xFFE4E1E6),
            onSurface = Color(0xFFE4E1E6),
            onSurfaceVariant = Color(0xFFAAAAAA),
            outline = if (isPureBlack) Color(0xFF2A2A2A) else Color(0xFF444444),
            outlineVariant = if (isPureBlack) Color(0xFF1A1A1A) else Color(0xFF333333),
            // Keep secondary/tertiary neutral
            secondary = Color(0xFF8ECAE6),
            tertiary = CashGreen,
            error = Color(0xFFCF6679),
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = Color.White,
            primaryContainer = primaryColor.copy(alpha = 0.08f),
            onPrimaryContainer = primaryColor,
            // Standard light surfaces
            background = Color(0xFFFFFBFE),
            surface = Color(0xFFFFFBFE),
            surfaceVariant = Color(0xFFF3F0F4),
            surfaceContainerHighest = Color(0xFFE8E5E9),
            onBackground = Color(0xFF1C1B1F),
            onSurface = Color(0xFF1C1B1F),
            onSurfaceVariant = Color(0xFF49454F),
            outline = Color(0xFF79747E),
            outlineVariant = Color(0xFFCAC4D0),
            secondary = Color(0xFF625B71),
            tertiary = Color(0xFF7D5260),
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    }

    // Update status bar
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    // Apply text scale via custom Density
    val currentDensity = LocalDensity.current
    val scaledDensity = Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale * textScale
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
