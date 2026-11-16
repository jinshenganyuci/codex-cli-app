package com.nanci.codexremote.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.core.UserInputs
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Presents the flat app-server ThreadItem without discarding unfamiliar fields. */
@Composable
fun MessageContent(
    item: JSONObject,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
    onPlanOpen: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onDownload: ((String) -> Unit)? = null,
    imageLoader: ImageLoader? = null,
    onOpenImage: (ImageSource) -> Unit = {},
    onCopyImage: ((ImageSource) -> Unit)? = null,
) {
    val type = item.messageString("type")
    Column(modifier.fillMaxWidth()) {
        when (type) {
            "userMessage" -> UserMessage(item,imageLoader,onOpenImage)
            "agentMessage" -> {
                val text = item.messageString("text")
                if (text.isNotBlank()) {
                    MarkdownContent(text, onOpenLink = onOpenLink)
                    IconButton(onClick = { onCopy(text) }, modifier = Modifier.padding(top = 10.dp).size(36.dp)) {
                        ContentIcon("copy", Modifier.size(21.dp), ContentInk)
                    }
                }
            }
            "plan" -> PlanPreview(item.messageString("text"), onOpen = onPlanOpen, onCopy = onCopy, onDownload = onDownload)
            "commandExecution" -> CommandItem(item, onCopy)
            "fileChange" -> FileChanges(item, onCopy)
            "reasoning" -> {
                val text = item.optJSONArray("summary").messageStrings().joinToString("\n\n")
                    .ifBlank { item.optJSONArray("content").messageStrings().joinToString("\n\n") }
                if (text.isNotBlank()) ExpandableItem("思考", item.messageString("status")) {
                    MarkdownContent(text, onOpenLink = onOpenLink)
                }
            }
            "mcpToolCall", "dynamicToolCall" -> ToolItem(item,onCopy,onOpenLink,imageLoader,onOpenImage)
            "functionCallOutput" -> ExpandableItem(item.messageString("name").ifBlank { "工具结果" }) {
                OutputContent(item.opt("output").messageValue(), onCopy)
            }
            "collabAgentToolCall" -> ExpandableItem("子任务 · ${item.messageString("tool")}", item.messageString("status")) {
                item.messageString("prompt").takeIf { it.isNotBlank() }?.let { MarkdownContent(it, onOpenLink = onOpenLink) }
                val agents = item.optJSONObject("agentsStates")
                agents?.keys()?.asSequence()?.toList()?.forEach { id ->
                    val agent = agents.optJSONObject(id)
                    Text(id.take(24), color = ContentMuted, fontSize = 12.sp)
                    Text(agent?.messageString("status").orEmpty(), color = ContentInk, fontSize = 14.sp)
                    agent?.messageString("message")?.takeIf { it.isNotBlank() }?.let { MarkdownContent(it, onOpenLink = onOpenLink) }
                }
            }
            "webSearch" -> ExpandableItem("搜索网页", item.messageString("status")) {
                val query = item.messageString("query").ifBlank { item.optJSONObject("action")?.messageString("query").orEmpty() }
                if (query.isNotBlank()) Text(query, color = ContentInk, fontSize = 14.sp)
                OutputContent(item.messageValue(), onCopy)
            }
            "imageView" -> ImageAttachment(item.messageString("path"),item.messageIdentity()+":view","图像",imageLoader,onOpenImage)
            "imageGeneration" -> GeneratedImageContent(item,imageLoader,onOpenImage,onCopyImage)
            "enteredReviewMode", "exitedReviewMode" -> {
                Text(if (type == "enteredReviewMode") "开始审查" else "审查完成", color = ContentMuted, fontSize = 12.sp)
                item.messageString("review").takeIf { it.isNotBlank() }?.let { MarkdownContent(it, onOpenLink = onOpenLink) }
            }
            "contextCompaction" -> Text("已整理聊天上下文", color = ContentMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            "sleep" -> ExpandableItem("等待", item.messageString("status")) { OutputContent(item.messageValue(), onCopy) }
            "subAgentActivity" -> ExpandableItem("子任务活动", item.messageString("kind")) { OutputContent(item.messageValue(), onCopy) }
            "hookPrompt" -> ExpandableItem("附加上下文") { OutputContent(item.messageValue(), onCopy) }
            else -> ExpandableItem(type.ifBlank { "消息详情" }) { OutputContent(item.messageValue(), onCopy) }
        }
    }
}

@Composable
private fun UserMessage(item: JSONObject,imageLoader: ImageLoader?,onOpenImage: (ImageSource) -> Unit) {
    val content = item.optJSONArray("content")
    val indexedParts=(0 until (content?.length() ?: 0)).mapNotNull { index ->
        content?.optJSONObject(index)?.let { IndexedValue(index,it) }
    }
    val parts=indexedParts.map { it.value }
    val visibleParts=indexedParts.filterNot { (_,part) ->
        part.messageString("type")=="text" && UserInputs.isAttachmentMetadata(part.messageString("text"),parts)
    }
    val groups=groupUserMessageParts(visibleParts)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            val (index,part)=group.first()
            key(item.messageIdentity(),index) {
                if(group.size>1) UserImageGroup(group,item.messageIdentity(),imageLoader,onOpenImage)
                else when (part.messageString("type")) {
                    "text" -> UserTextBubble(readableUserText(part.messageString("text")))
                    "image", "localImage" -> ImageAttachment(part.messageString("url").ifBlank { part.messageString("path") },
                        item.messageIdentity()+":user:$index","图片",imageLoader,onOpenImage)
                    "skill", "mention" -> Surface(color = ContentField, shape = RoundedCornerShape(12.dp)) {
                        Text(part.messageString("name"), color = ContentMuted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                    else -> Text("附件 · ${part.messageString("type")}", color = ContentMuted, fontSize = 12.sp)
                }
            }
        }
        if(visibleParts.isEmpty()) UserTextBubble(readableUserText(item.messageString("text")))
    }
}

/** Only adjacent images in this message share a gallery; original part indices identify previews. */
internal fun groupUserMessageParts(parts: List<IndexedValue<JSONObject>>): List<List<IndexedValue<JSONObject>>> {
    val groups=mutableListOf<MutableList<IndexedValue<JSONObject>>>()
    var imageRun: MutableList<IndexedValue<JSONObject>>?=null
    parts.forEach { part ->
        if(part.value.messageString("type") in setOf("image","localImage")) {
            val run=imageRun ?: mutableListOf<IndexedValue<JSONObject>>().also { groups.add(it);imageRun=it }
            run.add(part)
        } else {
            imageRun=null
            groups.add(mutableListOf(part))
        }
    }
    return groups
}

/** Verify that gallery grouping never consumes text, changes image order or crosses a message. */
internal fun userImageGroupingSmokeChecks(): List<String> = buildList {
    fun image(path: String)=JSONObject().put("type","localImage").put("path",path)
    val text=JSONObject().put("type","text").put("text","between images")
    val mixed=listOf(image("/a"),text,image("/b"),image("/c"),text,image("/d")).withIndex().toList()
    val groups=groupUserMessageParts(mixed)
    if(groups.map { it.map { part -> part.index } }!=listOf(listOf(0),listOf(1),listOf(2,3),listOf(4),listOf(5)))
        add("image grouping must preserve text boundaries and source indices")
    if(groupUserMessageParts(emptyList()).isNotEmpty()) add("empty image grouping")
    val six=(0..5).map { image("/$it") }.withIndex().toList()
    if(groupUserMessageParts(six).singleOrNull()?.map { it.value.messageString("path") }!=(0..5).map { "/$it" })
        add("all six images must remain individually accessible in order")
    if(groupUserMessageParts(listOf(IndexedValue(9,image("/single")))).singleOrNull()?.singleOrNull()?.index!=9)
        add("single image original index")
}

@Composable
private fun UserTextBubble(text: String) {
    if (text.isNotBlank()) Surface(
            color = ContentField,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer { Text(text, fontSize = 16.sp, lineHeight = 25.sp, color = ContentInk, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) }
        }
}

private fun readableUserText(text: String): String {
    val open = "<send_user_message_question_reply>"
    val close = "</send_user_message_question_reply>"
    val trimmed=text.trim()
    if (!trimmed.startsWith(open) || !trimmed.endsWith(close)) return text
    return runCatching {
        val raw = trimmed.substring(open.length,trimmed.length-close.length).trim()
        val tokenizer=JSONTokener(raw)
        val entries=tokenizer.nextValue() as? JSONArray ?: error("not an answer array")
        require(tokenizer.nextClean()=='\u0000') // Preserve trailing content instead of partially parsing it.
        require(entries.length()>0)
        (0 until entries.length()).map { index ->
            val answer=entries.optJSONObject(index) ?: error("invalid answer entry")
            val question=answer.opt("question") as? String ?: error("invalid question")
            val value=answer.opt("answer") as? String ?: error("invalid answer")
            require(value.isNotBlank())
            listOf(question,value).filter { it.isNotBlank() }.joinToString("\n")
        }.joinToString("\n\n")
    }.getOrDefault(text)
}

/** Exact-wrapper display conversion only; raw ThreadItem remains untouched. */
internal fun userMessageWrapperSmokeChecks(): List<String> = buildList {
    val valid="<send_user_message_question_reply>\n[{\"questionItemId\":\"q1\",\"question\":\"一加一等于几？\",\"answer\":\"2\"},{\"questionItemId\":\"q2\",\"question\":\"继续吗？\",\"answer\":\"继续\"}]\n</send_user_message_question_reply>"
    if(readableUserText(valid)!="一加一等于几？\n2\n\n继续吗？\n继续") add("answer wrapper display")
    val suffix="$valid\n其他文字"
    if(readableUserText(suffix)!=suffix) add("preserve text outside wrapper")
    val malformed="<send_user_message_question_reply>\n[{\"question\":\"问题\",\"answer\":42}]\n</send_user_message_question_reply>"
    if(readableUserText(malformed)!=malformed) add("preserve malformed answer")
    val trailing=valid.replace("]\n</", "] 其他内容\n</")
    if(readableUserText(trailing)!=trailing) add("preserve trailing content")
}


@Composable
private fun CommandItem(item: JSONObject, onCopy: (String) -> Unit) {
    val command = item.messageString("command")
    val status = item.messageString("status")
    val summary = item.optJSONArray("commandActions")?.let { actions ->
        actions.optJSONObject(0)?.let { action -> action.messageString("name").ifBlank { action.messageString("path") }.ifBlank { action.messageString("query") } }
    }.orEmpty().ifBlank { command.lineSequence().firstOrNull().orEmpty() }
    ExpandableItem(summary.ifBlank { "运行命令" }, status) {
        Text("命令", color = ContentMuted, fontSize = 12.sp)
        OutputContent(command, onCopy)
        item.messageString("cwd").takeIf { it.isNotBlank() }?.let { Text(it, color = ContentMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        val output = item.messageString("aggregatedOutput")
        if (output.isNotEmpty()) { Text("输出", color = ContentMuted, fontSize = 12.sp); OutputContent(output, onCopy) }
        val metadata = buildList {
            if (item.has("exitCode") && !item.isNull("exitCode")) add("退出码 ${item.optInt("exitCode")}")
            if (item.has("durationMs") && !item.isNull("durationMs")) add("${"%.1f".format(item.optLong("durationMs") / 1000.0)} 秒")
        }.joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, color = ContentMuted, fontSize = 12.sp)
    }
}

@Composable
private fun FileChanges(item: JSONObject, onCopy: (String) -> Unit) {
    val changes = item.optJSONArray("changes")
    ExpandableItem("${changes?.length() ?: 0} 个文件已更改", item.messageString("status")) {
        (0 until (changes?.length() ?: 0)).forEach { index ->
            val change = changes?.optJSONObject(index) ?: return@forEach
            val path = change.messageString("path")
            val diff = change.messageString("diff")
            val lines = remember(diff) { diff.lines() }
            val added = lines.count { it.startsWith('+') && !it.startsWith("+++") }
            val removed = lines.count { it.startsWith('-') && !it.startsWith("---") }
            var opened by remember(path) { mutableStateOf(true) }
            Column(Modifier.fillMaxWidth().background(Color(0xFFF9F9F9), RoundedCornerShape(10.dp))) {
                Row(Modifier.fillMaxWidth().clickable { opened = !opened }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContentIcon("chevron", Modifier.size(14.dp).rotate(if (opened) 90f else 0f))
                    Text(path.substringAfterLast('/'), color = ContentInk, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("+$added", color = Color(0xFF00A240), fontSize = 12.sp)
                    Text("−$removed", color = Color(0xFFE02E2A), fontSize = 12.sp)
                    IconButton(onClick = { onCopy(diff) }, modifier = Modifier.size(26.dp)) { ContentIcon("copy", Modifier.size(14.dp)) }
                }
                if (opened) {
                    val target = change.optJSONObject("kind")?.messageString("move_path").orEmpty()
                    if (target.isNotBlank()) Text("$path → $target", fontSize = 12.sp, color = ContentMuted, modifier = Modifier.padding(10.dp))
                    DiffContent(diff)
                }
            }
        }
    }
}

@Composable
private fun DiffContent(diff: String) {
    val lines = remember(diff) { diff.lines() }
    var showAll by remember(diff.length / 200) { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "关闭换行" else "自动换行", color = ContentMuted, fontSize = 12.sp) }
    }
    SelectionContainer {
        Column(if (wrap) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (if (showAll) lines else lines.take(200)).forEach { line ->
                val foreground = when {
                    line.startsWith('+') && !line.startsWith("+++") -> Color(0xFF00682A)
                    line.startsWith('-') && !line.startsWith("---") -> Color(0xFFAC2020)
                    line.startsWith("@@") -> Color(0xFF53739C)
                    else -> ContentInk
                }
                val background = when {
                    line.startsWith('+') && !line.startsWith("+++") -> Color(0xFFEAF8EE)
                    line.startsWith('-') && !line.startsWith("---") -> Color(0xFFFFEDED)
                    else -> Color.Transparent
                }
                Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, color = foreground, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 10.dp), softWrap = wrap)
            }
        }
    }
    if (!showAll && lines.size > 200) TextButton(onClick = { showAll = true }) { Text("显示全部 ${lines.size} 行", color = ContentMuted) }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToolItem(item: JSONObject,onCopy: (String) -> Unit,onOpenLink: (String) -> Unit,
    imageLoader: ImageLoader?,onOpenImage: (ImageSource) -> Unit) {
    val tool = listOf(item.messageString("server"), item.messageString("tool")).filter { it.isNotBlank() }.joinToString(" · ")
    ExpandableItem(tool.ifBlank { "工具调用" }, item.messageString("status")) {
        if (item.has("arguments") && !item.isNull("arguments")) { Text("参数", color = ContentMuted, fontSize = 12.sp); OutputContent(item.opt("arguments").messageValue(), onCopy) }
        val result = item.optJSONObject("result")
        val contents = result?.optJSONArray("content") ?: item.optJSONArray("contentItems")
        if (contents != null) (0 until contents.length()).forEach { index ->
            val content = contents.optJSONObject(index) ?: return@forEach
            when (content.messageString("type")) {
                "text", "inputText" -> MarkdownContent(content.messageString("text"), onOpenLink = onOpenLink)
                "image", "inputImage" -> {
                    val data = content.messageString("data")
                    val source=if(data.isNotEmpty()) data else content.messageString("url")
                    ImageAttachment(source,item.messageIdentity()+":tool:$index","工具图像",imageLoader,onOpenImage,
                        allowRawBase64=data.isNotEmpty(),mimeType=content.messageString("mimeType").ifBlank { null })
                }
                "resource_link", "resourceLink" -> TextButton(onClick = { onOpenLink(content.messageString("uri")) }) { Text(content.messageString("name").ifBlank { content.messageString("uri") }) }
                else -> OutputContent(content.messageValue(), onCopy)
            }
        } else if (result != null) OutputContent(result.messageValue(), onCopy)
        if (item.has("error") && !item.isNull("error")) Text(item.opt("error").messageValue(), color = Color(0xFFB42318), fontSize = 13.sp)
    }
}

