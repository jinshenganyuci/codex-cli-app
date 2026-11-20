package com.nanci.codexremote.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nanci.codexremote.core.approvalDecisionsFor
import com.nanci.codexremote.ui.theme.NativeTypography
import org.json.JSONArray
import org.json.JSONObject

private data class ApprovalDetails(val title: String, val text: String, val code: Boolean = false)

/** Never infer an approval from the visible label: every row carries its exact wire decision. */
@Composable
fun ApprovalCard(
    method: String, params: JSONObject, enabled: Boolean = true,
    onReply: (JSONObject, String?) -> Unit, modifier: Modifier = Modifier,
) {
    val request = params.toString()
    var feedback by remember(method, request) { mutableStateOf("") }
    var details by remember(method, request) { mutableStateOf<ApprovalDetails?>(null) }
    val permissions = method == "item/permissions/requestApproval"
    val legacy = method == "execCommandApproval" || method == "applyPatchApproval"
    val fileChange = method.contains("fileChange") || method == "applyPatchApproval"
    val decisions = remember(method, request) { approvalDecisionsFor(method, params) }
    val accept = decisions.firstOrNull { it == "accept" || it == "approved" }
    val cancel = decisions.firstOrNull { !permissions && (it == "cancel" || it == "abort") }
    val deny = decisions.firstOrNull(::isDeclineDecision)
    fun respond(value: Any, note: String? = null) { onReply(JSONObject().put("decision", value), note) }
    fun permissionReply(grant: Boolean, scope: String, note: String? = null) {
        val result = JSONObject()
        if (grant) {
            val requested = params.optJSONObject("permissions") ?: JSONObject()
            for (key in listOf("network", "fileSystem")) {
                requested.optJSONObject(key)?.let { result.put(key, it) }
            }
        }
        onReply(JSONObject().put("permissions", result).put("scope", scope), note)
    }
    fun decline() {
        val note = feedback.takeIf { it.isNotBlank() }
        when {
            permissions -> permissionReply(false, "turn", note)
            legacy && (deny == "denied" || deny is JSONObject) ->
                respond(JSONObject().put("denied", JSONObject().put("rejection", feedback)))
            deny != null -> respond(deny, note)
        }
    }
    val title = when {
        permissions -> "Codex 请求额外权限"
        fileChange -> "批准文件更改？"
        else -> "批准运行此命令？"
    }
    val command = remember(request) {
        when (val raw = params.opt("command")) {
            is JSONArray -> raw.strings().joinToString(" ")
            JSONObject.NULL, null -> ""
            else -> raw.toString()
        }
    }
    ContentCard(modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .7f).dp)) {
        Text(title, color = ContentInk, style = NativeTypography.body16Medium)
        // The body can shrink with the window while the title and immediate actions stay visible.
        key(method, request) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 8.dp).testTag("approval-scroll")
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val reason = params.text("reason")
                if (reason.isNotEmpty()) ApprovalPreview(reason, "请求原因", NativeTypography.body14,
                    reviewLabel = "查看完整原因", onReview = { details = it })
                if (command.isNotEmpty()) {
                    ApprovalPreview(command, "完整命令", NativeTypography.code13, maxLines = 4, code = true,
                        reviewLabel = "查看完整命令", onReview = { details = it })
                }
                val cwd = params.text("cwd")
                if (cwd.isNotEmpty()) ApprovalPreview(cwd, "工作目录", NativeTypography.caption12,
                    reviewLabel = "查看完整路径", onReview = { details = it })
                for (name in listOf("permissions", "additionalPermissions", "networkApprovalContext", "changes", "fileChanges")) {
                    val detail = params.opt(name) ?: continue
                    if (detail == JSONObject.NULL) continue
                    val label = when (name) {
                        "changes", "fileChanges" -> "文件更改"
                        "networkApprovalContext" -> "网络访问"
                        else -> "请求的权限"
                    }
                    val value = remember(request, name) { approvalRequestDetails(name, detail) }
                    Column {
                        Text(label, color = ContentInk, style = NativeTypography.caption12Medium)
                        ApprovalPreview(value, label, NativeTypography.caption12, maxLines = 2,
                            reviewLabel = "查看全部${label}", onReview = { details = it })
                    }
                }
                val grantRoot = params.text("grantRoot")
                if (grantRoot.isNotBlank()) ApprovalPreview("写入范围：$grantRoot", "写入范围", NativeTypography.caption12,
                    reviewLabel = "查看完整路径", onReview = { details = it })

                if (permissions) {
                    HorizontalDivider(color = ContentBorder)
                    ApprovalAction("始终批准", "当前会话", enabled) { permissionReply(true, "session") }
                } else {
                    for (decision in decisions) {
                        if (decision in listOf("accept", "approved", "cancel", "abort") || isDeclineDecision(decision)) continue
                        val rule = (decision as? JSONObject)?.let { value ->
                            remember(request, decision) { approvalRuleDescription(value) }
                        }
                        val session = decision == "acceptForSession" || decision == "approved_for_session"
                        val label = when {
                            session -> "始终批准"
                            decision is JSONObject && (decision.has("acceptWithExecpolicyAmendment") || decision.has("approved_execpolicy_amendment")) ->
                                "批准并记住此命令规则"
                            decision is JSONObject && (decision.has("applyNetworkPolicyAmendment") || decision.has("network_policy_amendment")) ->
                                "应用网络规则"
                            else -> "当前版本不支持此审批选项"
                        }
                        val note = when {
                            session -> "当前会话"
                            rule != null -> rule
                            else -> "请在服务器上的 Codex 客户端核对该选项"
                        }
                        Column {
                            HorizontalDivider(color = ContentBorder)
                            ApprovalAction(label, note, enabled && (session || rule != null)) { respond(decision) }
                            if (rule != null) ApprovalReviewLink("查看完整规则") {
                                details = ApprovalDetails("完整规则", rule)
                            }
                        }
                    }
                }
                if (permissions || deny != null) {
                    Column {
                        HorizontalDivider(color = ContentBorder)
                        FeedbackRow(enabled, feedback, { feedback = it }, { decline() }, { decline() }, "告诉 Codex 应该如何处理")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        if (permissions || accept != null || cancel != null) {
            HorizontalDivider(color = ContentBorder)
            Row(Modifier.fillMaxWidth().testTag("approval-actions").padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (cancel != null) Box(Modifier.weight(2f), contentAlignment = Alignment.CenterStart) {
                    TextButton(enabled = enabled, onClick = { respond(cancel) }) {
                        Text("取消本次请求", color = if (enabled) ContentInk else ContentMuted, style = NativeTypography.body14Medium)
                    }
                }
                if (permissions || accept != null) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    PillButton("批准", enabled) {
                        if (permissions) permissionReply(true, "turn") else accept?.let { respond(it) }
                    }
                }
            }
        }
    }
    details?.let { ApprovalDetailsDialog(it, onClose = { details = null }) }
}

