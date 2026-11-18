package com.nanci.codexremote.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Immutable
data class NativeColors(
    val background: Color,
    val surface: Color,
    val field: Color,
    val ink: Color,
    val muted: Color,
    val tertiary: Color,
    val border: Color,
    val accent: Color,
    val danger: Color,
    val onAccent: Color,
    val success: Color,
    val selection: Color,
    val isDark: Boolean,
    val highContrast: Boolean,
)

/** Source: Android 1.2026.307 fo00.o/s -> r7p/h9p/d8p/a9p.
 * High contrast getters were additionally resolved in cmf1/bof1/nmf1/tnf1 and
 * gjf1/pkf1/qjf1/ikf1, rather than estimating them from light screenshots.
 * Field names here are this client's semantic bindings, not original class names.
 */
fun nativeColors(dark: Boolean, highContrast: Boolean): NativeColors {
    val surface = if (dark) Color(0xFF212121) else Color.White
    val ink = if (dark) Color.White else Color(0xFF0D0D0D)
    val muted = when {
        dark -> Color(0xFFCDCDCD)
        highContrast -> Color(0xFF414141)
        else -> Color(0xFF5D5D5D)
    }
    val field = when {
        dark && highContrast -> Color(0xFF212121) // qjf1.z -> d8p.d
        dark -> Color(0x80414141) // fo00.o -> d8p.d; retain token alpha
        highContrast -> Color.White // nmf1.g -> d8p.d
        else -> Color(0xFFF3F3F3)
    }
    return NativeColors(
        background = if (dark) Color(0xFF303030) else Color.White, // r7p.a
        surface = surface, field = field, ink = ink, muted = muted,
        tertiary = when {
            dark -> Color(0xFFAFAFAF)
            highContrast -> Color(0xFF5D5D5D)
            else -> Color(0xFF8F8F8F)
        }, // h9p.e
        // Stronger outlines bind the native high-contrast text tone to local form borders.
        border = if (highContrast) muted else if (dark) Color(0xFF414141) else Color(0xFFE8E8E8),
        accent = when {
            dark -> Color(0xFF81A6F9) // a9p.f / ikf1.j
            highContrast -> Color(0xFF2451DA) // tnf1.h
            else -> Color(0xFF3566F0)
        },
        danger = if (dark) Color(0xFFFF8066) else Color(0xFFD8241A), // k8p.g
        onAccent = if (dark) Color(0xFF0D0D0D) else Color.White,
        success = if (dark) Color(0xFF48A04C) else Color(0xFF3A843F),
        selection = if (dark) Color(0xFF414141) else Color(0xFFE8E8E8),
        isDark = dark, highContrast = highContrast,
    )
}

private val LocalNativeColors = staticCompositionLocalOf { nativeColors(false, false) }

object NativeTheme {
    val colors: NativeColors
        @Composable get() = LocalNativeColors.current
}

@Composable
fun NativeAppTheme(
    darkTheme: Boolean = false,
    highContrast: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val systemHighContrast = rememberSystemHighContrast()
    val colors = remember(darkTheme, highContrast, systemHighContrast) {
        nativeColors(darkTheme, highContrast ?: systemHighContrast)
    }
    val material = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = material.copy(
        primary = colors.ink, onPrimary = colors.surface,
        primaryContainer = colors.field, onPrimaryContainer = colors.ink,
        secondary = colors.accent, onSecondary = colors.onAccent,
        secondaryContainer = colors.selection, onSecondaryContainer = colors.ink,
        tertiary = colors.accent, onTertiary = colors.onAccent,
        tertiaryContainer = colors.field, onTertiaryContainer = colors.ink,
        background = colors.background, onBackground = colors.ink,
        surface = colors.surface, onSurface = colors.ink,
        surfaceVariant = colors.field, onSurfaceVariant = colors.muted,
        surfaceTint = Color.Transparent,
        surfaceBright = colors.surface, surfaceDim = colors.background,
        surfaceContainer = colors.surface, surfaceContainerLow = colors.surface,
        surfaceContainerHigh = colors.field, surfaceContainerHighest = colors.selection,
        surfaceContainerLowest = colors.background,
        inverseSurface = colors.ink, inverseOnSurface = colors.surface,
        inversePrimary = colors.accent,
        error = colors.danger, onError = colors.surface,
        errorContainer = colors.field, onErrorContainer = colors.danger,
        outline = colors.border, outlineVariant = colors.border,
        scrim = Color.Black,
    )
    CompositionLocalProvider(LocalNativeColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = NativeTypography.material,
            shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp)),
            content = content)
    }
}

/** Observe changes without requiring an app restart or a permission to alter settings.
 * API 34's public contrast callback supplements the legacy high-contrast text setting.
 */
@Composable
private fun rememberSystemHighContrast(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(readHighContrast(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = readHighContrast(context) }
        }
        val registered = runCatching {
            resolver.registerContentObserver(Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer)
        }.isSuccess
        val removeContrastListener = if (Build.VERSION.SDK_INT >= 34) {
            observeModernContrast(context) { enabled = readHighContrast(context) }
        } else null
        onDispose {
            if (registered) resolver.unregisterContentObserver(observer)
            removeContrastListener?.invoke()
        }
    }
    return enabled
}

private fun readHighContrast(context: Context): Boolean =
    runCatching { Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false) ||
        (Build.VERSION.SDK_INT >= 34 && readModernContrast(context))

@RequiresApi(34)
private fun readModernContrast(context: Context): Boolean =
    runCatching { context.getSystemService(UiModeManager::class.java)?.contrast?.let { it >= .5f } ?: false }.getOrDefault(false)

@RequiresApi(34)
private fun observeModernContrast(context: Context, update: () -> Unit): (() -> Unit)? {
    val manager = context.getSystemService(UiModeManager::class.java) ?: return null
    val listener = UiModeManager.ContrastChangeListener { update() }
    return runCatching {
        manager.addContrastChangeListener(context.mainExecutor, listener)
        val remove: () -> Unit = { manager.removeContrastChangeListener(listener) }
        remove
    }.getOrNull()
}
