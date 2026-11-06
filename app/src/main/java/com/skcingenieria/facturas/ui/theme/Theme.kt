package com.skcingenieria.facturas.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SkcPalette {
    val Blue = Color(0xFF075FB5)
    val BlueDark = Color(0xFF064F98)
    val BlueSoft = Color(0xFFE8F2FC)
    val Ink = Color(0xFF172433)
    val Muted = Color(0xFF647284)
    val Line = Color(0xFFD8E1EC)
    val Surface = Color.White
    val Canvas = Color(0xFFF4F7FB)
    val Yellow = Color(0xFFFFE59A)
    val YellowRow = Color(0xFFFFF7DF)
    val Green = Color(0xFF157F55)
    val GreenBg = Color(0xFFE8F7EF)
    val Red = Color(0xFFB52B2B)
    val RedBg = Color(0xFFFDECEC)
    val Orange = Color(0xFFA75B00)
    val OrangeBg = Color(0xFFFFF2DF)
}

private val SkcColors = lightColorScheme(
    primary = SkcPalette.Blue,
    onPrimary = Color.White,
    primaryContainer = SkcPalette.BlueSoft,
    onPrimaryContainer = SkcPalette.BlueDark,
    secondary = SkcPalette.Muted,
    background = SkcPalette.Canvas,
    onBackground = SkcPalette.Ink,
    surface = SkcPalette.Surface,
    onSurface = SkcPalette.Ink,
    outline = SkcPalette.Line,
    error = SkcPalette.Red,
    errorContainer = SkcPalette.RedBg
)

private val SkcTypography = Typography(
    headlineLarge = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    bodySmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 9.sp, lineHeight = 12.sp)
)

private val SkcShapes = Shapes(
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

@Composable
fun SkcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SkcColors,
        typography = SkcTypography,
        shapes = SkcShapes,
        content = content
    )
}