private fun isDeclineDecision(value: Any): Boolean =
    value == "decline" || value == "denied" || (value is JSONObject && value.has("denied"))

@Composable
private fun ApprovalPreview(
    value: String, title: String, style: TextStyle, reviewLabel: String, maxLines: Int = 3,
    code: Boolean = false, onReview: (ApprovalDetails) -> Unit,
) {
    var clipped by remember(value) { mutableStateOf(false) }
    val preview = if (code) Modifier.fillMaxWidth().testTag("approval-command-text").clip(RoundedCornerShape(8.dp))
        .background(ContentField).padding(12.dp) else Modifier.fillMaxWidth()
    Column {
        Text(value, preview, color = if (code) ContentInk else ContentMuted, style = style, maxLines = maxLines,
            overflow = TextOverflow.Ellipsis, onTextLayout = { clipped = it.hasVisualOverflow })
        if (clipped) ApprovalReviewLink(reviewLabel) { onReview(ApprovalDetails(title, value, code)) }
    }
}

@Composable
private fun ApprovalReviewLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = ContentInk, style = NativeTypography.body14Medium)
    }
}

@Composable
private fun ApprovalDetailsDialog(details: ApprovalDetails, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        ContentCard(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp)) {
            Text(details.title, color = ContentInk, style = NativeTypography.body16Medium)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("approval-details-scroll")
                .verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(details.text, color = ContentInk,
                        style = if (details.code) NativeTypography.code13 else NativeTypography.body14)
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("关闭", color = ContentInk, style = NativeTypography.body14Medium)
            }
        }
    }
}

/** Display only; the original decision object is returned unchanged to the server. */
private fun approvalRuleDescription(decision: JSONObject): String? {
    val exec=decision.optJSONObject("acceptWithExecpolicyAmendment")
        ?: decision.optJSONObject("approved_execpolicy_amendment")
    if(exec!=null) {
        val prefix=exec.optJSONArray("execpolicy_amendment") ?: exec.optJSONArray("proposed_execpolicy_amendment") ?: return null
        if(prefix.length()==0 || (0 until prefix.length()).any { prefix.opt(it) !is String }) return null
        return "以后允许："+prefix.strings().joinToString(" ") { arg ->
            if(arg.isEmpty() || arg.any { it.isWhitespace() || it=='\"' || it=='\\' })
                "\""+arg.replace("\\","\\\\").replace("\"","\\\"")+"\"" else arg
        }
    }
    val network=(decision.optJSONObject("applyNetworkPolicyAmendment")
        ?: decision.optJSONObject("network_policy_amendment"))?.optJSONObject("network_policy_amendment") ?: return null
    val host=network.text("host").takeIf { it.isNotBlank() } ?: return null
    return when(network.text("action")) {
        "allow" -> "以后允许访问：$host"
        "deny" -> "以后禁止访问：$host"
        else -> null
    }
}

