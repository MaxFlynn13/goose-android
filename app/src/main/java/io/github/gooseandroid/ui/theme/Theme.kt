package io.github.gooseandroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Goose brand colors
val GooseOrange = Color(0xFFFF6B35)
val GooseOrangeDark = Color(0xFFE55A25)
val GooseOrangeLight = Color(0xFFFF8F66)

private val DarkColorScheme = darkColorScheme(
    primary = GooseOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3D1E00),
    onPrimaryContainer = GooseOrangeLight,
    secondary = Color(0xFF8ECAE6),
    onSecondary = Color.Black,
    background = Color(0xFF121218),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF1C1C24),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF2A2A36),
    onSurfaceVariant = Color(0xFFC4C0CC),
    outline = Color(0xFF3D3D4A),
    error = Color(0xFFFF5449),
)

private val LightColorScheme = lightColorScheme(
    primary = GooseOrange,
    onPrimary = Color.White,
    primaryContainer = GooseOrangeLight,
    onPrimaryContainer = Color(0xFF3D1E00),
    secondary = Color(0xFF023047),
    onSecondary = Color.White,
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF3EFF4),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF7A757F),
    error = Color(0xFFBA1A1A),
)

@Composable
fun GooseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
