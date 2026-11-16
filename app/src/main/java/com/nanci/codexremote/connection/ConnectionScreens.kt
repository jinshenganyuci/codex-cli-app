package com.nanci.codexremote.connection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import java.io.ByteArrayOutputStream

private val Ink = Color(0xFF0D0D0D)
private val Secondary = Color(0xFF5D5D5D)
private val FieldBackground = Color(0xFFF3F3F3)

/** Caller owns the emitted credential and must close it after saving/connecting. */
@Composable
fun ConnectionScreen(
    saved: SavedConnection? = null,
    connected: Boolean = false,
    onSave: (ConnectionProfile, SshCredential?) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    saving: Boolean = false,
    error: String? = null,
) {
    val context = LocalContext.current
    var name by remember(saved?.id) { mutableStateOf(saved?.profile?.name ?: "") }
    var host by remember(saved?.id) { mutableStateOf(saved?.profile?.host ?: "") }
    var port by remember(saved?.id) { mutableStateOf((saved?.profile?.port ?: 22).toString()) }
    var user by remember(saved?.id) { mutableStateOf(saved?.profile?.user ?: "codex") }
    var fingerprint by remember(saved?.id) { mutableStateOf(saved?.profile?.fingerprint ?: "") }
    var codexPath by remember(saved?.id) { mutableStateOf(saved?.profile?.codexPath ?: "~/.local/bin/codex") }
    var algorithm by remember(saved?.id) { mutableStateOf(saved?.profile?.hostKeyAlgorithm ?: "ecdsa-sha2-nistp256") }
    var password by remember(saved?.id) { mutableStateOf("") }
    var passphrase by remember(saved?.id) { mutableStateOf("") }
    var passwordMode by remember(saved?.id) { mutableStateOf(false) }
    var privateKey by remember(saved?.id) { mutableStateOf<ByteArray?>(null) }
    var keyImported by remember(saved?.id) { mutableStateOf(false) }
    var advanced by remember(saved?.id) { mutableStateOf(false) }
    var localError by remember(saved?.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(privateKey) {
        val ownedKey = privateKey
        onDispose { ownedKey?.fill(0) }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    try {
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > ConnectionStore.MAX_SECRET) throw IllegalArgumentException()
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().also { require(it.isNotEmpty()) }
                    } finally { buffer.fill(0) }
                } ?: throw IllegalArgumentException()
                privateKey?.fill(0)
                privateKey = bytes
                keyImported = true
                passwordMode = false
                localError = null
            } catch (_: Exception) {
                localError = "无法导入文件，请选择不超过 64 KB 的 SSH 私钥。"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().imePadding()) {
        PageHeader(if (saved == null) "添加连接" else "编辑连接", onCancel)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(FieldBackground), contentAlignment = Alignment.Center) {
                    DeviceGlyph(Modifier.size(26.dp))
                }
                Column {
                    Text("连接你的 Codex", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (connected) "已连接" else "在手机上继续电脑中的聊天", color = Secondary, fontSize = 14.sp)
                }
                if (connected) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF4AA453)))
            }
            Spacer(Modifier.height(2.dp))
            EntryField(name, { name = it }, "显示名称", "例如：我的开发服务器", enabled = !saving)
            EntryField(host, { host = it }, "服务器地址", "192.168.1.100", enabled = !saving)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryField(user, { user = it }, "用户名", "codex", modifier = Modifier.weight(1f), enabled = !saving)
                EntryField(port, { port = it.filter(Char::isDigit).take(5) }, "端口", "22", modifier = Modifier.width(104.dp), numeric = true, enabled = !saving)
            }
            EntryField(fingerprint, { fingerprint = it.trim() }, "主机指纹", "SHA256:…", enabled = !saving)
            Text("填写从服务器可信渠道核对过的 SSH 指纹。", color = Secondary, fontSize = 12.sp, lineHeight = 18.sp)

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FieldBackground).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("身份验证", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthChoice("SSH 密钥", !passwordMode, Modifier.weight(1f)) { passwordMode = false; password = "" }
                    AuthChoice("密码", passwordMode, Modifier.weight(1f)) { passwordMode = true }
                }
                if (passwordMode) {
                    EntryField(password, { password = it }, "密码", if (saved?.hasCredential == true) "留空保留已保存的凭据" else "服务器登录密码", secret = true, enabled = !saving)
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                            .clickable(enabled = !saving) { importer.launch(arrayOf("*/*")) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(when { keyImported -> "已导入 SSH 私钥"; saved?.hasCredential == true -> "已保存凭据 · 点击更换密钥"; else -> "选择 SSH 私钥文件" }, modifier = Modifier.weight(1f), color = Ink, fontSize = 14.sp)
                        Text("›", color = Secondary, fontSize = 24.sp)
                    }
                    if (keyImported) EntryField(passphrase, { passphrase = it }, "私钥口令（可选）", "未加密的私钥无需填写", secret = true, enabled = !saving)
                }
                Text("凭据仅加密保存在此设备上。", color = Secondary, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("高级设置", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (advanced) "⌃" else "⌄", color = Secondary, fontSize = 20.sp)
            }
            if (advanced) {
                EntryField(codexPath, { codexPath = it }, "Codex 路径", "~/.local/bin/codex", enabled = !saving)
                EntryField(algorithm, { algorithm = it }, "主机密钥算法", "ecdsa-sha2-nistp256", enabled = !saving)
            }
            (localError ?: error)?.let { Text(it, color = Color(0xFFC13B35), fontSize = 14.sp, lineHeight = 20.sp) }
            Button(
                enabled = !saving,
                onClick = {
                    val parsedPort = port.toIntOrNull()
                    localError = when {
                        host.isBlank() || host.any(Char::isWhitespace) -> "请输入有效的服务器地址。"
                        user.isBlank() -> "请输入服务器用户名。"
                        parsedPort == null || parsedPort !in 1..65535 -> "端口应为 1 至 65535。"
                        !Regex("^SHA256:[A-Za-z0-9+/]{43}=?$").matches(fingerprint.trim()) -> "请填写完整的 SHA256 主机指纹。"
                        algorithm.isBlank() || codexPath.isBlank() -> "请填写完整的高级连接设置。"
                        !keyImported && password.isBlank() && saved?.hasCredential != true -> "请导入 SSH 私钥或输入密码。"
                        passwordMode && password.isBlank() && saved?.hasCredential != true -> "请输入服务器登录密码。"
                        else -> null
                    }
                    if (localError == null) {
                        val credential = when {
                            passwordMode && password.isNotEmpty() -> SshCredential(password = password.toCharArray())
                            !passwordMode && privateKey != null -> SshCredential(privateKey = privateKey!!.clone(), passphrase = passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
                            else -> null
                        }
                        onSave(ConnectionProfile(host.trim(), parsedPort!!, user.trim(), fingerprint.trim(), name.trim().ifEmpty { host.trim() }, algorithm.trim(), codexPath.trim()), credential)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("保存并连接", fontSize = 16.sp)
            }
            if (onDelete != null) TextButton(onClick = { confirmDelete = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text("删除连接", color = Color(0xFFC13B35), fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp).navigationBarsPadding())
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除连接？") },
        text = { Text("此设备保存的连接信息和凭据将被移除。服务器上的聊天会保留。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("删除", color = Color(0xFFC13B35)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Ink) } },
        containerColor = Color.White,
    )
}

@Composable
fun SettingsScreen(
    connections: List<SavedConnection>,
    activeId: String?,
    workspace: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SavedConnection) -> Unit,
    onSelect: (SavedConnection) -> Unit,
    onWorkspaceChange: (String) -> Unit,
    version: String = "0.2.0",
) {
    var directory by remember(workspace) { mutableStateOf(workspace) }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().imePadding()) {
        PageHeader("设置", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("连接")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FieldBackground)) {
                connections.forEachIndexed { index, connection ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(connection) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeviceGlyph(Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(connection.profile.name, color = Ink, fontSize = 16.sp)
                            Text(connection.profile.host, color = Secondary, fontSize = 12.sp)
                        }
                        if (activeId == connection.id) Text("✓", color = Ink, fontSize = 20.sp)
                        TextButton(onClick = { onEdit(connection) }) { Text("编辑", color = Secondary, fontSize = 14.sp) }
                    }
                    if (index < connections.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp), color = Color(0xFFE4E4E4))
                }
                if (connections.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 16.dp), color = Color(0xFFE4E4E4))
                Row(Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("＋", color = Ink, fontSize = 24.sp, modifier = Modifier.width(34.dp))
                    Text("添加连接", color = Ink, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            SectionLabel("新聊天")
            EntryField(directory, { directory = it }, "默认工作目录", "留空使用服务器默认目录")
            Text("已有聊天会继续使用各自的工作目录。", color = Secondary, fontSize = 12.sp, lineHeight = 18.sp)
            val validDirectory = directory.isBlank() || directory.trim().startsWith("/")
            if (!validDirectory) Text("请输入服务器上的绝对路径，例如 /home/codex/project。", color = Color(0xFFC13B35), fontSize = 12.sp)
            if (directory.trim() != workspace) Button(enabled = validDirectory, onClick = { onWorkspaceChange(directory.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(24.dp)) {
                Text("保存工作目录")
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("版本", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(version, color = Secondary, fontSize = 14.sp)
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable private fun SectionLabel(label: String) { Text(label, color = Secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

@Composable private fun AuthChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(38.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) Ink else Color.White).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Color.White else Ink, fontSize = 14.sp)
    }
}

@Composable private fun PageHeader(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.align(Alignment.CenterStart).size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(24.dp)) {
                val width = 2.dp.toPx()
                drawLine(Ink, Offset(size.width * .60f, size.height * .22f), Offset(size.width * .32f, size.height * .50f), width, StrokeCap.Round)
                drawLine(Ink, Offset(size.width * .32f, size.height * .50f), Offset(size.width * .60f, size.height * .78f), width, StrokeCap.Round)
            }
        }
    }
}

@Composable private fun DeviceGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        drawRoundRect(Ink, Offset(size.width * .18f, size.height * .12f), androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .55f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(Ink, Offset(size.width * .10f, size.height * .82f), Offset(size.width * .90f, size.height * .82f), stroke, StrokeCap.Round)
    }
}

@Composable private fun EntryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    secret: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 14.sp) }, placeholder = { Text(placeholder, fontSize = 14.sp) },
        singleLine = true, enabled = enabled, shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { numeric -> KeyboardType.Number; secret -> KeyboardType.Password; else -> KeyboardType.Text }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink, unfocusedBorderColor = Color(0xFFDADADA), focusedLabelColor = Secondary, unfocusedLabelColor = Secondary, cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    )
}
