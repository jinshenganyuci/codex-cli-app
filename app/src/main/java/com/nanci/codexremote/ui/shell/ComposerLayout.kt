package com.nanci.codexremote.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Stable slots, inspired by the APK rci0/jci0 measure-and-place path. The editor, attachment
 * button and primary action are each composed once. A single internal progress changes the
 * card height, text position and control positions; the IME owns the parent translation.
 */
@Composable
internal fun ComposerLayout(
    expansion: Float,
    modifier: Modifier = Modifier,
    accessories: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    editor: @Composable () -> Unit,
    permission: @Composable () -> Unit,
    model: @Composable () -> Unit,
    primary: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = {
        Box { accessories() }
        Box { leading() }
        Box(propagateMinConstraints = true) { editor() }
        Box { permission() }
        Box { model() }
        Box { primary() }
    }) { children, constraints ->
        val progress = expansion.coerceIn(0f, 1f)
        fun interpolate(from: Int, to: Int) = (from + (to - from) * progress).roundToInt()
        val width = constraints.maxWidth
        val horizontal = interpolate(6.dp.roundToPx(), 8.dp.roundToPx())
        val vertical = interpolate(5.dp.roundToPx(), 8.dp.roundToPx())
        val innerWidth = (width - horizontal * 2).coerceAtLeast(1)
        val loose = Constraints(maxWidth = innerWidth, maxHeight = constraints.maxHeight)
        val leadingPlaceable = children[1].measure(loose)
        val permissionPlaceable = children[3].measure(loose)
        val primaryPlaceable = children[5].measure(loose)
        val controlHeight = maxOf(leadingPlaceable.height, permissionPlaceable.height, primaryPlaceable.height)
        val modelWidth = (innerWidth - leadingPlaceable.width - permissionPlaceable.width - primaryPlaceable.width).coerceAtLeast(0)
        val modelPlaceable = children[4].measure(Constraints(maxWidth = modelWidth, maxHeight = constraints.maxHeight))
        val rowHeight = maxOf(controlHeight, modelPlaceable.height)
        val accessoriesPlaceable = children[0].measure(loose)

        // The editor's own 8dp start padding replaces the gap beside the compact leading button.
        val compactEditorInset = (leadingPlaceable.width - 8.dp.roundToPx()).coerceAtLeast(0)
        val editorInset = interpolate(compactEditorInset, 0)
        val trailingInset = interpolate(primaryPlaceable.width, 0)
        val editorWidth = (innerWidth - editorInset - trailingInset).coerceAtLeast(1)
        val availableEditorHeight = if (constraints.hasBoundedHeight)
            (constraints.maxHeight - accessoriesPlaceable.height - rowHeight - 16.dp.roundToPx()).coerceAtLeast(1)
        else Constraints.Infinity
        val editorPlaceable = children[2].measure(Constraints(minWidth = editorWidth, maxWidth = editorWidth, maxHeight = availableEditorHeight))

        // Preserve measured draft lines in both endpoints. Never switch maxLines at the last frame.
        val compactRowHeight = maxOf(editorPlaceable.height, leadingPlaceable.height, primaryPlaceable.height)
        val compactHeight = accessoriesPlaceable.height + compactRowHeight + 10.dp.roundToPx()
        val expandedHeight = accessoriesPlaceable.height + editorPlaceable.height + rowHeight + 16.dp.roundToPx()
        val height = constraints.constrainHeight(interpolate(compactHeight, expandedHeight))
        val editorY = vertical + accessoriesPlaceable.height + interpolate((compactRowHeight - editorPlaceable.height) / 2, 0)
        val expandedControlsY = vertical + accessoriesPlaceable.height + editorPlaceable.height
        fun controlY(controlHeightPx: Int) = interpolate(
            vertical + accessoriesPlaceable.height + (compactRowHeight - controlHeightPx) / 2,
            expandedControlsY + (rowHeight - controlHeightPx) / 2,
        )
        layout(width, height) {
            accessoriesPlaceable.placeRelative(horizontal, vertical)
            editorPlaceable.placeRelative(horizontal + editorInset, editorY)
            // Secondary controls are unplaced at zero, excluding invisible hit targets as well.
            // Their opacity is a local transition choice; card geometry never relies on a fade.
            if (progress > 0f) {
                permissionPlaceable.placeRelativeWithLayer(horizontal + leadingPlaceable.width, controlY(permissionPlaceable.height)) { alpha = progress }
                modelPlaceable.placeRelativeWithLayer(width - horizontal - primaryPlaceable.width - modelPlaceable.width,
                    controlY(modelPlaceable.height)) { alpha = progress }
            }
            leadingPlaceable.placeRelative(horizontal, controlY(leadingPlaceable.height))
            primaryPlaceable.placeRelative(width - horizontal - primaryPlaceable.width, controlY(primaryPlaceable.height))
        }
    }
}
