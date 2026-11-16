package com.nanci.codexremote.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlanPreview(text: String, onOpen: (String) -> Unit, onCopy: (String) -> Unit,
    onDownload: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {
    ContentCard(modifier) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ContentIcon("list")
            Text("计划",Modifier.weight(1f),color=ContentMuted,fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium)
            ContentIconButton("down","下载计划",{onDownload?.invoke(text)},enabled=onDownload!=null)
            ContentIconButton("copy","复制计划",{onCopy(text)})
        }
        Box(Modifier.fillMaxWidth().padding(top=16.dp).heightIn(max=200.dp).clip(RoundedCornerShape(1.dp))
            .clickable{onOpen(text)}) {
            MarkdownContent(text.lineSequence().take(8).joinToString("\n"))
        }
    }
}

@Composable
fun PlanDecisionCard(enabled: Boolean = true, onImplement: () -> Unit, onRevise: (String) -> Unit,
    onSkip: () -> Unit, modifier: Modifier = Modifier) {
    var feedback by remember { mutableStateOf("") }
    ContentCard(modifier) {
        Text("要执行此计划吗？",color=ContentInk,fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium)
        Row(Modifier.fillMaxWidth().padding(top=16.dp).defaultMinSize(minHeight=40.dp)
            .clickable(enabled=enabled,onClick=onImplement).padding(vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ContentIcon("check",Modifier.size(16.dp))
            Text("是，执行此计划",fontSize=14.sp,lineHeight=20.sp,color=ContentInk)
        }
        HorizontalDivider(color=ContentBorder)
        FeedbackRow(enabled,feedback,{feedback=it},onSkip,{onRevise(feedback)})
    }
}

@Composable
fun PlanReader(text: String, onClose: () -> Unit, onCopy: (String) -> Unit,
    onDownload: (String) -> Unit, modifier: Modifier = Modifier, onOpenLink: (String) -> Unit = {}) {
    Box(modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp)
            .padding(top=88.dp,bottom=32.dp)) {
            MarkdownContent(text,onOpenLink=onOpenLink)
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFFAFAFA).copy(alpha=.95f)).padding(horizontal=14.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=CircleShape,color=Color.White) { ContentIconButton("close","关闭计划",onClose,modifier=Modifier.size(44.dp)) }
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center) { Text("计划",fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold,color=ContentInk) }
            Surface(shape=RoundedCornerShape(24.dp),color=Color.White) {
                Row { ContentIconButton("down","下载计划",{onDownload(text)},modifier=Modifier.size(44.dp));ContentIconButton("copy","复制计划",{onCopy(text)},modifier=Modifier.size(44.dp)) }
            }
        }
    }
}
