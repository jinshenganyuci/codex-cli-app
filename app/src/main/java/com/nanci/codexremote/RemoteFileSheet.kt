package com.nanci.codexremote

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import com.nanci.codexremote.ui.theme.NativeTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Native p2e1/lp0 defaults and resources: docs/APK_REFERENCE.md, file-download audit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFileSheet(state: RemoteFileState, onDismiss: () -> Unit, onDownload: () -> Unit,
    onOpen: (SavedRemoteFile) -> Unit, onOpenImage: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val view = LocalView.current
    val density = LocalDensity.current
    val radius = if (Build.VERSION.SDK_INT >= 31) {
        val insets = view.rootWindowInsets
        val corners = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            .map { insets?.getRoundedCorner(it)?.radius ?: 0 }
        if (corners.first() > 0 && corners.distinct().size == 1) with(density) { corners.first().toDp() } else 24.dp
    } else 24.dp
    val colors = NativeTheme.colors
    val ink = colors.ink
    val muted = colors.muted
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = radius, topEnd = radius), dragHandle = null,
        containerColor = colors.surface, contentColor = ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (state.phase) {
                    RemoteFilePhase.Loading -> "正在加载文件…"
                    RemoteFilePhase.Error -> "无法打开文件"
                    RemoteFilePhase.Ready -> "远程文件"
                }, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = .1.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    if (state.phase == RemoteFilePhase.Ready) {
                        IconButton(onClick = onDownload, enabled = !state.saving, modifier = Modifier.size(32.dp)) {
                            if (state.saving) CircularProgressIndicator(Modifier.size(24.dp), color = ink, strokeWidth = 2.dp)
                            else Icon(painterResource(R.drawable.ic_remote_download), "下载", Modifier.size(24.dp))
                        }
                    }
                    state.saved?.let { saved ->
                        IconButton(onClick = { onOpen(saved) }, modifier = Modifier.size(32.dp)) {
                            Icon(painterResource(R.drawable.ic_remote_open), "打开", Modifier.size(24.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (state.phase) {
                RemoteFilePhase.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = ink, strokeWidth = 2.dp)
                    FileBody("正在通过远程控制读取此文件。")
                }
                RemoteFilePhase.Error -> FileBody(state.error ?: "无法读取此文件，请重新打开。")
                RemoteFilePhase.Ready -> {
                    if (state.image != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val height = (maxWidth * (state.image.height.toFloat() / state.image.width)).coerceIn(48.dp, 360.dp)
                        Image(state.image.asImageBitmap(), state.displayName,
                            // p2e1 uses Shapes.small; default provider ccz0.b/wcz0.h is 8dp.
                            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = "全屏查看图像", onClick = onOpenImage))
                    } else if (state.text != null) SelectionContainer {
                        Text(state.text, Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
                    } else FileBody("此文件类型不支持预览。你仍可将其保存到“下载”文件夹。")
                    Text("${state.displayName} (${remoteFileSize(state.size ?: 0)})", Modifier.padding(top = 8.dp),
                        color = muted, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .2.sp)
                }
            }
            Text(state.path, Modifier.padding(top = 8.dp), color = muted, fontSize = 12.sp,
                lineHeight = 16.sp, letterSpacing = .2.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            state.saveError?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp, lineHeight = 20.sp) }
        }
    }
}

@Composable
private fun FileBody(text: String) {
    Text(text, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = .1.sp, fontWeight = FontWeight.Normal)
}

private fun remoteFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}
