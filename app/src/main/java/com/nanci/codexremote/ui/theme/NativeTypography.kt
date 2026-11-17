package com.nanci.codexremote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Android APK fs40 typography, not Material's unrelated default size/spacing table.
 * fs40 declares Roboto, OpenAI Sans, sans-serif. Android's system sans family supplies
 * Roboto where installed and the system language fallback; no proprietary font is assumed.
 */
object NativeTypography {
    val body16 = style(16, 24, 400, .1f)
    val body16Medium = style(16, 24, 500, .1f)
    val body14 = style(14, 20, 400, .1f)
    val body14Medium = style(14, 20, 500, .1f)
    val caption12 = style(12, 16, 400, .2f)
    val caption12Medium = style(12, 16, 500, .2f)
    val menu16 = body16Medium
    val title16 = style(16, 24, 600, .1f)
    val title22 = style(22, 28, 500, .1f)
    val title22Bold = style(22, 28, 700, .1f)
    val title24 = style(24, 32, 500, .1f)
    val title28 = style(28, 36, 500, -.32f)
    val title36 = style(36, 44, 500, -.32f)
    val compact14 = style(14, 18, 400, -.15f)
    val code13 = style(13, 18, 400, .2f).copy(fontFamily = FontFamily.Monospace)

    private fun style(size: Int, height: Int, weight: Int, spacing: Float) = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp,
        fontWeight = FontWeight(weight), letterSpacing = spacing.sp,
    )

    val material = Typography(
        displayLarge = title36, displayMedium = title36, displaySmall = title28,
        headlineLarge = title28, headlineMedium = title24, headlineSmall = title22,
        titleLarge = title22, titleMedium = menu16, titleSmall = body14Medium,
        bodyLarge = body16, bodyMedium = body14, bodySmall = caption12,
        labelLarge = body14Medium, labelMedium = caption12Medium, labelSmall = caption12,
    )
}
