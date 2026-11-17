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
import androidx.compose.ui.res.painterResource
import com.nanci.codexremote.R
import androidx.compose.ui.draw.clip
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
import com.nanci.codexremote.ui.theme.NativeTheme
import com.nanci.codexremote.ui.theme.NativeTypography
import com.nanci.codexremote.ui.theme.nativeShadow

internal val ContentInk: Color @Composable get() = NativeTheme.colors.ink
internal val ContentMuted: Color @Composable get() = NativeTheme.colors.muted
internal val ContentField: Color @Composable get() = NativeTheme.colors.field
internal val ContentBlue: Color @Composable get() = NativeTheme.colors.accent
internal val ContentBorder: Color @Composable get() = NativeTheme.colors.border

internal fun JSONObject.text(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)
internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a ->
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }
} ?: emptyList()
internal fun JSONArray?.strings(): List<String> = this?.let { a ->
    (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.opt(it)?.toString() }
} ?: emptyList()

/** DS vector resources are shared with the shell; page-to-icon mapping remains explicit. */
@Composable
internal fun ContentIcon(name: String, modifier: Modifier = Modifier, color: Color = ContentMuted) {
    val glyph = when(name) {
        "close" -> "x"
        "chevron" -> "right"
        "list" -> "plan"
        else -> name
    }
    com.nanci.codexremote.ui.shell.ShellGlyph(glyph,modifier.size(24.dp),color)
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
    // Native cbz0.c uses a blur radius, not elevation.
    Surface(modifier.fillMaxWidth().nativeShadow(shape,20.dp,Color.Black.copy(alpha=.05f)),shape=shape,color=NativeTheme.colors.surface,
        shadowElevation=0.dp, tonalElevation=0.dp) {
        Column(Modifier.padding(16.dp),content=content)
    }
}

@Composable
internal fun PillButton(label: String, enabled: Boolean = true, outline: Boolean = false, onClick: () -> Unit) {
    val shape=RoundedCornerShape(100)
    val bg=if(outline) NativeTheme.colors.surface else if(enabled) ContentInk else ContentField
    Box(Modifier.defaultMinSize(minHeight=40.dp).clip(shape).background(bg)
        .then(if(outline) Modifier.border(1.dp,ContentBorder,shape) else Modifier)
        .clickable(enabled=enabled,onClick=onClick).padding(horizontal=16.dp,vertical=9.dp),contentAlignment=Alignment.Center) {
        Text(label,color=if(!enabled) NativeTheme.colors.tertiary else if(outline) ContentInk else NativeTheme.colors.surface,
            style=NativeTypography.body14Medium)
    }
}

@Composable
internal fun OptionRow(label: String, description: String = "", selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight=40.dp).clickable(enabled=enabled,onClick=onClick)
        .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        // Native ot70 -> g500.t -> tmx(11): a tinted vector with a transparent check cutout.
        if(selected) Icon(painterResource(R.drawable.ic_native_circle_checkmark_fill),null,Modifier.size(16.dp),tint=ContentBlue)
        else ContentIcon("pending",Modifier.size(16.dp),ContentMuted)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,color=ContentInk,style=NativeTypography.body14)
            if(description.isNotBlank()) Text(description,color=ContentMuted,style=NativeTypography.caption12,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RequestField(value: String, onValueChange: (String) -> Unit, enabled: Boolean = true,
    label: String = "回答", placeholder: String = "请输入回复", secret: Boolean = false,
    number: Boolean = false, maxLines: Int = 4, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(label.isNotEmpty()) Text(label,color=ContentMuted,style=NativeTypography.caption12Medium)
        BasicTextField(value,onValueChange,enabled=enabled,modifier=Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(ContentField).padding(horizontal=10.dp,vertical=9.dp),
            textStyle=NativeTypography.body14.copy(color=ContentInk),cursorBrush=SolidColor(ContentInk),
            maxLines=maxLines,visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions=KeyboardOptions(keyboardType=if(secret) KeyboardType.Password else if(number) KeyboardType.Decimal else KeyboardType.Text),
            decorationBox={ inner -> Box { if(value.isEmpty()) Text(placeholder,color=NativeTheme.colors.tertiary,style=NativeTypography.body14);inner() } })
    }
}

@Composable
internal fun FeedbackRow(enabled: Boolean, value: String, onValue: (String) -> Unit, onSkip: () -> Unit, onSubmit: () -> Unit,
    hint: String = "告诉 Codex 需要如何修改", maxLines: Int = 3) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight=40.dp).padding(top=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ContentIcon("pencil",Modifier.size(12.dp))
        BasicTextField(value,onValue,enabled=enabled,modifier=Modifier.weight(1f),maxLines=maxLines,
            textStyle=NativeTypography.body14.copy(color=ContentInk),cursorBrush=SolidColor(ContentInk),
            decorationBox={ inner -> Box { if(value.isEmpty()) Text(hint,color=ContentMuted,style=NativeTypography.body14);inner() } })
        PillButton(if(value.isBlank()) "跳过" else "提交",enabled,outline=value.isBlank(),onClick={ if(value.isBlank()) onSkip() else onSubmit() })
    }
}
