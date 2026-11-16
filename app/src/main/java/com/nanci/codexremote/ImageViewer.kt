package com.nanci.codexremote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nanci.codexremote.ui.content.*

@Composable
fun ImageViewer(source:ImageSource,loader:ImageLoader,onClose:()->Unit,onSave:()->Unit) {
    val payloadIdentity=System.identityHashCode(source.value)
    var state by remember(source.cacheKey,payloadIdentity,loader) {mutableStateOf<ImageLoadState>(ImageLoadState.Loading)}
    var retry by remember {mutableIntStateOf(0)}
    var scale by remember(source.cacheKey) {mutableFloatStateOf(1f)}
    var offset by remember(source.cacheKey) {mutableStateOf(Offset.Zero)}
    DisposableEffect(source.cacheKey,payloadIdentity,loader,retry) {
        state=ImageLoadState.Loading
        val cancel=loader.load(source,2048) {state=it}
        onDispose {cancel()}
    }
    val transform=rememberTransformableState {zoom,pan,_ ->
        scale=(scale*zoom).coerceIn(1f,5f)
        offset=if(scale<=1f)Offset.Zero else offset+pan
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onClose){Text("关闭",color=Color.White)}
                Spacer(Modifier.weight(1f))
                if(scale>1f)TextButton(onClick={scale=1f;offset=Offset.Zero}) {Text("重置",color=Color.White)}
                TextButton(onClick=onSave,enabled=state is ImageLoadState.Ready,modifier=Modifier.semantics {contentDescription="保存图片"}) {
                    Text("保存",color=if(state is ImageLoadState.Ready)Color.White else Color.Gray)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(),contentAlignment=Alignment.Center) {
                when(val current=state) {
                    is ImageLoadState.Ready -> Image(current.bitmap,"图片预览",Modifier.fillMaxSize()
                        .transformable(transform).graphicsLayer(scaleX=scale,scaleY=scale,translationX=offset.x,translationY=offset.y),contentScale=ContentScale.Fit)
                    ImageLoadState.Loading -> CircularProgressIndicator(color=Color.White,strokeWidth=2.dp)
                    is ImageLoadState.Error -> Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(current.reason,color=Color.White,fontSize=14.sp,modifier=Modifier.padding(horizontal=24.dp))
                        TextButton(onClick={retry++}) {Text("重试",color=Color.White)}
                    }
                }
            }
        }
    }
}

fun imageFileName(source:ImageSource):String {
    if(source.kind==ImageSourceKind.RemotePath)source.value.substringAfterLast('/').takeIf {it.contains('.') && it.length<180}?.let {return it}
    val ext=when {
        source.mimeType=="image/jpeg" || source.value.startsWith("/9j/") || source.value.startsWith("data:image/jpeg",true) -> "jpg"
        source.mimeType=="image/webp" || source.value.startsWith("UklGR") || source.value.startsWith("data:image/webp",true) -> "webp"
        else -> "png"
    }
    return "Codex-image.$ext"
}
