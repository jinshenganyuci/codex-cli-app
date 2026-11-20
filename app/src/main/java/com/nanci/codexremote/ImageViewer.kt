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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nanci.codexremote.ui.content.*

@Composable
fun ImageViewer(source:ImageSource,loader:ImageLoader,onClose:()->Unit,onSave:(String)->Unit) {
    val payloadIdentity=System.identityHashCode(source.value)
    var state by remember(source.cacheKey,payloadIdentity,loader) {mutableStateOf<ImageLoadState>(ImageLoadState.Loading)}
    var retry by remember {mutableIntStateOf(0)}
    var scale by remember(source.cacheKey) {mutableFloatStateOf(1f)}
    var offset by remember(source.cacheKey) {mutableStateOf(Offset.Zero)}
    var viewport by remember {mutableStateOf(IntSize.Zero)}
    DisposableEffect(source.cacheKey,payloadIdentity,loader,retry) {
        state=ImageLoadState.Loading
        val cancel=loader.load(source,2048) {state=it}
        onDispose {cancel()}
    }
    val transform=rememberTransformableState {zoom,pan,_ ->
        scale=(scale*zoom).coerceIn(1f,5f)
        val bitmap=(state as? ImageLoadState.Ready)?.bitmap
        offset=if(bitmap==null)Offset.Zero else boundedImageOffset(bitmap.width,bitmap.height,viewport,scale,offset+pan)
    }
    LaunchedEffect(viewport,state,scale) {
        val bitmap=(state as? ImageLoadState.Ready)?.bitmap
        offset=if(bitmap==null)Offset.Zero else boundedImageOffset(bitmap.width,bitmap.height,viewport,scale,offset)
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onClose){Text("关闭",color=Color.White)}
                Spacer(Modifier.weight(1f))
                if(scale>1f)TextButton(onClick={scale=1f;offset=Offset.Zero}) {Text("重置",color=Color.White)}
                TextButton(onClick={onSave(imageFileName(source,(state as? ImageLoadState.Ready)?.extension))},enabled=state is ImageLoadState.Ready,modifier=Modifier.semantics {contentDescription="保存图片"}) {
                    Text("保存",color=if(state is ImageLoadState.Ready)Color.White else Color.Gray)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged {viewport=it}.clipToBounds(),contentAlignment=Alignment.Center) {
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

internal fun boundedImageOffset(width:Int,height:Int,viewport:IntSize,scale:Float,requested:Offset):Offset {
    if(width<=0 || height<=0 || viewport.width<=0 || viewport.height<=0 || scale<=1f)return Offset.Zero
    val fitted=minOf(viewport.width.toFloat()/width,viewport.height.toFloat()/height)
    val x=((width*fitted*scale-viewport.width)/2).coerceAtLeast(0f)
    val y=((height*fitted*scale-viewport.height)/2).coerceAtLeast(0f)
    return Offset(requested.x.coerceIn(-x,x),requested.y.coerceIn(-y,y))
}

/** Prefer decoder format over an HTTP suffix, which can be absent or disagree with the bytes. */
fun imageFileName(source:ImageSource,detectedExtension:String?=null):String {
    val supported=setOf("png","jpg","jpeg","webp","svg","gif","heic","heif","avif","bmp")
    val path=when(source.kind) {
        ImageSourceKind.RemotePath -> source.value
        ImageSourceKind.HttpUrl -> runCatching {java.net.URI(source.value).rawPath}.getOrNull().orEmpty()
        else -> ""
    }
    val rawName=source.displayName ?: path.substringAfterLast('/')
    val decoded=if(source.kind==ImageSourceKind.HttpUrl) runCatching {
        java.net.URLDecoder.decode(rawName.replace("+","%2B"),"UTF-8")
    }.getOrDefault(rawName) else rawName
    val name=decoded.replace(Regex("[\\p{Cntrl}/\\\\]"),"_").take(160).trim().takeIf {it.isNotEmpty() && it!="." && it!=".."}
    val suffix=name?.substringAfterLast('.',"")?.lowercase()?.takeIf {it in supported}
    val ext=detectedExtension?.lowercase()?.takeIf {it in supported} ?: when {
        source.mimeType=="image/svg+xml" || source.value.startsWith("data:image/svg+xml",true) -> "svg"
        source.mimeType=="image/jpeg" || source.value.startsWith("/9j/") || source.value.startsWith("data:image/jpeg",true) -> "jpg"
        source.mimeType=="image/webp" || source.value.startsWith("UklGR") || source.value.startsWith("data:image/webp",true) -> "webp"
        source.mimeType=="image/gif" || source.value.startsWith("data:image/gif",true) -> "gif"
        else -> suffix ?: "png"
    }
    if(name==null)return "Codex-image.$ext"
    if(suffix==ext || suffix=="jpeg" && ext=="jpg")return name
    return "${if(name.contains('.'))name.substringBeforeLast('.').ifBlank {"Codex-image"} else name}.$ext"
}
