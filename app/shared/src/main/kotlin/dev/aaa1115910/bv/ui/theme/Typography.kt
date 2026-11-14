package dev.aaa1115910.bv.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import dev.aaa1115910.bv.R

private val notoMathFont = Font(R.font.noto_sans_math_regular, FontWeight.Normal)
private val notoFontFamily = FontFamily(notoMathFont)

private val dummyTypography = Typography()

// 只覆盖需要调整的样式，其余沿用 tv-material3 默认值
val tvTypography = dummyTypography.copy(
    labelSmall = dummyTypography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
)

private fun Typography.withFontFamily(fontFamily: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily)
)

val android6AndBelowTypographyTv = tvTypography.withFontFamily(notoFontFamily)

val android6AndBelowTypographyCommon = androidx.compose.material3.Typography(
    displayLarge = dummyTypography.displayLarge.copy(fontFamily = notoFontFamily),
    displayMedium = dummyTypography.displayMedium.copy(fontFamily = notoFontFamily),
    displaySmall = dummyTypography.displaySmall.copy(fontFamily = notoFontFamily),
    headlineLarge = dummyTypography.headlineLarge.copy(fontFamily = notoFontFamily),
    headlineMedium = dummyTypography.headlineMedium.copy(fontFamily = notoFontFamily),
    headlineSmall = dummyTypography.headlineSmall.copy(fontFamily = notoFontFamily),
    titleLarge = dummyTypography.titleLarge.copy(fontFamily = notoFontFamily),
    titleMedium = dummyTypography.titleMedium.copy(fontFamily = notoFontFamily),
    titleSmall = dummyTypography.titleSmall.copy(fontFamily = notoFontFamily),
    bodyLarge = dummyTypography.bodyLarge.copy(fontFamily = notoFontFamily),
    bodyMedium = dummyTypography.bodyMedium.copy(fontFamily = notoFontFamily),
    bodySmall = dummyTypography.bodySmall.copy(fontFamily = notoFontFamily),
    labelLarge = dummyTypography.labelLarge.copy(fontFamily = notoFontFamily),
    labelMedium = dummyTypography.labelMedium.copy(fontFamily = notoFontFamily),
    labelSmall = dummyTypography.labelSmall.copy(fontFamily = notoFontFamily)
)