package com.nanci.codexremote.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

private fun reasoningLabel(value: String) = when (value.lowercase()) { "none" -> "无"; "minimal" -> "最低"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; "max" -> "Max"; "ultra" -> "Ultra"; else -> value }
private fun permissionGlyph(value: ShellPermission) = when (value) { ShellPermission.Default -> "hand"; ShellPermission.AutoReview -> "shield"; ShellPermission.ReadOnly -> "readonly"; ShellPermission.FullAccess -> "warning"; ShellPermission.Custom -> "settings" }
private fun permissionLabel(value: ShellPermission) = when (value) { ShellPermission.Default -> "默认权限"; ShellPermission.AutoReview -> "自动审核"; ShellPermission.ReadOnly -> "只读"; ShellPermission.FullAccess -> "完整访问权限"; ShellPermission.Custom -> "自定义权限" }
private fun permissionDescription(value: ShellPermission) = when (value) { ShellPermission.Default -> "在沙盒中运行命令"; ShellPermission.AutoReview -> "自动审核提权请求"; ShellPermission.ReadOnly -> "编辑文件或运行命令需经批准"; ShellPermission.FullAccess -> "电脑完全访问权限（风险较高）"; ShellPermission.Custom -> "使用服务器的自定义配置" }

/** Parent owns state/actions and vertical system insets; this component owns its horizontal margin. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(state: ComposerState, actions: ComposerActions, modifier: Modifier = Modifier) {
    var attachmentsMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableIntStateOf(0) }
    var modelParent by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val ime = rememberComposerImeState()
    // Ordinary soft-keyboard focus alone does not pre-expand the card before IME starts.
    val editingTarget = ime.layoutExpanded
    val editingProgress by animateFloatAsState(
        targetValue = if (ime.widthExpanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1500f, visibilityThreshold = .01f),
        label = "composerEditingInsets",
    )
    var consumedInsets by remember { mutableStateOf<WindowInsets>(WindowInsets(0, 0, 0, 0)) }
    val horizontalInsets = composerHorizontalPadding(editingProgress, consumedInsets)
    val direction = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    val editorTextStyle = TextStyle(color = ShellInk, fontSize = 16.sp, lineHeight = 24.sp)
    // The APK's ordinary rci0/jci0 layout uses a separate 100ms transition. Both animations
    // observe the same IME target, so collapsing never waits for the keyboard's final frame.
    val layoutProgress by animateFloatAsState(
        targetValue = if (editingTarget) 1f else 0f,
        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
        label = "composerLayoutExpansion",
    )
    val selectedModel = state.models.firstOrNull { it.id == state.modelId }
    val modelLabel = selectedModel?.label ?: state.modelId.ifBlank { "模型" }
    // Draft content is retained while reading history; it does not make the editor focused.
    // Attachments/plan keep their content rows even when the outer card is compact.
    val hasAccessories = state.attachments.isNotEmpty() || state.planMode
    val attachmentActions = actions.copy(onAttachment = { action ->
        // A system picker must not leave a stale editor focus behind when its result returns.
        // Model/permission menus continue to preserve the existing editor and IME focus.
        focusManager.clearFocus()
        keyboard?.hide()
        actions.onAttachment(action)
    })
    val validAttachments = state.attachments.all { !it.loading && it.error == null }
    val canSend = state.enabled && !state.submitting && validAttachments && (state.text.isNotBlank() || state.attachments.isNotEmpty())
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest > 0) {
            ime.focusSession.onFocusRequest()
            focusRequester.requestFocus(); keyboard?.show()
            actions.onFocusRequestHandled(state.focusRequest)
        }
    }
    LaunchedEffect(state.permissions.isEmpty()) { if(state.permissions.isEmpty()) permissionMenu=false }
    ComposerMenuBackHandler(attachmentsMenu || permissionMenu || modelMenu>0) {
        when {
            modelMenu>0 && modelParent>0 -> { modelMenu=modelParent;modelParent=0 }
            modelMenu>0 -> { modelMenu=0;modelParent=0 }
            permissionMenu -> permissionMenu=false
            attachmentsMenu -> attachmentsMenu=false
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().onConsumedWindowInsetsChanged { consumedInsets = it }) {
        val compactSides = horizontalInsets.compact.calculateLeftPadding(direction) + horizontalInsets.compact.calculateRightPadding(direction)
        val primaryWidth = if (state.submitting || state.running || state.text.isNotBlank() || state.attachments.isNotEmpty()) 36.dp else 6.dp
        // Predict overflow at the compact endpoint, independent of current animation width. Using
        // the live field line count here would oscillate between inline and expanded layouts.
        val compactTextWidth = with(density) { (maxWidth - compactSides - 12.dp - 36.dp - primaryWidth - 8.dp).roundToPx().coerceAtLeast(1) }
        val multilineDraft = remember(state.text, compactTextWidth, textMeasurer, density.fontScale, density.density) {
            state.text.isNotEmpty() && textMeasurer.measure(state.text, style = editorTextStyle,
                maxLines = 6, constraints = Constraints(maxWidth = compactTextWidth)).let { it.lineCount > 1 || it.didOverflowWidth }
        }
        val keepContentExpanded = hasAccessories || multilineDraft
        val expansion = if (keepContentExpanded) 1f else layoutProgress
        val controlsInteractive = expansion >= .999f
        LaunchedEffect(editingTarget, keepContentExpanded) {
            if (!editingTarget && !keepContentExpanded) { permissionMenu = false; modelMenu = 0; modelParent = 0 }
        }
        // Corner interpolation is a local continuity choice; native evidence establishes the
        // measure/place transition, not an exact radius animation curve.
        val composerShape = RoundedCornerShape((30f + (24f - 30f) * expansion).dp)
        Surface(
            Modifier.fillMaxWidth().padding(horizontalInsets.current).shadow(
                elevation = 20.dp,
                shape = composerShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = .05f),
                spotColor = Color.Black.copy(alpha = .03f),
            ),
            shape = composerShape,
            color = Color.White,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            ComposerLayout(
                expansion = expansion,
                modifier = Modifier.fillMaxWidth(),
                accessories = {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.attachments.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.attachments.forEach { attachment -> AttachmentChip(attachment) { actions.onRemoveAttachment(attachment.id) } }
                            }
                        }
                        if (state.planMode) {
                            Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 6.dp).clip(CircleShape).background(ShellField).clickable { actions.onPlanMode(false) }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                ShellGlyph("plan", Modifier.size(16.dp), ShellMuted); Spacer(Modifier.width(6.dp)); Text("计划", color = ShellInk, fontSize = 14.sp, lineHeight = 20.sp); Spacer(Modifier.width(6.dp)); ShellGlyph("x", Modifier.size(14.dp), ShellMuted)
                            }
                        }
                    }
                },
                leading = {
                    Box {
                        ComposerIconButton("plus", "添加附件", { attachmentsMenu = true })
                        AttachmentMenu(attachmentsMenu, { attachmentsMenu = false }, state, attachmentActions)
                    }
                },
                editor = {
                    BasicTextField(
                        value = state.text,
                        onValueChange = actions.onTextChange,
                        enabled = state.editorEnabled,
                        textStyle = editorTextStyle,
                        cursorBrush = SolidColor(Color(0xFF3566F0)),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 1,
                        // Keep the same editor and line policy while the card changes its measured
                        // width/height. A retained multiline draft is never truncated on IME dismissal.
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).focusRequester(focusRequester).onFocusChanged {
                            if (ime.focusSession.onFocusChanged(it.isFocused)) actions.onFocus()
                        }.observeComposerEditorTouch(ime.focusSession, state.editorEnabled).padding(horizontal = 8.dp, vertical = 8.dp),
                        decorationBox = { field ->
                            Box {
                                if (state.text.isEmpty()) Text(state.placeholder, color = Color(0xFF969696), fontSize = 16.sp, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                field()
                            }
                        },
                    )
                },
                permission = {
                    Box(if (controlsInteractive) Modifier else Modifier.clearAndSetSemantics { }) {
                        ComposerIconButton(permissionGlyph(state.permission), permissionLabel(state.permission), { permissionMenu = true },
                            tint = if (state.permission == ShellPermission.FullAccess) Color(0xFFB27A25) else ShellMuted,
                            enabled = controlsInteractive && state.permissions.isNotEmpty(), dimWhenDisabled = state.permissions.isEmpty())
                        ShellMenu(permissionMenu, { permissionMenu = false }, Modifier.width(300.dp), preserveEditorFocus = true) {
                            state.permissions.forEach { permission ->
                                MenuRow(permissionLabel(permission), permissionGlyph(permission), permissionDescription(permission), checked = state.permission == permission, color = ShellInk) {
                                    permissionMenu = false; actions.onPermission(permission)
                                }
                            }
                        }
                    }
                },
                model = {
                    Box(if (controlsInteractive) Modifier else Modifier.clearAndSetSemantics { }) {
                        Row(Modifier.heightIn(min = 34.dp).widthIn(max = 205.dp).clip(CircleShape).clickable(enabled = controlsInteractive && state.models.isNotEmpty()) {
                            modelParent = 0; modelMenu = if (selectedModel?.reasoning?.isNotEmpty() == true) 1 else 2
                        }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val shortModel = modelLabel.removePrefix("GPT-")
                            val reason = reasoningLabel(state.reasoning)
                            Text(listOf(shortModel, reason).filter { it.isNotBlank() }.joinToString(" "), color = if (state.models.isNotEmpty()) ShellInk else ShellMuted, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ShellMenu(modelMenu > 0, { modelMenu = 0; modelParent = 0 }, Modifier.width(if (modelMenu == 1) 224.dp else 292.dp), preserveEditorFocus = true) {
                            when (modelMenu) {
                                1 -> {
                                    selectedModel?.reasoning.orEmpty().forEach { reason -> MenuRow(reasoningLabel(reason), checked = state.reasoning == reason) { actions.onReasoning(reason); modelMenu = 0 } }
                                    MenuDivider()
                                    MenuRow("模型", subtitle = modelLabel, trailing = true) { modelParent = 1; modelMenu = 2 }
                                    if (state.supportsFast) MenuRow("速度", subtitle = if (state.fast) "快速" else "常速", trailing = true) { modelParent = 1; modelMenu = 3 }
                                }
                                2 -> {
                                    MenuRow("模型", subtitle = modelLabel, trailing = true) { modelMenu = modelParent; modelParent = 0 }
                                    MenuDivider()
                                    state.models.forEach { model -> MenuRow(model.label, subtitle = model.description, checked = model.id == state.modelId) { actions.onModel(model.id); modelMenu = 0 } }
                                }
                                3 -> {
                                    MenuRow("速度", subtitle = if (state.fast) "快速" else "常速", trailing = true) { modelMenu = modelParent; modelParent = 0 }
                                    MenuDivider()
                                    MenuRow("常速", subtitle = "默认速度", checked = !state.fast) { actions.onFast(false); modelMenu = 0 }
                                    MenuRow("快速", subtitle = "更快的响应速度", checked = state.fast) { actions.onFast(true); modelMenu = 0 }
                                }
                            }
                        }
                    }
                },
                primary = {
                    ComposerPrimaryButton(state, canSend, actions) {
                        actions.onSend(); focusManager.clearFocus(); keyboard?.hide()
                    }
                },
            )
        }
    }
}

@Composable private fun ComposerIconButton(glyph: String, label: String, action: () -> Unit, tint: Color = ShellInk,
    enabled: Boolean = true, dimWhenDisabled: Boolean = true) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled=enabled,onClick = action), contentAlignment = Alignment.Center) {
        ShellGlyph(glyph,Modifier.size(24.dp),if(enabled || !dimWhenDisabled) tint else tint.copy(alpha=.35f),label)
    }
}

@Composable private fun ComposerPrimaryButton(state: ComposerState, canSend: Boolean, actions: ComposerActions, send: () -> Unit) {
    when {
        state.submitting -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ShellInk) }
        state.running && state.text.isBlank() -> Box(Modifier.size(36.dp).clip(CircleShape).background(Color.Black).clickable(onClick = actions.onStop), contentAlignment = Alignment.Center) { ShellGlyph("stop", Modifier.size(24.dp), Color.White, "停止") }
        state.text.isNotBlank() || state.attachments.isNotEmpty() -> Box(Modifier.size(36.dp).clip(CircleShape).background(if (canSend) Color.Black else Color(0xFFE3E3E3)).clickable(enabled = canSend, onClick = send), contentAlignment = Alignment.Center) { ShellGlyph("send", Modifier.size(22.dp), if (canSend) Color.White else Color(0xFFA8A8A8), "发送") }
        // The original mic/waveform actions are deliberately omitted; no fake send button in idle state.
        else -> Spacer(Modifier.width(6.dp))
    }
}

@Composable private fun AttachmentMenu(expanded: Boolean, dismiss: () -> Unit, state: ComposerState, actions: ComposerActions) {
    ShellMenu(expanded, dismiss, Modifier.width(290.dp),preserveEditorFocus=true) {
        if (state.allowCamera) MenuRow("拍照", "camera") { dismiss(); actions.onAttachment(AttachmentAction.Camera) }
        if (state.allowPhotos) MenuRow("上传照片", "photos") { dismiss(); actions.onAttachment(AttachmentAction.Photos) }
        if (state.allowFiles) MenuRow("上传文件", "file") { dismiss(); actions.onAttachment(AttachmentAction.Files) }
        MenuRow("计划模式", "plan", checked = state.planMode) { dismiss(); actions.onPlanMode(!state.planMode) }
        // Plugin names are environment-specific. They are not hardcoded from the reference account.
    }
}

@Composable private fun AttachmentChip(attachment: ShellAttachment, onRemove: () -> Unit) {
    Box(Modifier.size(if (attachment.thumbnail != null) 88.dp else 154.dp, if (attachment.thumbnail != null) 126.dp else 74.dp).clip(RoundedCornerShape(14.dp)).background(ShellField)) {
        if (attachment.thumbnail != null) {
            Image(attachment.thumbnail.asImageBitmap(), contentDescription = attachment.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                ShellGlyph("file", Modifier.size(24.dp), ShellMuted); Spacer(Modifier.width(8.dp))
                Column { Text(attachment.label, color = ShellInk, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); if (attachment.error != null) Text(attachment.error, color = Color(0xFFD91843), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1) }
            }
        }
        if (attachment.loading) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), strokeWidth = 2.dp, color = ShellInk)
        Box(Modifier.padding(4.dp).size(23.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.White.copy(alpha = .92f)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) { ShellGlyph("x", Modifier.size(16.dp), description = "移除 ${attachment.label}") }
    }
}
