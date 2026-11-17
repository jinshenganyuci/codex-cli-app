package com.nanci.codexremote.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nanci.codexremote.ui.theme.NativeTheme
import com.nanci.codexremote.ui.theme.NativeTypography

/** Local first-install setup, not a replica of a reference ChatGPT screen. */
@Composable
fun BootstrapUi(coordinator: BootstrapCoordinator, onManual: () -> Unit) {
    val state = coordinator.state
    if (state.phase == BootstrapPhase.Hidden) return
    val working = state.phase != BootstrapPhase.Failed
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(28.dp),
        containerColor = NativeTheme.colors.surface,
        title = { Text(if (working) "正在准备连接" else "首次连接未完成", color = NativeTheme.colors.ink, style = NativeTypography.title22) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NativeTheme.colors.ink, trackColor = NativeTheme.colors.border)
                Text(state.message ?: if (state.phase == BootstrapPhase.Cancelling) "正在停止…"
                    else "首次打开需要激活这台手机，完成后会自动连接。", color = NativeTheme.colors.muted, style = NativeTypography.body14)
            }
        },
        confirmButton = {
            if (state.phase == BootstrapPhase.Failed) TextButton(onClick = coordinator::retry) { Text("重试", color = NativeTheme.colors.ink, style = NativeTypography.body14) }
        },
        dismissButton = {
            TextButton(enabled = state.phase != BootstrapPhase.Cancelling,
                onClick = { coordinator.cancel(onManual) }) { Text(if (working) "取消并手动配置" else "手动配置", color = NativeTheme.colors.muted, style = NativeTypography.body14) }
        },
    )
}
