package dev.dimension.flare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF087F73),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD2F2EC),
        onPrimaryContainer = Color(0xFF073D38),
        secondary = Color(0xFFB24F18),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFDBC8),
        onSecondaryContainer = Color(0xFF4A1B04),
        tertiary = Color(0xFF4E5F91),
        onTertiary = Color.White,
        background = Color(0xFFF6F9F8),
        onBackground = Color(0xFF17201E),
        surface = Color(0xFFFCFDFC),
        onSurface = Color(0xFF17201E),
        surfaceVariant = Color(0xFFE3E9E7),
        onSurfaceVariant = Color(0xFF48524F),
        outline = Color(0xFF727C79),
        outlineVariant = Color(0xFFC3CBC8),
        error = Color(0xFFB3261E),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF70D8C8),
        onPrimary = Color(0xFF003C35),
        primaryContainer = Color(0xFF075D55),
        onPrimaryContainer = Color(0xFFB8EEE5),
        secondary = Color(0xFFFFB68F),
        onSecondary = Color(0xFF5D2507),
        secondaryContainer = Color(0xFF713312),
        onSecondaryContainer = Color(0xFFFFDBC8),
        tertiary = Color(0xFFBAC5FF),
        onTertiary = Color(0xFF24315F),
        background = Color(0xFF111614),
        onBackground = Color(0xFFE2E9E6),
        surface = Color(0xFF161C1A),
        onSurface = Color(0xFFE2E9E6),
        surfaceVariant = Color(0xFF29312E),
        onSurfaceVariant = Color(0xFFBEC8C4),
        outline = Color(0xFF89938F),
        outlineVariant = Color(0xFF3D4643),
        error = Color(0xFFFFB4AB),
    )

private val FlareDoTypography =
    Typography(
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                letterSpacing = 0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.sp,
            ),
    )

private val FlareDoShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )

@Composable
public fun FlareDoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = FlareDoTypography,
        shapes = FlareDoShapes,
        content = content,
    )
}
