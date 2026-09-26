package com.rtree.LIFELiNK

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LifeLinkColorScheme = lightColorScheme(
    primary = LifeLinkNavy,
    onPrimary = LifeLinkSurface,
    primaryContainer = LifeLinkNavyContainer,
    onPrimaryContainer = LifeLinkNavyDark,
    secondary = LifeLinkAccentBlue,
    onSecondary = LifeLinkSurface,
    secondaryContainer = LifeLinkAccentBlueContainer,
    onSecondaryContainer = LifeLinkNavyDark,
    tertiary = LifeLinkSuccessGreen,
    onTertiary = LifeLinkSurface,
    tertiaryContainer = LifeLinkSuccessGreenContainer,
    onTertiaryContainer = LifeLinkSuccessGreenDark,
    error = LifeLinkEmergencyRed,
    onError = LifeLinkSurface,
    errorContainer = LifeLinkEmergencyHalo,
    onErrorContainer = LifeLinkEmergencyRedDark,
    background = LifeLinkBackground,
    onBackground = LifeLinkOnSurface,
    surface = LifeLinkSurface,
    onSurface = LifeLinkOnSurface,
    surfaceVariant = LifeLinkSurfaceVariant,
    onSurfaceVariant = LifeLinkOnSurfaceVariant,
    surfaceContainerLowest = LifeLinkSurface,
    surfaceContainerLow = LifeLinkSurface,
    surfaceContainer = LifeLinkBackground,
    surfaceContainerHigh = LifeLinkSurfaceVariant,
    surfaceContainerHighest = LifeLinkSurfaceVariant,
    outline = LifeLinkOutline,
    outlineVariant = LifeLinkSurfaceVariant,
)

private val LifeLinkTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

private val LifeLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    // Pill buttons ("Back" / "Next" / status chips) in the mocks.
    extraLarge = RoundedCornerShape(percent = 50),
)

@Composable
fun LifeLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LifeLinkColorScheme,
        typography = LifeLinkTypography,
        shapes = LifeLinkShapes,
        content = content,
    )
}