@Composable
private fun ExpandableItem(title: String, status: String = "", content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == "inProgress" || status == "running") CircularProgressIndicator(Modifier.size(14.dp), color = ContentMuted, strokeWidth = 1.5.dp)
            else ContentIcon(if (status == "failed" || status == "declined") "close" else "check", Modifier.size(14.dp), if (status == "failed") Color(0xFFB42318) else ContentMuted)
            Text(title, color = ContentMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            ContentIcon("chevron", Modifier.size(16.dp).rotate(if (expanded) 90f else 0f))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun OutputContent(output: String, onCopy: (String) -> Unit) {
    val identity=System.identityHashCode(output)
    var showAll by remember(identity,output.length) { mutableStateOf(false) }
    // Bound by characters before splitting: a single base64/log line can be several megabytes.
    val preview=remember(identity,output.length) { output.take(16384) }
    val limited=output.length>preview.length
    val lines=remember(preview) { preview.lines() }
    val visible=if(showAll) preview else lines.take(120).joinToString("\n")
    val partial=limited || (!showAll && lines.size>120)
    Column(Modifier.fillMaxWidth().background(ContentField, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onCopy(visible) }) {
                ContentIcon("copy",Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if(partial) "复制预览" else "复制",fontSize=12.sp,color=ContentMuted)
            }
        }
        SelectionContainer {
            Text(visible, color = ContentInk, fontFamily = FontFamily.Monospace,
                fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
        }
        if (!showAll && lines.size > 120) TextButton(onClick = { showAll = true }) { Text("展开预览 ${lines.size} 行", color = ContentMuted) }
        if(limited) Text("仅预览前 16,384 个字符",Modifier.padding(horizontal=12.dp,vertical=8.dp),fontSize=12.sp,color=ContentMuted)
    }
}

