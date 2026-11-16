package com.nanci.codexremote.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.util.Locale

/** New local-client update UI; no claim that this dialog was extracted from the reference APK. */
@Composable
fun UpdateUi(coordinator: UpdateCoordinator) {
    val context = LocalContext.current
    val state = coordinator.state
    fun canInstall() = Build.VERSION.SDK_INT < 26 || runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        coordinator.onInstallerReturned()
    }
    fun launchInstaller(action: UpdateInstallAction.OpenInstaller) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", action.apk.file)
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            intent.clipData = ClipData.newRawUri("更新安装包", uri)
            installer.launch(intent)
        } catch (_: Exception) { coordinator.onExternalLaunchFailed() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (coordinator.onPermissionReturned(canInstall()) as? UpdateInstallAction.OpenInstaller)?.let(::launchInstaller)
    }
    fun install() {
        when (val action = coordinator.requestInstall(canInstall())) {
            UpdateInstallAction.RequestPermission -> try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) { coordinator.onExternalLaunchFailed() }
            is UpdateInstallAction.OpenInstaller -> launchInstaller(action)
            null -> Unit
        }
    }

    val release = state.release ?: return
    if (state.phase in setOf(UpdatePhase.Hidden, UpdatePhase.PermissionSettings, UpdatePhase.Installer)) return
    val ink = Color(0xFF0D0D0D)
    val secondary = Color(0xFF5D5D5D)
    val title = when (state.phase) {
        UpdatePhase.Available -> "发现新版本 ${release.versionName}"
        UpdatePhase.Downloading -> "正在下载更新"
        UpdatePhase.Ready -> "更新已下载"
        UpdatePhase.DownloadError -> "下载未完成"
        else -> "应用更新"
    }
    AlertDialog(
        onDismissRequest = coordinator::dismiss,
        shape = RoundedCornerShape(28.dp), containerColor = Color.White,
        title = { Text(title, color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.phase != UpdatePhase.Available) Text("版本 ${release.versionName}", color = secondary, fontSize = 14.sp)
                if (state.phase == UpdatePhase.Downloading) {
                    val total = state.progress.totalBytes.takeIf { it > 0 } ?: release.sizeBytes
                    val fraction = if (total > 0) (state.progress.downloadedBytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = ink, trackColor = Color(0xFFECECEC))
                    Text("${sizeLabel(state.progress.downloadedBytes)} / ${sizeLabel(total)}", color = secondary, fontSize = 13.sp)
                }
                state.message?.let { Text(it, color = secondary, fontSize = 14.sp, lineHeight = 21.sp) }
                if (state.phase == UpdatePhase.Ready && state.message == null) Text("更新包已验证，准备好后即可安装。", color = secondary, fontSize = 14.sp, lineHeight = 21.sp)
                if (state.phase == UpdatePhase.Available) {
                    if (release.releaseNotes.isNotEmpty()) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        release.releaseNotes.forEach { note -> Text(note, color = ink, fontSize = 14.sp, lineHeight = 21.sp) }
                    }
                    Text("下载大小 ${sizeLabel(release.sizeBytes)}", color = secondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.Available -> TextButton(onClick = coordinator::download) { Text("下载更新", color = ink, fontWeight = FontWeight.SemiBold) }
                UpdatePhase.DownloadError -> TextButton(onClick = coordinator::download) { Text("重试", color = ink, fontWeight = FontWeight.SemiBold) }
                UpdatePhase.Ready -> TextButton(onClick = ::install) { Text("安装", color = ink, fontWeight = FontWeight.SemiBold) }
                UpdatePhase.Downloading -> TextButton(onClick = coordinator::dismiss) { Text("取消下载", color = ink) }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.phase != UpdatePhase.Downloading) TextButton(onClick = coordinator::dismiss) { Text("稍后", color = secondary) }
        },
    )
}

private fun sizeLabel(bytes: Long): String = String.format(Locale.CHINA, "%.1f MB", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
