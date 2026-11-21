package dev.afterbeep.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Ab {
    val Ink = Color(0xFF0B0C10)
    val Surface = Color(0xFF14161C)
    val Surface2 = Color(0xFF1B1E26)
    val Surface3 = Color(0xFF242833)
    val Outline = Color(0xFF2C313D)
    val Text = Color(0xFFF3F4F7)
    val Muted = Color(0xFF8D94A3)
    val Faint = Color(0xFF555B69)
    val Amber = Color(0xFFFFB547)
    val Orange = Color(0xFFFF8A4C)
    val Coral = Color(0xFFFF6B5B)
    val Mint = Color(0xFF43E5A0)
    val Red = Color(0xFFFF5470)

    val Beep = Brush.linearGradient(listOf(Amber, Coral))
    val BeepH = Brush.horizontalGradient(listOf(Amber, Coral))

    /** Warm avatar gradients, picked by hash so a caller keeps their colour. */
    val avatarPairs = listOf(
        Amber to Coral, Color(0xFFFF7EB3) to Color(0xFFB57BFF), Color(0xFF5BD3FF) to Color(0xFF7B8CFF),
        Mint to Color(0xFF2FB5C8), Color(0xFFFFD36B) to Color(0xFFFF9F43), Color(0xFFB57BFF) to Color(0xFF6E5BFF),
    )
}

private val scheme = darkColorScheme(
    primary = Ab.Orange,
    onPrimary = Ab.Ink,
    secondary = Ab.Coral,
    tertiary = Ab.Mint,
    background = Ab.Ink,
    onBackground = Ab.Text,
    surface = Ab.Surface,
    onSurface = Ab.Text,
    surfaceVariant = Ab.Surface2,
    onSurfaceVariant = Ab.Muted,
    surfaceContainer = Ab.Surface,
    surfaceContainerHigh = Ab.Surface2,
    surfaceContainerHighest = Ab.Surface3,
    outline = Ab.Outline,
    outlineVariant = Ab.Outline,
    error = Ab.Red,
)

private val f = FontFamily.SansSerif
private val type = Typography(
    displaySmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-1.2).sp),
    headlineMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 0.8.sp),
)

@Composable
fun AfterbeepTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
