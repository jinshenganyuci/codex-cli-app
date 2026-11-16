package com.nanci.codexremote.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime

private data class FormOption(val value: String,val title: String)
private data class FormField(val name: String,val schema: JSONObject,val required: Boolean) {
    val type get()=schema.text("type")
    val title get()=schema.text("title",name)
    val options: List<FormOption> get() {
        val source=if(type=="array") schema.optJSONObject("items") ?: JSONObject() else schema
        val titled=source.optJSONArray("oneOf") ?: source.optJSONArray("anyOf")
        if(titled!=null) return titled.objects().map { FormOption(it.text("const"),it.text("title",it.text("const"))) }
        val values=source.optJSONArray("enum").strings()
        val titles=source.optJSONArray("enumNames").strings()
        return values.mapIndexed { i,v -> FormOption(v,titles.getOrNull(i) ?: v) }
    }
    val supported get()=type in setOf("string","number","integer","boolean") || type=="array" && options.isNotEmpty()
}

/** Standard MCP JSON Schema forms remain typed; unsupported schemas cannot be accepted. */
@Composable
fun ElicitationCard(params: JSONObject, enabled: Boolean = true, onReply: (JSONObject) -> Unit,
    onOpenUrl: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val identity=params.toString()
    val mode=params.text("mode","form")
    val schema=params.optJSONObject("requestedSchema")
    val persistModes=remember(identity) {
        when(val value=params.optJSONObject("_meta")?.opt("persist")) {
            is String -> setOf(value)
            is JSONArray -> value.strings().toSet()
            else -> emptySet()
        }.intersect(setOf("session","always"))
    }
    val fields=remember(identity) {
        val props=schema?.optJSONObject("properties")
        val required=schema?.optJSONArray("required").strings().toSet()
        props?.keys()?.asSequence()?.mapNotNull { name -> props.optJSONObject(name)?.let{FormField(name,it,name in required)} }?.toList().orEmpty()
    }
    val supported=mode=="url" || mode in setOf("form","openai/form","openaiForm") && schema?.text("type")=="object" && fields.all{it.supported}
    val values=remember(identity) {
        mutableStateMapOf<String,Any>().apply {
            fields.forEach { field ->
                val default=field.schema.opt("default")
                this[field.name]=when {
                    field.type=="boolean" -> default as? Boolean ?: false
                    field.type=="array" -> (default as? JSONArray).strings().toSet()
                    default!=null && default!=JSONObject.NULL -> default.toString()
                    else -> ""
                }
            }
        }
    }
    var errors by remember(identity) { mutableStateOf(emptyMap<String,String>()) }
    fun reply(action: String,content: JSONObject? = null,persist: String? = null) {
        val meta=if(action=="accept" && persist in persistModes) JSONObject().put("persist",persist) else JSONObject.NULL
        onReply(JSONObject().put("action",action).put("content",content ?: JSONObject.NULL).put("_meta",meta))
    }
    fun accept(persist: String? = null) {
        if(mode=="url") { reply("accept",persist=persist);return }
        val invalid=fields.mapNotNull { f -> validateField(f,values[f.name])?.let { f.name to it } }.toMap()
        errors=invalid
        if(invalid.isNotEmpty()) return
        val result=JSONObject()
        fields.forEach { f ->
            val value=values[f.name]
            when(f.type) {
                "boolean" -> result.put(f.name,value as? Boolean ?: false)
                "array" -> { val items=(value as? Set<*>)?.filterIsInstance<String>().orEmpty();if(items.isNotEmpty() || f.required) result.put(f.name,JSONArray(items)) }
                "number" -> (value as? String)?.trim()?.toDoubleOrNull()?.let {result.put(f.name,it)}
                "integer" -> (value as? String)?.trim()?.toLongOrNull()?.let {result.put(f.name,it)}
                else -> if((value as? String).orEmpty().isNotEmpty() || f.required) result.put(f.name,value ?: "")
            }
        }
        reply("accept",result,persist)
    }
    ContentCard(modifier) {
        Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.72f).dp).verticalScroll(rememberScrollState())) {
            Text("查看请求",fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium,color=ContentInk)
            val server=params.text("serverName")
            if(server.isNotEmpty()) Text("$server 已请求此表单",Modifier.padding(top=4.dp),fontSize=12.sp,lineHeight=16.sp,color=ContentMuted)
            val message=params.text("message",params.text("description"))
            if(message.isNotBlank()) Text(message,Modifier.padding(top=8.dp),fontSize=14.sp,lineHeight=20.sp,color=ContentInk)
            if(mode=="url") {
                val url=params.text("url")
                Text(url,Modifier.fillMaxWidth().clickable(enabled=enabled&&url.isNotBlank()){onOpenUrl(url)}.padding(vertical=12.dp),
                    fontSize=14.sp,lineHeight=20.sp,color=ContentBlue)
                Text("请在打开的页面完成操作后继续。",fontSize=12.sp,lineHeight=16.sp,color=ContentMuted)
            } else if(supported) {
                fields.forEachIndexed { index,field ->
                    if(index>0) HorizontalDivider(Modifier.padding(vertical=8.dp),color=ContentBorder) else Spacer(Modifier.height(12.dp))
                    val label=field.title+if(field.required) " *" else ""
                    if(field.type!="boolean") Text(label,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,color=ContentInk)
                    val description=field.schema.text("description")
                    if(description.isNotBlank()) Text(description,Modifier.padding(top=2.dp),fontSize=12.sp,lineHeight=16.sp,color=ContentMuted)
                    when {
                        field.type=="boolean" -> Row(Modifier.fillMaxWidth().clickable(enabled=enabled){values[field.name]=values[field.name]!=true},verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=values[field.name]==true,onCheckedChange={values[field.name]=it},enabled=enabled,
                                colors=CheckboxDefaults.colors(checkedColor=ContentInk,uncheckedColor=ContentMuted))
                            Text(label,fontSize=14.sp,lineHeight=20.sp,color=ContentInk)
                        }
                        field.options.isNotEmpty() -> field.options.forEach { option ->
                            val multiple=field.type=="array"
                            val selected=if(multiple) (values[field.name] as? Set<*>)?.contains(option.value)==true else values[field.name]==option.value
                            OptionRow(option.title,selected=selected,enabled=enabled) {
                                if(multiple) {
                                    val set=(values[field.name] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: mutableSetOf()
                                    if(!set.add(option.value)) set.remove(option.value)
                                    values[field.name]=set.toSet()
                                } else values[field.name]=option.value
                            }
                        }
                        else -> RequestField((values[field.name] as? String).orEmpty(),{values[field.name]=it},enabled,label="",placeholder="请输入",
                            secret=field.schema.text("format")=="password",number=field.type=="number"||field.type=="integer",
                            modifier=Modifier.padding(top=6.dp))
                    }
                    errors[field.name]?.let {Text(it,Modifier.padding(top=4.dp),color=Color(0xFFBD2424),fontSize=12.sp,lineHeight=16.sp)}
                }
            } else {
                Text("当前版本的 Codex 暂时无法显示此请求。",Modifier.padding(vertical=12.dp),fontSize=14.sp,lineHeight=20.sp,color=ContentMuted)
                Text("模式：$mode",fontSize=12.sp,lineHeight=16.sp,color=ContentMuted)
            }
            if(supported && persistModes.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(top=12.dp,bottom=4.dp),color=ContentBorder)
                for(persist in listOf("session","always").filter{it in persistModes}) {
                    Row(Modifier.fillMaxWidth().clickable(enabled=enabled){accept(persist)}.padding(vertical=8.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        ContentIcon("check",Modifier.size(16.dp))
                        Column {
                            Text(if(persist=="session") "在本次会话中允许" else "始终允许",fontSize=14.sp,lineHeight=20.sp,color=ContentInk)
                            Text(if(persist=="session") "在本次会话中记住此选择" else "记住此选择，用于后续请求",fontSize=12.sp,lineHeight=16.sp,color=ContentMuted)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
                PillButton("关闭",enabled,outline=true) {reply("cancel")}
                PillButton("跳过",enabled,outline=true) {reply("decline")}
                PillButton(if(mode=="url") "已完成" else "提交",enabled&&supported) {accept()}
            }
        }
    }
}

private fun validateField(field: FormField,value: Any?): String? {
    if(field.type=="boolean") return null
    if(field.type=="array") {
        val selected=(value as? Set<*>)?.filterIsInstance<String>().orEmpty()
        if(field.required && selected.isEmpty()) return "请填写此字段后再继续"
        if(selected.any { v -> field.options.none { it.value==v } }) return "请选择有效的选项"
        if(selected.size<field.schema.optInt("minItems",0)) return "请至少选择 ${field.schema.optInt("minItems")} 项"
        if(selected.size>field.schema.optInt("maxItems",Int.MAX_VALUE)) return "最多选择 ${field.schema.optInt("maxItems")} 项"
        return null
    }
    val str=(value as? String).orEmpty()
    if(str.isBlank()) return if(field.required) "请填写此字段后再继续" else null
    if(field.options.isNotEmpty() && field.options.none{it.value==str}) return "请选择有效的选项"
    if(field.type=="integer" || field.type=="number") {
        if(field.type=="integer" && str.toLongOrNull()==null) return "请输入整数"
        val number=str.toDoubleOrNull()?.takeIf{it.isFinite()} ?: return "请输入有效数字"
        if(field.schema.has("minimum") && number<field.schema.optDouble("minimum")) return "数值不能小于 ${field.schema.opt("minimum")}"
        if(field.schema.has("maximum") && number>field.schema.optDouble("maximum")) return "数值不能大于 ${field.schema.opt("maximum")}"
    } else {
        if(str.length<field.schema.optInt("minLength",0)) return "请至少输入 ${field.schema.optInt("minLength")} 个字符"
        if(str.length>field.schema.optInt("maxLength",Int.MAX_VALUE)) return "最多输入 ${field.schema.optInt("maxLength")} 个字符"
        val pattern=field.schema.text("pattern")
        if(pattern.isNotEmpty() && runCatching{Regex(pattern).containsMatchIn(str)}.getOrDefault(false).not()) return "内容不符合要求的格式"
        val valid=when(field.schema.text("format")) {
            "email" -> android.util.Patterns.EMAIL_ADDRESS.matcher(str).matches()
            "uri","url" -> runCatching { URI(str).isAbsolute }.getOrDefault(false)
            "date" -> runCatching { LocalDate.parse(str);true }.getOrDefault(false)
            "date-time" -> runCatching { OffsetDateTime.parse(str);true }.getOrDefault(false)
            else -> true
        }
        if(!valid) return "请输入有效的 ${field.schema.text("format")} 格式"
    }
    return null
}
