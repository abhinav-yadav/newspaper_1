package com.epaper.reader.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.epaper.reader.data.ThemeMode

// Ink-on-newsprint palette: deep navy ink, warm paper, a single red accent as in a masthead.
private val Light = lightColorScheme(
    primary = Color(0xFF1F3A5F), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F7), onPrimaryContainer = Color(0xFF0B1D33),
    secondary = Color(0xFF8C2F2B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E4D7), onSecondaryContainer = Color(0xFF3A1A10),
    tertiary = Color(0xFF2E6B4F), tertiaryContainer = Color(0xFFD4EEDD), onTertiaryContainer = Color(0xFF0C2A1C),
    background = Color(0xFFFBF8F3), onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFBF8F3), onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFEDE8E0), onSurfaceVariant = Color(0xFF4A4740),
    surfaceContainer = Color(0xFFF3EFE8), surfaceContainerHigh = Color(0xFFEEE9E1), surfaceContainerLow = Color(0xFFF7F3ED),
    outline = Color(0xFF7B776F), outlineVariant = Color(0xFFD5CFC5),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7F0), onPrimary = Color(0xFF0B2846),
    primaryContainer = Color(0xFF233F63), onPrimaryContainer = Color(0xFFD6E3F7),
    secondary = Color(0xFFF0B3A9), onSecondary = Color(0xFF55130F),
    secondaryContainer = Color(0xFF4A2A22), onSecondaryContainer = Color(0xFFF3E4D7),
    tertiary = Color(0xFF9FD5B7), tertiaryContainer = Color(0xFF1E4A35), onTertiaryContainer = Color(0xFFD4EEDD),
    background = Color(0xFF121416), onBackground = Color(0xFFE4E2DE),
    surface = Color(0xFF121416), onSurface = Color(0xFFE4E2DE),
    surfaceVariant = Color(0xFF2A2D31), onSurfaceVariant = Color(0xFFC4C1BA),
    surfaceContainer = Color(0xFF1C1E21), surfaceContainerHigh = Color(0xFF24272A), surfaceContainerLow = Color(0xFF17191B),
    outline = Color(0xFF8E8A83), outlineVariant = Color(0xFF3B3E42),
)

private val Serif = FontFamily.Serif
private val base = Typography()
private val AppTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun EpaperTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
