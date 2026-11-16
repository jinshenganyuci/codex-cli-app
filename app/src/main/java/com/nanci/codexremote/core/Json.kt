package com.nanci.codexremote.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

fun jsonObject(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

fun jsonArray(values: Iterable<Any?>): JSONArray = JSONArray().apply {
    values.forEach { put(it ?: JSONObject.NULL) }
}

fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONObject.booleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

/** Copy mutable JSON containers without serializing or reallocating large immutable image strings. */
fun JSONObject.copyJson(excluding: Set<String> = emptySet()): JSONObject = JSONObject().also { copy ->
    keys().forEach { key -> if (key !in excluding) copy.put(key, copyJsonValue(opt(key))) }
}

private fun copyJsonValue(value: Any?): Any? = when (value) {
    is JSONObject -> value.copyJson()
    is JSONArray -> JSONArray().also { copy ->
        for (index in 0 until value.length()) copy.put(copyJsonValue(value.opt(index)))
    }
    else -> value // JSON String, Number, Boolean and NULL leaves are immutable.
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { i ->
    opt(i)?.takeUnless { it == JSONObject.NULL }?.toString()
}

internal fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject = apply {
    if (value != null) put(key, value)
}

object UserInputs {
    private const val FILE_START = "<codex_mobile_file_attachment>\n"
    private const val FILE_END = "\n</codex_mobile_file_attachment>"
    private const val FILE_DESCRIPTION = "This user-selected file is already uploaded on the connected server. Read the exact absolute_path to access it; the filename alone is not a current-directory path."
    fun text(text: String): JSONObject = jsonObject("type" to "text", "text" to text, "text_elements" to JSONArray())
    fun remoteImage(absolutePath: String): JSONObject {
        require(absolutePath.startsWith('/'))
        return jsonObject("type" to "localImage", "path" to absolutePath)
    }
    fun imageUrl(url: String): JSONObject = jsonObject("type" to "image", "url" to url)
    fun remoteFile(name: String, absolutePath: String): JSONObject {
        require(absolutePath.startsWith('/'))
        return jsonObject("type" to "mention", "name" to name, "path" to absolutePath)
    }

    /** A mention preserves the attachment UI, but is not sufficient to communicate a file path to the model. */
    internal fun prepareForSend(input: List<JSONObject>): List<JSONObject> {
        val result = input.map { it.copyJson() }.toMutableList()
        input.filter { it.optString("type") == "mention" && it.stringOrNull("path")?.startsWith('/') == true }
            .forEach { mention ->
                val encoded = attachmentText(mention.optString("name"), mention.optString("path"))
                if (result.none { it.optString("type") == "text" && isAttachmentMetadata(it.optString("text"), listOf(mention)) })
                    result += text(encoded)
            }
        return result
    }

    /** Hide only our exact generated structure when the same message contains its matching mention. */
    internal fun isAttachmentMetadata(text: String, content: List<JSONObject>): Boolean {
        if (!text.startsWith(FILE_START) || !text.endsWith(FILE_END)) return false
        val metadata = runCatching {
            val tokener = JSONTokener(text.substring(FILE_START.length, text.length - FILE_END.length))
            val value = tokener.nextValue() as? JSONObject ?: return false
            if (tokener.nextClean() != '\u0000') return false
            value
        }.getOrNull() ?: return false
        if (metadata.keys().asSequence().toSet() != setOf("version", "name", "absolute_path", "description")) return false
        if ((metadata.opt("version") as? Number)?.toDouble() != 1.0 || metadata.opt("description") != FILE_DESCRIPTION) return false
        val name = metadata.opt("name") as? String ?: return false
        val path = (metadata.opt("absolute_path") as? String)?.takeIf { it.startsWith('/') } ?: return false
        // Compare typed fields, not key order: Android and JVM JSONObject have different map ordering.
        return content.any { it.optString("type") == "mention" && it.stringOrNull("name") == name && it.stringOrNull("path") == path }
    }

    private fun attachmentText(name: String, path: String): String = FILE_START + jsonObject(
        "version" to 1, "name" to name, "absolute_path" to path, "description" to FILE_DESCRIPTION,
    ).toString() + FILE_END
}

internal object ProtocolParser {
    fun thread(raw: JSONObject) = ThreadSummary(
        raw.optString("id"), raw.stringOrNull("name") ?: raw.stringOrNull("preview") ?: "新聊天",
        raw.optString("preview"), raw.optString("cwd"), raw.optLong("updatedAt"),
        raw.optJSONObject("status")?.optString("type") ?: raw.optString("status", "notLoaded"),
        raw.stringOrNull("model"), raw.stringOrNull("modelProvider"),
        raw.booleanOrNull("canAcceptDirectInput"), raw.copyJson(excluding = setOf("turns")),
        raw.optJSONObject("section")?.stringOrNull("id"),
    )
    fun section(raw: JSONObject): ChatSection? {
        val id = raw.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val name = raw.stringOrNull("name") ?: return null
        return ChatSection(id, name)
    }
    fun readState(raw: JSONObject?): ChatReadState? {
        raw ?: return null
        val revision = raw.stringOrNull("revision")?.takeIf { it.isNotBlank() } ?: return null
        if (!raw.has("firstUnread") || (!raw.isNull("firstUnread") && raw.optJSONObject("firstUnread") == null)) return null
        return ChatReadState(!raw.isNull("firstUnread"), revision)
    }
    fun item(raw: JSONObject, turnId: String): ChatItem {
        val type = raw.optString("type")
        val content = raw.optJSONArray("content")?.objects().orEmpty()
        val text = when (type) {
            "userMessage" -> content.mapNotNull {
                when (it.optString("type")) {
                    "text" -> it.optString("text").takeUnless { value -> UserInputs.isAttachmentMetadata(value, content) }
                    "mention" -> "@${it.optString("name")}"
                    else -> null
                }
            }.joinToString("\n")
            "reasoning" -> raw.optJSONArray("summary")?.strings()?.joinToString("\n")
                .takeUnless { it.isNullOrBlank() } ?: raw.optJSONArray("content")?.strings()?.joinToString("\n").orEmpty()
            "commandExecution" -> raw.optString("command")
            "fileChange" -> raw.optJSONArray("changes")?.objects()?.joinToString("\n") { it.optString("path") }.orEmpty()
            "mcpToolCall" -> "${raw.optString("server")}/${raw.optString("tool")}"
            "dynamicToolCall", "collabAgentToolCall" -> raw.optString("tool")
            else -> raw.stringOrNull("text") ?: raw.stringOrNull("review") ?: ""
        }
        return ChatItem(raw.optString("id"), turnId, type, text, raw.optString("status", "completed"), raw.copyJson())
    }
    fun turn(raw: JSONObject) = ChatTurn(
        raw.optString("id"), raw.optString("status", "completed"),
        raw.optJSONArray("items")?.objects()?.map { item(it, raw.optString("id")) }.orEmpty(),
        raw.optJSONObject("error")?.stringOrNull("message"), raw.copyJson(excluding = setOf("items")),
    )
    fun model(raw: JSONObject) = ModelInfo(
        raw.optString("id"), raw.optString("model"), raw.optString("displayName"), raw.optString("description"),
        raw.optJSONArray("supportedReasoningEfforts")?.objects()?.map { it.optString("reasoningEffort") }.orEmpty(),
        raw.stringOrNull("defaultReasoningEffort"), raw.optBoolean("isDefault"),
        raw.optJSONArray("inputModalities")?.strings()?.contains("image") == true, raw.copyJson(),
    )
}
