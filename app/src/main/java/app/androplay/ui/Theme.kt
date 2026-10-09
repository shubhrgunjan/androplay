package app.androplay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** AndroPlay brand: deep blue primary, warm accent, soft rounded surfaces. */
object Brand {
    val Blue = Color(0xFF2F6BFF)
    val BlueDark = Color(0xFF8AB0FF)
    val Accent = Color(0xFFFF7A45)
    val Success = Color(0xFF2EB67D)
    val Warning = Color(0xFFF2B53A)
    val Danger = Color(0xFFE5484D)
}

private val Light = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0A1F5C),
    secondary = Brand.Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3D6),
    onSecondaryContainer = Color(0xFF4A1A05),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFF6F7FB),
    surfaceVariant = Color(0xFFE7EAF3),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F2F8),
    onSurfaceVariant = Color(0xFF5A6072),
    outlineVariant = Color(0xFFD5D9E5),
    error = Brand.Danger,
)

private val Dark = darkColorScheme(
    primary = Brand.BlueDark,
    onPrimary = Color(0xFF07163F),
    primaryContainer = Color(0xFF1C3274),
    onPrimaryContainer = Color(0xFFDDE6FF),
    secondary = Color(0xFFFFA27A),
    onSecondary = Color(0xFF3A1200),
    secondaryContainer = Color(0xFF5A2A12),
    onSecondaryContainer = Color(0xFFFFE3D6),
    background = Color(0xFF0E1117),
    surface = Color(0xFF0E1117),
    surfaceVariant = Color(0xFF232836),
    surfaceContainer = Color(0xFF171B24),
    surfaceContainerHigh = Color(0xFF1E2330),
    onSurfaceVariant = Color(0xFFA9B0C2),
    outlineVariant = Color(0xFF2E3445),
    error = Brand.Danger,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AndroPlayTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, content = content)
}
