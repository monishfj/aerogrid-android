package com.aerogrid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerogrid.app.R

val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
    Font(R.font.montserrat_extrabold, FontWeight.Black),
)

/** Soft-UI design tokens: the blue→indigo accent gradient and the paired light/dark neumorphic shadows. */
@Immutable
data class Soft(
    val gradStart: Color, val gradEnd: Color,
    val shadowDark: Color, val shadowLight: Color,
    val card: Color, val bg: Color, val track: Color, val muted: Color, val outline: Color,
) {
    val gradient get() = Brush.horizontalGradient(listOf(gradStart, gradEnd))
    val gradientV get() = Brush.verticalGradient(listOf(gradStart, gradEnd))
}

private val SoftLight = Soft(
    gradStart = Color(0xFF2B8CF0), gradEnd = Color(0xFF3B2CF6),
    shadowDark = Color(0xFFA9B6C6), shadowLight = Color(0xFFFFFFFF),
    card = Color(0xFFF7F9FB), bg = Color(0xFFE3E9EF), track = Color(0xFFD6DCE3), muted = Color(0xFF8E97A3), outline = Color(0xFF9AA3AE),
)
private val SoftDark = Soft(
    gradStart = Color(0xFF3A9BFF), gradEnd = Color(0xFF5B4BFF),
    shadowDark = Color(0xFF0D1015), shadowLight = Color(0xFF2E3542),
    card = Color(0xFF242A33), bg = Color(0xFF1C2129), track = Color(0xFF353C48), muted = Color(0xFF8C95A3), outline = Color(0xFF5B6473),
)

val LocalSoft = staticCompositionLocalOf { SoftLight }

private val Light = lightColorScheme(
    primary = Color(0xFF3557F2), onPrimary = Color.White, primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF14246B),
    secondary = Color(0xFF2B8CF0), onSecondary = Color.White, secondaryContainer = Color(0xFFE2ECFB), onSecondaryContainer = Color(0xFF173A66),
    tertiary = Color(0xFF2E9E6A), onTertiary = Color.White,
    background = SoftLight.bg, onBackground = Color(0xFF2B2F36),
    surface = SoftLight.bg, onSurface = Color(0xFF2B2F36), surfaceVariant = Color(0xFFE9EEF3), onSurfaceVariant = Color(0xFF6F7884),
    surfaceContainer = SoftLight.card, surfaceContainerLow = Color(0xFFEFF3F6), surfaceContainerHigh = Color.White, surfaceContainerHighest = Color.White,
    surfaceContainerLowest = Color.White,
    outline = SoftLight.outline, outlineVariant = Color(0xFFCDD5DE), error = Color(0xFFE5484D),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7D93FF), onPrimary = Color(0xFF0E1A55), primaryContainer = Color(0xFF2C3A80), onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF6CB4FF), secondaryContainer = Color(0xFF233A5A), onSecondaryContainer = Color(0xFFD6E7FF),
    tertiary = Color(0xFF5FD39A),
    background = SoftDark.bg, onBackground = Color(0xFFE6E9EE),
    surface = SoftDark.bg, onSurface = Color(0xFFE6E9EE), surfaceVariant = Color(0xFF2B323C), onSurfaceVariant = Color(0xFFA7B0BD),
    surfaceContainer = SoftDark.card, surfaceContainerLow = Color(0xFF20262E), surfaceContainerHigh = Color(0xFF2A313B), surfaceContainerHighest = Color(0xFF303845),
    surfaceContainerLowest = Color(0xFF181C22),
    outline = SoftDark.outline, outlineVariant = Color(0xFF3A424F), error = Color(0xFFFF6B6B),
)

private fun t(size: Int, weight: FontWeight, line: Int = (size * 1.35).toInt(), spacing: Double = 0.0) =
    TextStyle(fontFamily = Montserrat, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

val SoftTypography = Typography(
    displayLarge = t(54, FontWeight.Bold), displayMedium = t(44, FontWeight.Bold), displaySmall = t(36, FontWeight.ExtraBold, spacing = -0.5),
    headlineLarge = t(30, FontWeight.Bold), headlineMedium = t(26, FontWeight.Bold), headlineSmall = t(22, FontWeight.Bold),
    titleLarge = t(20, FontWeight.Bold), titleMedium = t(16, FontWeight.SemiBold), titleSmall = t(14, FontWeight.SemiBold),
    bodyLarge = t(16, FontWeight.Normal), bodyMedium = t(14, FontWeight.Normal), bodySmall = t(12, FontWeight.Normal),
    labelLarge = t(14, FontWeight.SemiBold), labelMedium = t(12, FontWeight.Medium), labelSmall = t(11, FontWeight.Medium),
)

val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun AeroGridTheme(theme: Int, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalSoft provides if (dark) SoftDark else SoftLight) {
        MaterialTheme(colorScheme = scheme, typography = SoftTypography, shapes = SoftShapes, content = content)
    }
}
