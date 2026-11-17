package com.nanci.codexremote.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import com.nanci.codexremote.ui.theme.NativeTypography
import com.nanci.codexremote.ui.theme.NativeTheme
import org.json.JSONObject

/** Pure view of a server-owned request. The owner retains the JSON-RPC request id. */
@Composable
fun ToolUserInputCard(params: JSONObject, enabled: Boolean = true, onSubmit: (JSONObject) -> Unit,
    modifier: Modifier = Modifier) {
    val identity=params.toString()
    val questions=remember(identity) { params.optJSONArray("questions").objects() }
    val answers=remember(identity) { mutableStateMapOf<String,String>() }
    val complete=questions.isNotEmpty() && questions.all { !answers[it.text("id")].isNullOrBlank() }
    ContentCard(modifier) {
        Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.72f).dp)
            .verticalScroll(rememberScrollState())) {
            Text("Codex 需要用户输入",style=NativeTypography.body16,fontWeight=FontWeight.Medium,color=ContentInk)
            Spacer(Modifier.height(8.dp))
            questions.forEachIndexed { index,q ->
                val id=q.text("id")
                val options=q.optJSONArray("options").objects()
                val value=answers[id].orEmpty()
                if(index>0) HorizontalDivider(Modifier.padding(vertical=8.dp),color=ContentBorder)
                if(q.text("header").isNotBlank()) Text(q.text("header"),style=NativeTypography.caption12,fontWeight=FontWeight.Medium,color=ContentMuted)
                Text(q.text("question"),Modifier.padding(top=2.dp),style=NativeTypography.body14,color=ContentInk)
                if(options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    options.forEachIndexed { oi,option ->
                        if(oi>0) HorizontalDivider(color=ContentBorder)
                        OptionRow(option.text("label"),option.text("description"),selected=value==option.text("label"),enabled=enabled) {
                            answers[id]=option.text("label")
                        }
                    }
                }
                if(q.optBoolean("isOther",false) || options.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val isOption=options.any { it.text("label")==value }
                    RequestField(if(isOption) "" else value,{answers[id]=it},enabled,secret=q.optBoolean("isSecret",false))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.End) {
                PillButton("提交",enabled&&complete) {
                    val data=JSONObject()
                    questions.forEach { q -> data.put(q.text("id"),JSONObject().put("answers",JSONArray().put(answers[q.text("id")].orEmpty()))) }
                    onSubmit(JSONObject().put("answers",data))
                }
            }
        }
    }
}

/** params describes the current question only; navigation / skip / persistence belong to its owner. */
@Composable
fun AsyncQuestionCard(params: JSONObject, enabled: Boolean = true, onClose: () -> Unit,
    onSkip: () -> Unit, onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    val identity=params.toString()
    val options=remember(identity) { params.optJSONArray("options").strings() }
    var value by remember(identity) { mutableStateOf(params.text("answer",options.firstOrNull().orEmpty())) }
    val index=params.optInt("index",0)+1
    val total=params.optInt("total",1)
    ContentCard(modifier) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("第 $index 个问题（共 $total 个）",Modifier.weight(1f),color=ContentInk,style=NativeTypography.body16,fontWeight=FontWeight.SemiBold)
            ContentIconButton("close","关闭",onClose,enabled)
        }
        Text(params.text("question",params.text("title")),Modifier.padding(top=8.dp),color=ContentInk,style=NativeTypography.body16)
        options.forEach { option -> OptionRow(option,selected=value==option,enabled=enabled) { value=option } }
        OutlinedTextField(value=if(value in options) "" else value,onValueChange={value=it},enabled=enabled,
            modifier=Modifier.fillMaxWidth().padding(top=8.dp),maxLines=4,label={Text("回复",fontSize=16.sp)},
            textStyle=NativeTypography.body16.copy(color=ContentInk),
            colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=ContentInk,unfocusedBorderColor=ContentBorder,
                focusedLabelColor=ContentMuted,unfocusedLabelColor=ContentMuted,cursorColor=ContentInk))
        Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),verticalAlignment=Alignment.CenterVertically) {
            Text("跳过",Modifier.clickable(enabled=enabled,onClick=onSkip).padding(horizontal=12.dp,vertical=10.dp),
                color=ContentInk,fontWeight=FontWeight.SemiBold,fontSize=16.sp)
            PillButton("发送",enabled&&value.isNotBlank()) { onSubmit(value) }
        }
    }
}
