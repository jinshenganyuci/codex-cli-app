package com.nanci.codexremote.connection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import com.nanci.codexremote.ui.theme.NativeTheme
import com.nanci.codexremote.ui.theme.NativeTypography
import com.nanci.codexremote.ui.theme.AppColorMode
import com.nanci.codexremote.ui.shell.ShellGlyph
import com.nanci.codexremote.ui.shell.ShellMenu

private val Ink: Color @Composable get() = NativeTheme.colors.ink
private val Secondary: Color @Composable get() = NativeTheme.colors.muted
private val FieldBackground: Color @Composable get() = NativeTheme.colors.field

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

    val keyReader = remember(saved?.id) { PrivateKeyImporter(context) }
    var importing by remember(saved?.id) { mutableStateOf(false) }
    // Keep the launch owner even if the same composition switches to another connection.
    var pickerOwner by remember { mutableStateOf<PrivateKeyImporter?>(null) }
    DisposableEffect(keyReader) { onDispose { keyReader.close() } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val owner = pickerOwner
        pickerOwner = null
        if (uri != null && owner === keyReader) {
            importing = true
            localError = null
            keyReader.read(uri) { result ->
                importing = false
                result.onSuccess { bytes ->
                    privateKey?.fill(0)
                    privateKey = bytes
                    keyImported = true
                    passwordMode = false
                }.onFailure { failure -> localError = failure.message }
            }
        }
    }
    val busy = saving || importing

    Column(Modifier.fillMaxSize().background(NativeTheme.colors.surface).statusBarsPadding().imePadding()) {
        PageHeader(if (saved == null) "添加连接" else "编辑连接", onCancel)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(FieldBackground), contentAlignment = Alignment.Center) {
                    DeviceGlyph(Modifier.size(26.dp))
                }
                Column {
                    Text("连接你的 Codex", color = Ink, style = NativeTypography.title22)
                    Text(if (connected) "已连接" else "在手机上继续电脑中的聊天", color = Secondary, fontSize = 14.sp)
                }
                if (connected) Box(Modifier.size(7.dp).clip(CircleShape).background(NativeTheme.colors.success))
            }
            Spacer(Modifier.height(2.dp))
            EntryField(name, { name = it }, "显示名称", "例如：我的开发服务器", enabled = !busy)
            EntryField(host, { host = it }, "服务器地址", "192.168.1.100", enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryField(user, { user = it }, "用户名", "codex", modifier = Modifier.weight(1f), enabled = !busy)
                EntryField(port, { port = it.filter(Char::isDigit).take(5) }, "端口", "22", modifier = Modifier.width(104.dp), numeric = true, enabled = !busy)
            }
            EntryField(fingerprint, { fingerprint = it.trim() }, "主机指纹", "SHA256:…", enabled = !busy)
            Text("填写从服务器可信渠道核对过的 SSH 指纹。", color = Secondary, style = NativeTypography.caption12)

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FieldBackground).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("身份验证", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthChoice("SSH 密钥", !passwordMode, Modifier.weight(1f), enabled = !busy) { passwordMode = false; password = "" }
                    AuthChoice("密码", passwordMode, Modifier.weight(1f), enabled = !busy) { passwordMode = true }
                }
                if (passwordMode) {
                    EntryField(password, { password = it }, "密码", if (saved?.hasCredential == true) "留空保留已保存的凭据" else "服务器登录密码", secret = true, enabled = !busy)
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NativeTheme.colors.surface)
                            .clickable(enabled = !busy, role = Role.Button) {
                                pickerOwner = keyReader
                                try { importer.launch(arrayOf("*/*")) }
                                catch (_: Exception) { pickerOwner = null; localError = "无法打开文件选择器，请稍后重试。" }
                            }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(when { importing -> "正在读取文件…"; keyImported -> "已导入 SSH 私钥"; saved?.hasCredential == true -> "已保存凭据 · 点击更换密钥"; else -> "选择 SSH 私钥文件" }, modifier = Modifier.weight(1f), color = Ink, fontSize = 14.sp)
                        Text("›", color = Secondary, fontSize = 24.sp)
                    }
                    if (importing) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { keyReader.cancel(); importing = false }) { Text("取消读取", color = Ink) }
                    }
                    if (keyImported) EntryField(passphrase, { passphrase = it }, "私钥口令（可选）", "未加密的私钥无需填写", secret = true, enabled = !busy)
                }
                Text("凭据仅加密保存在此设备上。", color = Secondary, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { advanced = !advanced }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("高级设置", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (advanced) "⌃" else "⌄", color = Secondary, fontSize = 20.sp)
            }
            if (advanced) {
                EntryField(codexPath, { codexPath = it }, "Codex 路径", "~/.local/bin/codex", enabled = !busy)
                EntryField(algorithm, { algorithm = it }, "主机密钥算法", "ecdsa-sha2-nistp256", enabled = !busy)
            }
            (localError ?: error)?.let { Text(it, color = NativeTheme.colors.danger, fontSize = 14.sp, lineHeight = 20.sp) }
            Button(
                enabled = !busy,
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = NativeTheme.colors.surface),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = NativeTheme.colors.surface, strokeWidth = 2.dp)
                else Text("保存并连接", fontSize = 16.sp)
            }
            if (onDelete != null) TextButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("删除连接", color = NativeTheme.colors.danger, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp).navigationBarsPadding())
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除连接？") },
        text = { Text("此设备保存的连接信息和凭据将被移除。服务器上的聊天会保留。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("删除", color = NativeTheme.colors.danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Ink) } },
        containerColor = NativeTheme.colors.surface,
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
    colorMode: AppColorMode = AppColorMode.Light,
    onColorModeChange: (AppColorMode) -> Unit = {},
) {
    var directory by remember(workspace) { mutableStateOf(workspace) }
    Column(Modifier.fillMaxSize().background(NativeTheme.colors.surface).statusBarsPadding().imePadding()) {
        PageHeader("设置", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("外观")
            ColorModeSetting(colorMode, onColorModeChange)
            Spacer(Modifier.height(6.dp))
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
                    if (index < connections.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp), color = NativeTheme.colors.border)
                }
                if (connections.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 16.dp), color = NativeTheme.colors.border)
                Row(Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("＋", color = Ink, fontSize = 24.sp, modifier = Modifier.width(34.dp))
                    Text("添加连接", color = Ink, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            SectionLabel("新聊天")
            EntryField(directory, { directory = it }, "默认工作目录", "留空使用服务器默认目录")
            Text("已有聊天会继续使用各自的工作目录。", color = Secondary, style = NativeTypography.caption12)
            val validDirectory = directory.isBlank() || directory.trim().startsWith("/")
            if (!validDirectory) Text("请输入服务器上的绝对路径，例如 /home/codex/project。", color = NativeTheme.colors.danger, fontSize = 12.sp)
            if (directory.trim() != workspace) Button(enabled = validDirectory, onClick = { onWorkspaceChange(directory.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = NativeTheme.colors.surface), shape = RoundedCornerShape(24.dp)) {
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

@Composable
private fun ColorModeSetting(mode: AppColorMode, onChange: (AppColorMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FieldBackground)
            .semantics { stateDescription = mode.label }
            .clickable(role = Role.Button, onClickLabel = "选择色彩") { expanded = true }
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("色彩", color = Ink, style = NativeTypography.body16, modifier = Modifier.weight(1f))
            Text(mode.label, color = Secondary, style = NativeTypography.body14)
            Spacer(Modifier.width(8.dp))
            ShellGlyph("right", Modifier.size(24.dp), Secondary)
        }
        ShellMenu(expanded, { expanded = false }) {
            Column(Modifier.selectableGroup()) {
                AppColorMode.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = option == mode, role = Role.RadioButton, onClick = {
                            expanded = false
                            onChange(option)
                        }).padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, color = Ink, style = NativeTypography.menu16, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                        if (option == mode) ShellGlyph("check", Modifier.size(24.dp), Ink)
                        else Spacer(Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun SectionLabel(label: String) { Text(label, color = Secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

@Composable private fun AuthChoice(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(if (selected) Ink else NativeTheme.colors.surface)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) NativeTheme.colors.surface else Ink, style = NativeTypography.body14)
    }
}

@Composable private fun PageHeader(title: String, onBack: () -> Unit) {
    val ink = Ink
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(title, color = Ink, style = NativeTypography.title22, modifier = Modifier.padding(horizontal = 56.dp))
        Box(Modifier.align(Alignment.CenterStart).size(48.dp).clip(CircleShape).semantics { contentDescription = "返回" }.clickable(role = Role.Button, onClickLabel = "返回", onClick = onBack), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(24.dp)) {
                val width = 2.dp.toPx()
                drawLine(ink, Offset(size.width * .60f, size.height * .22f), Offset(size.width * .32f, size.height * .50f), width, StrokeCap.Round)
                drawLine(ink, Offset(size.width * .32f, size.height * .50f), Offset(size.width * .60f, size.height * .78f), width, StrokeCap.Round)
            }
        }
    }
}

@Composable private fun DeviceGlyph(modifier: Modifier) {
    val ink = Ink
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        drawRoundRect(ink, Offset(size.width * .18f, size.height * .12f), androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .55f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(ink, Offset(size.width * .10f, size.height * .82f), Offset(size.width * .90f, size.height * .82f), stroke, StrokeCap.Round)
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
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink, unfocusedBorderColor = NativeTheme.colors.border, focusedLabelColor = Secondary, unfocusedLabelColor = Secondary, cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink),
        textStyle = NativeTypography.body16,
    )
}