private fun JSONObject.messageString(key: String): String = opt(key) as? String ?: ""
private fun JSONObject.messageIdentity(): String = messageString("id").ifBlank { "item@${System.identityHashCode(this)}" }
private fun JSONArray?.messageStrings(): List<String> = if (this == null) emptyList() else (0 until length()).map { opt(it).messageValue() }
/** Never stringify a whole unknown JSON object: image payloads can contain megabytes of base64. */
private fun Any?.messageValue(): String {
    if(this is String) return take(48000)+(if(length>48000) "\n[长文本预览已截断]" else "")
    val out=StringBuilder()
    fun write(value: Any?,depth: Int) {
        if(out.length>=12000) return
        when(value) {
            null,JSONObject.NULL -> out.append("null")
            is String -> {
                val limited=value.take(2000)
                out.append(JSONObject.quote(limited))
                if(value.length>limited.length) out.append(" [长字段已省略，共 ${value.length} 字符]")
            }
            is JSONObject -> {
                if(depth>=4) {out.append("{…}");return}
                out.append("{\n")
                val keys=value.keys()
                var count=0
                while(keys.hasNext() && count<16 && out.length<12000) {
                    val key=keys.next()
                    if(count>0) out.append(",\n")
                    out.append("  ".repeat(depth+1)).append(JSONObject.quote(key.take(160))).append(": ")
                    write(value.opt(key),depth+1);count++
                }
                if(keys.hasNext()) out.append("\n  …")
                out.append('\n').append("  ".repeat(depth)).append('}')
            }
            is JSONArray -> {
                if(depth>=4) {out.append("[…]");return}
                out.append('[')
                for(index in 0 until minOf(value.length(),16)) {
                    if(out.length>=12000) break
                    if(index>0) out.append(", ")
                    write(value.opt(index),depth+1)
                }
                if(value.length()>16) out.append(", …")
                out.append(']')
            }
            else -> out.append(value.toString().take(500))
        }
    }
    write(this,0)
    return out.toString().take(14000)
}
