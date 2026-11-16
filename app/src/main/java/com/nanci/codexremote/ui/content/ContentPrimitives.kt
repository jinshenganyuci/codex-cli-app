package com.nanci.codexremote.ui.content

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

internal val ContentInk = Color(0xFF0D0D0D)
internal val ContentMuted = Color(0xFF5D5D5D)
internal val ContentField = Color(0xFFF3F3F3)
internal val ContentBlue = Color(0xFF3566F0)
internal val ContentBorder = Color(0xFFE8E8E8)

internal fun JSONObject.text(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)
internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a ->
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }
} ?: emptyList()
internal fun JSONArray?.strings(): List<String> = this?.let { a ->
    (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.opt(it)?.toString() }
} ?: emptyList()

@Composable
internal fun ContentIcon(name: String, modifier: Modifier = Modifier, color: Color = ContentMuted) {
    Canvas(modifier.size(24.dp)) {
        val s = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x: Float,y: Float,x2: Float,y2: Float) = drawLine(color,p(x,y),p(x2,y2),1.8f*s,StrokeCap.Round)
        when(name) {
            "copy" -> {
                drawRoundRect(color,p(8f,3f),Size(12f*s,14f*s),CornerRadius(2.5f*s),style=Stroke(1.8f*s))
                drawRoundRect(Color.White,p(3f,8f),Size(12f*s,14f*s),CornerRadius(2.5f*s))
                drawRoundRect(color,p(3f,8f),Size(12f*s,14f*s),CornerRadius(2.5f*s),style=Stroke(1.8f*s))
            }
            "down" -> { line(12f,3f,12f,15f);line(7f,10f,12f,15f);line(17f,10f,12f,15f);line(4f,21f,20f,21f) }
            "close" -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
            "check" -> { line(5f,12f,10f,17f);line(10f,17f,19f,6f) }
            "chevron" -> { line(9f,6f,15f,12f);line(15f,12f,9f,18f) }
            "pencil" -> {
                val path=Path().apply { moveTo(4*s,16*s);lineTo(15*s,5*s);lineTo(19*s,9*s);lineTo(8*s,20*s);lineTo(3*s,21*s);close() }
                drawPath(path,color,style=Stroke(1.7f*s)); line(13f,7f,17f,11f)
            }
            else -> { for(y in listOf(6f,12f,18f)) { line(9f,y,21f,y);line(3f,y,4f,y) } }
        }
    }
}

@Composable
internal fun ContentIconButton(name: String, label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).clickable(enabled=enabled,onClick=onClick)
        .semantics { contentDescription=label }, contentAlignment=Alignment.Center) {
        ContentIcon(name,color=if(enabled) ContentInk else ContentMuted.copy(alpha=.35f))
    }
}

@Composable
internal fun ContentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape=RoundedCornerShape(16.dp)
    // The native reference uses a broad, 5% black shadow, not Material elevation's dark contact edge.
    Surface(modifier.fillMaxWidth().shadow(20.dp,shape,clip=false,
        ambientColor=Color.Black.copy(alpha=.05f),spotColor=Color.Black.copy(alpha=.05f)),shape=shape,color=Color.White,
        shadowElevation=0.dp, tonalElevation=0.dp) {
        Column(Modifier.padding(16.dp),content=content)
    }
}

@Composable
internal fun PillButton(label: String, enabled: Boolean = true, outline: Boolean = false, onClick: () -> Unit) {
    val shape=RoundedCornerShape(100)
    val bg=if(outline) Color.White else if(enabled) ContentInk else ContentField
    Box(Modifier.defaultMinSize(minHeight=40.dp).clip(shape).background(bg)
        .then(if(outline) Modifier.border(1.dp,ContentBorder,shape) else Modifier)
        .clickable(enabled=enabled,onClick=onClick).padding(horizontal=16.dp,vertical=9.dp),contentAlignment=Alignment.Center) {
        Text(label,color=if(!enabled) Color(0xFF9B9B9B) else if(outline) ContentInk else Color.White,
            fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold)
    }
}

@Composable
internal fun OptionRow(label: String, description: String = "", selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight=40.dp).clickable(enabled=enabled,onClick=onClick)
        .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if(selected) ContentBlue else Color.Transparent)
            .then(if(!selected) Modifier.border(1.4.dp,ContentMuted,CircleShape) else Modifier),contentAlignment=Alignment.Center) {
            if(selected) ContentIcon("check",Modifier.size(12.dp),Color.White)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,color=ContentInk,fontSize=14.sp,lineHeight=20.sp)
            if(description.isNotBlank()) Text(description,color=ContentMuted,fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RequestField(value: String, onValueChange: (String) -> Unit, enabled: Boolean = true,
    label: String = "回答", placeholder: String = "请输入回复", secret: Boolean = false,
    number: Boolean = false, maxLines: Int = 4, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(label.isNotEmpty()) Text(label,color=ContentMuted,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium)
        BasicTextField(value,onValueChange,enabled=enabled,modifier=Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(ContentField).padding(horizontal=10.dp,vertical=9.dp),
            textStyle=TextStyle(color=ContentInk,fontSize=14.sp,lineHeight=20.sp),cursorBrush=SolidColor(ContentInk),
            maxLines=maxLines,visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions=KeyboardOptions(keyboardType=if(secret) KeyboardType.Password else if(number) KeyboardType.Decimal else KeyboardType.Text),
            decorationBox={ inner -> Box { if(value.isEmpty()) Text(placeholder,color=Color(0xFF9B9B9B),fontSize=14.sp,lineHeight=20.sp);inner() } })
    }
}

@Composable
internal fun FeedbackRow(enabled: Boolean, value: String, onValue: (String) -> Unit, onSkip: () -> Unit, onSubmit: () -> Unit,
    hint: String = "告诉 Codex 需要如何修改") {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight=40.dp).padding(top=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ContentIcon("pencil",Modifier.size(12.dp))
        BasicTextField(value,onValue,enabled=enabled,modifier=Modifier.weight(1f),maxLines=4,
            textStyle=TextStyle(fontSize=14.sp,lineHeight=20.sp,color=ContentInk),cursorBrush=SolidColor(ContentInk),
            decorationBox={ inner -> Box { if(value.isEmpty()) Text(hint,color=ContentMuted,fontSize=14.sp,lineHeight=20.sp);inner() } })
        PillButton(if(value.isBlank()) "跳过" else "提交",enabled,outline=value.isBlank(),onClick={ if(value.isBlank()) onSkip() else onSubmit() })
    }
}