private fun approvalRequestDetails(key: String,detail: Any): String = when(key) {
    "networkApprovalContext" -> (detail as? JSONObject)?.let { context ->
        val host=context.text("host")
        val protocol=context.text("protocol")
        if(host.isNotBlank()) "请求连接：$host"+(if(protocol.isNotBlank()) "（${protocol.uppercase()}）" else "")
        else "需要核对网络访问目标"
    } ?: "需要核对网络访问目标"
    "permissions","additionalPermissions" -> readablePermissions(detail as? JSONObject)
    "changes","fileChanges" -> readableFileChanges(detail)
    else -> "需要核对请求内容"
}

private fun readablePermissions(profile: JSONObject?): String {
    if(profile==null) return "需要核对权限范围"
    val lines=mutableListOf<String>()
    val network=profile.optJSONObject("network")
    if(network!=null && !network.isNull("enabled")) {
        when(network.opt("enabled")) {
            true -> lines+="网络：允许联网"
            false -> lines+="网络：禁止联网"
            else -> lines+="网络：需要核对访问范围"
        }
    }
    val files=profile.optJSONObject("fileSystem")
    if(files!=null) {
        for(access in listOf("read","write")) {
            files.optJSONArray(access).strings().forEach { path -> lines+="${if(access=="read") "读取" else "写入"}：$path" }
        }
        files.optJSONArray("entries").objects().forEach { entry ->
            val action=when(entry.text("access")) {"read"->"读取";"write"->"写入";"deny"->"禁止访问";else->"待核对的访问权限"}
            lines+="$action：${readablePermissionPath(entry.opt("path"))}"
        }
        if(files.has("globScanMaxDepth") && !files.isNull("globScanMaxDepth")) lines+="目录搜索深度：${files.optInt("globScanMaxDepth")} 层"
    }
    val unknown=profile.keys().asSequence().any { it !in setOf("network","fileSystem") && !profile.isNull(it) }
    if(unknown) lines+="另有需要在服务器端核对的权限范围"
    return lines.joinToString("\n").ifBlank { "需要核对权限范围" }
}

private fun readablePermissionPath(value: Any?): String {
    if(value is String) return value
    val path=value as? JSONObject ?: return "未识别的路径范围"
    return when(path.text("type")) {
        "path" -> path.text("path").ifBlank { "未指定路径" }
        "glob_pattern" -> "匹配 ${path.text("pattern")} 的路径"
        "special" -> {
            val special=path.optJSONObject("value") ?: return "未识别的路径范围"
            val label=when(special.text("kind")) {
                "root" -> "整个文件系统"
                "minimal" -> "运行所需的最小目录"
                "project_roots" -> "项目目录"
                "tmpdir" -> "系统临时目录"
                "slash_tmp" -> "/tmp"
                else -> "需要核对的特殊目录"
            }
            label+special.text("subpath").takeIf { it.isNotBlank() }?.let { " / $it" }.orEmpty()
        }
        else -> "未识别的路径范围"
    }
}

private fun readableFileChanges(detail: Any): String {
    val changes=when(detail) {
        is JSONArray -> detail.objects().map { it.text("path") to it }
        is JSONObject -> detail.keys().asSequence().map { path -> path to (detail.optJSONObject(path) ?: JSONObject()) }.toList()
        else -> emptyList()
    }
    return changes.joinToString("\n") { (path,change) ->
        val kind=change.optJSONObject("kind")
        val type=kind?.text("type") ?: change.text("type")
        val destination=kind?.text("move_path") ?: change.text("move_path")
        val operation=when(type) {"add"->"新增";"delete"->"删除";"update"->if(destination.isNotBlank()) "移动并修改" else "修改";else->"待核对的更改"}
        val file=path.ifBlank { "未指定文件" }
        val target=if(destination.isNotBlank()) " → $destination" else ""
        "$operation：$file$target"
    }.ifBlank { "需要在服务器端核对文件更改" }
}

@Composable
private fun ApprovalAction(label: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ContentIcon("check", Modifier.size(16.dp), if (enabled) ContentMuted else ContentMuted.copy(alpha = .4f))
        Column(Modifier.weight(1f)) {
            Text(label, style = NativeTypography.body14, color = if (enabled) ContentInk else ContentMuted)
            Text(detail, style = NativeTypography.caption12, color = ContentMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
