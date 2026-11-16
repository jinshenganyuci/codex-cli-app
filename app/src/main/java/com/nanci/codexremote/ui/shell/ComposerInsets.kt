package com.nanci.codexremote.ui.shell

import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** Android gp2/lpq: compact margins follow symmetric lower display corners, not image scale. */
@Composable
internal fun composerHorizontalPadding(editProgress: Float, consumedInsets: WindowInsets): ComposerHorizontalPadding {
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val gestureNavigation = remember(context) {
        runCatching { Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1) == 2 }.getOrDefault(false)
    }
    var frame by remember(view) { mutableStateOf(readComposerFrame(view)) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener { frame = readComposerFrame(view) }
        observer.addOnGlobalLayoutListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener) else view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    val consumedLeft = consumedInsets.getLeft(density, direction) / density.density
    val consumedRight = consumedInsets.getRight(density, direction) / density.density
    val useRoundedMargins = gestureNavigation && frame.bottomRadius > 0 && frame.safeLeft == frame.safeRight
    val progress = editProgress.coerceIn(0f, 1f)
    fun padding(safeSide: Int, consumed: Float, fraction: Float): Float {
        if (!useRoundedMargins) return (safeSide / density.density - consumed).coerceAtLeast(0f) + 12f
        // lpq.i = clamp(radiusDp - ta11.q / 2, 34, 40), ta11.q = 48dp.
        val compact = ((frame.bottomRadius / density.density - 24f).coerceIn(34f, 40f) - consumed).coerceAtLeast(0f)
        return compact + (12f - compact) * fraction
    }
    fun values(fraction: Float): PaddingValues {
        val left = padding(frame.safeLeft, consumedLeft, fraction).dp
        val right = padding(frame.safeRight, consumedRight, fraction).dp
        return if (direction == androidx.compose.ui.unit.LayoutDirection.Ltr) PaddingValues(start = left, end = right)
            else PaddingValues(start = right, end = left)
    }
    return ComposerHorizontalPadding(values(progress), values(0f))
}

internal data class ComposerHorizontalPadding(val current: PaddingValues, val compact: PaddingValues)

private data class ComposerFrame(val bottomRadius: Int, val safeLeft: Int, val safeRight: Int)

@Suppress("DEPRECATION")
private fun readComposerFrame(view: View): ComposerFrame {
    val insets = view.rootWindowInsets ?: return ComposerFrame(0, 0, 0)
    val sides = if (Build.VERSION.SDK_INT >= 30) {
        val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        safe.left to safe.right
    } else insets.systemWindowInsetLeft to insets.systemWindowInsetRight
    return ComposerFrame(if (Build.VERSION.SDK_INT >= 31) bottomCornerRadius(insets) else 0, sides.first, sides.second)
}

@RequiresApi(31)
private fun bottomCornerRadius(insets: android.view.WindowInsets): Int {
    val left = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    val right = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
    return if (left == right) left else 0
}
