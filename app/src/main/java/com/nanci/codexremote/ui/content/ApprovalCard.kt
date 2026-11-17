package com.nanci.codexremote.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.core.approvalDecisionsFor
import org.json.JSONArray
import com.nanci.codexremote.ui.theme.NativeTypography
import com.nanci.codexremote.ui.theme.NativeTheme
import org.json.JSONObject

/** Never infer an approval from the visible label: every row carries its exact wire decision. */
@Composable
fun ApprovalCard(method: String, params: JSONObject, enabled: Boolean = true,
    onReply: (JSONObject, String?) -> Unit, modifier: Modifier = Modifier) {
    var feedback by remember(method,params.toString()) { mutableStateOf("") }
    val permissions=method=="item/permissions/requestApproval"
    val legacy=method=="execCommandApproval" || method=="applyPatchApproval"
    val fileChange=method.contains("fileChange") || method=="applyPatchApproval"
    val decisions=remember(method,params.toString()) { approvalDecisionsFor(method,params) }
    val canDecline=permissions || decisions.any { it=="decline" || it=="denied" || it is JSONObject && it.has("denied") }
    val canCancel=!permissions && decisions.any { it=="cancel" || it=="abort" }
    fun respond(value: Any, note: String? = null) { onReply(JSONObject().put("decision",value),note) }
    fun permissionReply(grant: Boolean,scope: String,note: String? = null) {
        val result=JSONObject()
        if(grant) {
            val requested=params.optJSONObject("permissions") ?: JSONObject()
            for(key in listOf("network","fileSystem")) {
                val value=requested.optJSONObject(key)
                if(value!=null) result.put(key,value)
            }
        }
        onReply(JSONObject().put("permissions",result).put("scope",scope),note)
    }
    fun decline() {
        val note=feedback.takeIf { it.isNotBlank() }
        when {
            permissions -> permissionReply(false,"turn",note)
            legacy -> respond(JSONObject().put("denied",JSONObject().put("rejection",feedback)),null)
            else -> respond("decline",note)
        }
    }
    ContentCard(modifier) {
        Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.7f).dp).verticalScroll(rememberScrollState())) {
            Text(when { permissions -> "Codex 请求额外权限";fileChange -> "批准文件更改？";else -> "批准运行此命令？" },
                style=NativeTypography.body16,fontWeight=FontWeight.Medium,color=ContentInk)
            val reason=params.text("reason")
            if(reason.isNotEmpty()) Text(reason,Modifier.padding(top=8.dp),color=ContentInk,style=NativeTypography.body14)
            val command=params.opt("command").let { if(it is JSONArray) it.strings().joinToString(" ") else if(it==JSONObject.NULL || it==null) "" else it.toString() }
            if(command.isNotEmpty()) Text(command,Modifier.padding(top=8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(ContentField).padding(12.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=18.sp,color=ContentInk)
            if(params.text("cwd").isNotEmpty()) Text(params.text("cwd"),Modifier.padding(top=6.dp),style=NativeTypography.caption12,color=ContentMuted)
            for(key in listOf("permissions","additionalPermissions","networkApprovalContext","changes","fileChanges")) {
                val detail=params.opt(key)
                if(detail!=null && detail!=JSONObject.NULL) {
                    var expanded by remember(method,params.toString(),key) { mutableStateOf(permissions || fileChange) }
                    Text(when(key){"changes","fileChanges"->"文件更改";"networkApprovalContext"->"网络访问";else->"请求的权限"} + if(expanded) " ⌄" else " ›",
                        Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(top=8.dp,bottom=4.dp),fontSize=12.sp,color=ContentMuted)
                    if(expanded) Text(approvalRequestDetails(key,detail),
                        fontSize=12.sp,lineHeight=18.sp,color=ContentMuted)
                }
            }
            if(params.text("grantRoot").isNotBlank()) Text("写入范围：${params.text("grantRoot")}",
                Modifier.padding(top=8.dp),fontSize=12.sp,lineHeight=18.sp,color=ContentMuted)
            Spacer(Modifier.height(12.dp))
            if(permissions) {
                ApprovalAction("批准","仅本轮对话",enabled) {permissionReply(true,"turn")}
                HorizontalDivider(color=ContentBorder)
                ApprovalAction("始终批准","当前会话",enabled) {permissionReply(true,"session")}
            } else {
                decisions.filter { it!="decline" && it!="denied" && it!="cancel" && it!="abort" && !(it is JSONObject && it.has("denied")) }.forEachIndexed { index,decision ->
                    if(index>0) HorizontalDivider(color=ContentBorder)
                    val label=when(decision) { "accept","approved"->"批准";"acceptForSession","approved_for_session"->"始终批准";else -> when {
                        decision is JSONObject && (decision.has("acceptWithExecpolicyAmendment") || decision.has("approved_execpolicy_amendment")) -> "批准并记住此命令规则"
                        decision is JSONObject && (decision.has("applyNetworkPolicyAmendment") || decision.has("network_policy_amendment")) -> "应用网络规则"
                        else -> "当前版本不支持此审批选项"
                    } }
                    val supported=decision=="accept" || decision=="approved" || decision=="acceptForSession" || decision=="approved_for_session" ||
                        decision is JSONObject && approvalRuleDescription(decision)!=null
                    val detail=when {
                        decision=="acceptForSession" || decision=="approved_for_session" -> "当前会话"
                        decision is JSONObject -> approvalRuleDescription(decision) ?: "请在服务器上的 Codex 客户端核对该规则"
                        else -> ""
                    }
                    ApprovalAction(label,detail,enabled&&supported) { respond(decision) }
                }
            }
            if(canDecline) {
                HorizontalDivider(color=ContentBorder)
                FeedbackRow(enabled,feedback,{feedback=it},{decline()},{decline()},"告诉 Codex 应该如何处理")
            }
            if(canCancel) Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.End) {
                Text("取消本次请求",Modifier.clickable(enabled=enabled){respond(if(legacy) "abort" else "cancel")}.padding(vertical=8.dp),
                    style=NativeTypography.caption12,color=ContentMuted)
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
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight=40.dp).clickable(enabled=enabled,onClick=onClick).padding(vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ContentIcon("check",Modifier.size(16.dp),if(enabled) ContentMuted else ContentMuted.copy(alpha=.4f))
        Column(Modifier.weight(1f)) {
            Text(label,style=NativeTypography.body14,color=if(enabled) ContentInk else ContentMuted)
            if(detail.isNotEmpty()) Text(detail,style=NativeTypography.caption12,color=ContentMuted)
        }
    }
}
