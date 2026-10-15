package nl.gamegraph.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import nl.gamegraph.app.data.ThemeMode
import nl.gamegraph.app.domain.LinkKind

object GgColors {
    val Violet = Color(0xFF8B5CF6)
    val Blue = Color(0xFF3B82F6)
    val Background = Color(0xFF0E0F1A)
    val Surface = Color(0xFF171829)
    val SurfaceHigh = Color(0xFF212238)
    val OnDark = Color(0xFFECECF6)
    val OnDarkMuted = Color(0xFFB4B5CC)
    val Danger = Color(0xFFFF6B7A)
    val Success = Color(0xFF4ADE80)

    /** Kleur per soort verbinding; ook onderscheidbaar zonder kleur via de legenda en lijnstijl. */
    fun link(kind: LinkKind): Color = when (kind) {
        LinkKind.GENRE -> Color(0xFF8B5CF6)
        LinkKind.MECHANIC -> Color(0xFF38BDF8)
        LinkKind.MOOD -> Color(0xFFF472B6)
        LinkKind.DEVELOPER -> Color(0xFFFBBF24)
        LinkKind.STYLE -> Color(0xFF4ADE80)
    }
}

private val DarkScheme = darkColorScheme(
    primary = GgColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2C7A),
    onPrimaryContainer = Color(0xFFE3DBFF),
    secondary = GgColors.Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1F3A66),
    onSecondaryContainer = Color(0xFFD6E6FF),
    background = GgColors.Background,
    onBackground = GgColors.OnDark,
    surface = GgColors.Surface,
    onSurface = GgColors.OnDark,
    surfaceVariant = GgColors.SurfaceHigh,
    onSurfaceVariant = GgColors.OnDarkMuted,
    surfaceContainer = GgColors.Surface,
    surfaceContainerHigh = GgColors.SurfaceHigh,
    error = GgColors.Danger,
    outline = Color(0xFF5A5C7A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D3FE0),
    secondary = Color(0xFF2563EB),
    background = Color(0xFFF7F7FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEBEBF6),
    error = Color(0xFFC62839),
)

private val GgTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val GgShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun GameGraphTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = GgTypography,
        shapes = GgShapes,
        content = content,
    )
}
