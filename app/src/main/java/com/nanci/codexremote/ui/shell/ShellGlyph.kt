package com.nanci.codexremote.ui.shell

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.R
import com.nanci.codexremote.ui.theme.NativeTheme

val ShellInk: Color @Composable get() = NativeTheme.colors.ink
val ShellMuted: Color @Composable get() = NativeTheme.colors.muted
val ShellBackground: Color @Composable get() = NativeTheme.colors.background
val ShellField: Color @Composable get() = NativeTheme.colors.field

/** Exact DS vector paths from the reference Android APK's Compose resource assets.
 * The resource bytes are reused; some project-specific actions select a semantic DS icon,
 * rather than asserting that the original app exposes that action at the same location.
 * See work/fix-all-20261117/shell-native/icon-mapping.json and shell-fixes.md for provenance.
 */
@Composable
fun ShellGlyph(name: String, modifier: Modifier = Modifier.size(24.dp), tint: Color = ShellInk, description: String? = null) {
    Icon(painterResource(shellGlyphResource(name)), contentDescription = description, modifier = modifier, tint = tint)
}

private fun shellGlyphResource(name: String): Int = when (name) {
    "plus" -> R.drawable.ds_plus_lg_regular_24
    "x" -> R.drawable.ds_xmark_lg_regular_24
    "down" -> R.drawable.ds_arrow_down_lg_regular_24
    "back" -> R.drawable.ds_arrow_left_lg_regular_24
    "chevron" -> R.drawable.ds_chevron_down_sm_regular_24
    "right" -> R.drawable.ds_chevron_right_md_regular_24
    "search" -> R.drawable.ds_magnifying_glass_lg_regular_24
    "menu" -> R.drawable.ds_sidebar_menu_regular_24
    "laptop" -> R.drawable.ds_laptop_regular_24
    "cloud" -> R.drawable.ds_cloud_regular_24
    "folder" -> R.drawable.ds_folder_regular_24
    "folder-down" -> R.drawable.ds_folder_badge_arrow_down_regular_24
    "folder-plus" -> R.drawable.ds_folder_badge_plus_regular_24
    "folder-minus" -> R.drawable.ds_folder_badge_xmark_regular_24
    "pencil" -> R.drawable.ds_pencil_regular_24
    "new" -> R.drawable.ds_square_and_pencil_regular_24
    "more" -> R.drawable.ds_ellipsis_vertical_regular_24
    "copy" -> R.drawable.ds_square_on_square_regular_24
    "archive" -> R.drawable.ds_archive_regular_24
    "trash" -> R.drawable.ds_trash_regular_24
    "pin", "pin-tilted" -> R.drawable.ds_pin_regular_24
    "unpin", "unpin-tilted" -> R.drawable.ds_pin_slash_regular_24
    "unread", "pending" -> R.drawable.ds_circle_regular_24
    "check" -> R.drawable.ds_checkmark_md_regular_24
    "clock" -> R.drawable.ds_clock_regular_24
    "bell" -> R.drawable.ds_bell_regular_24
    "settings" -> R.drawable.ds_gear_regular_24
    "camera" -> R.drawable.ds_camera_regular_24
    "photos" -> R.drawable.ds_photo_on_tilted_square_regular_24
    "file" -> R.drawable.ds_document_regular_24
    "plan" -> R.drawable.ds_list_checkmark_circle_regular_24
    "hand" -> R.drawable.ds_hand_palm_regular_24
    "shield" -> R.drawable.ds_shield_checkmark_regular_24
    "readonly" -> R.drawable.ds_shield_lock_regular_24
    "warning" -> R.drawable.ds_triangle_exclamationmark_regular_24
    "send" -> R.drawable.ds_arrow_up_lg_regular_24
    "stop" -> R.drawable.ds_stop_fill_regular_24
    "link" -> R.drawable.ds_link_regular_24
    else -> R.drawable.ds_circle_regular_24
}
