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
import io.github.gooseandroid.ui.settings.ThemeMode

// Default Goose colors
val GooseOrange = Color(0xFFFF6B35)
val GooseBlack = Color(0xFF0D0D0D)
val GooseDarkSurface = Color(0xFF1A1A1A)
val GooseLightSurface = Color(0xFFF5F5F5)

// Cash App Green (Neon Green)
val CashGreen = Color(0xFF00D632)

/**
 * Dynamic theme that reads from persisted settings.
 * Supports: Light, Dark, Pure Black (AMOLED), and custom colors.
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
    val secondaryColorInt by settingsStore.getInt(SettingsKeys.SECONDARY_COLOR, Color(0xFF8ECAE6).toArgb()).collectAsState(initial = Color(0xFF8ECAE6).toArgb())
    val accentColorInt by settingsStore.getInt(SettingsKeys.ACCENT_COLOR, CashGreen.toArgb()).collectAsState(initial = CashGreen.toArgb())

    val themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (e: Exception) { ThemeMode.SYSTEM }
    val primaryColor = Color(primaryColorInt)
    val secondaryColor = Color(secondaryColorInt)
    val accentColor = Color(accentColorInt)

    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.BLACK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val isPureBlack = themeMode == ThemeMode.BLACK

    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = Color.White,
            primaryContainer = primaryColor.copy(alpha = 0.2f),
            secondary = secondaryColor,
            secondaryContainer = secondaryColor.copy(alpha = 0.2f),
            tertiary = accentColor,
            background = if (isPureBlack) Color.Black else GooseBlack,
            surface = if (isPureBlack) Color(0xFF0A0A0A) else GooseDarkSurface,
            surfaceVariant = if (isPureBlack) Color(0xFF1A1A1A) else Color(0xFF2A2A2A),
            onBackground = Color(0xFFE4E1E6),
            onSurface = Color(0xFFE4E1E6),
            onSurfaceVariant = Color(0xFFAAAAAA),
            outline = if (isPureBlack) Color(0xFF333333) else Color(0xFF444444),
            error = Color(0xFFCF6679)
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = Color.White,
            primaryContainer = primaryColor.copy(alpha = 0.1f),
            secondary = secondaryColor,
            secondaryContainer = secondaryColor.copy(alpha = 0.1f),
            tertiary = accentColor,
            background = Color.White,
            surface = GooseLightSurface,
            surfaceVariant = Color(0xFFEEEEEE),
            onBackground = Color(0xFF1A1A1A),
            onSurface = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFF666666),
            outline = Color(0xFFCCCCCC),
            error = Color(0xFFB00020)
        )
    }

    // Update status bar color
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
