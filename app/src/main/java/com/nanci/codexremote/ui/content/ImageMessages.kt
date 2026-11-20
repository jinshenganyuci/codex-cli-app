package com.nanci.codexremote.ui.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import com.nanci.codexremote.ui.theme.NativeTheme

/** Same image view for sent attachments, generated images and tool images. No decoding happens here. */
@Composable
fun MessageImage(
    source: ImageSource,
    label: String,
    loader: ImageLoader?,
    onOpenImage: (ImageSource) -> Unit,
    modifier: Modifier = Modifier,
    quietLoading: Boolean = false,
    fillCell: Boolean = false,
    onImageSize: ((Int,Int) -> Unit)? = null,
    clampAspectRatio: Boolean = true,
) {
    val payloadIdentity=System.identityHashCode(source.value)
    var retry by remember(source.cacheKey,payloadIdentity) { mutableIntStateOf(0) }
    var state by remember(source.cacheKey,payloadIdentity,loader) { mutableStateOf<ImageLoadState>(ImageLoadState.Loading) }
    DisposableEffect(source.cacheKey,payloadIdentity,source.kind,loader,retry,fillCell) {
        state=ImageLoadState.Loading
        var active=true
        val cancel=loader?.load(source,if(fillCell) 768 else 1024) { if(active) state=it }
        if(loader==null) state=ImageLoadState.Error("图片服务尚未就绪")
        onDispose { active=false;cancel?.invoke() }
    }
    BoxWithConstraints(modifier) {
        // APK native MessageImage defaults: fit inside 280 × 350 dp, extraLarge shape 28 dp.
        val widthLimit=if(fillCell) maxWidth else minOf(maxWidth,280.dp)
        when(val current=state) {
            is ImageLoadState.Ready -> {
                val originalRatio=current.bitmap.width.coerceAtLeast(1).toFloat()/current.bitmap.height.coerceAtLeast(1)
                // kx61 passes g5f1.l mask64 (clamp 0.5..2 and Crop outside it).
                // i3h0 Markdown passes mask12; that caller explicitly opts out.
                val ratio=if(clampAspectRatio) originalRatio.coerceIn(.5f,2f) else originalRatio
                val fittedWidth=minOf(widthLimit,350.dp*ratio)
                LaunchedEffect(source.cacheKey,payloadIdentity,current.bitmap.width,current.bitmap.height) {
                    onImageSize?.invoke(current.bitmap.width,current.bitmap.height)
                }
                val imageSize=if(fillCell) Modifier.fillMaxSize() else Modifier.width(fittedWidth).height(fittedWidth/ratio)
                Image(current.bitmap,label,imageSize.clip(RoundedCornerShape(28.dp)).clickable(onClickLabel = "全屏查看图像") { onOpenImage(source) },
                    contentScale=if(fillCell || ratio!=originalRatio) ContentScale.Crop else ContentScale.Fit)
            }
            else -> Box((if(fillCell) Modifier.fillMaxSize() else Modifier.width(widthLimit).aspectRatio(1f)).clip(RoundedCornerShape(28.dp))
                .then(if(quietLoading && current is ImageLoadState.Loading) Modifier.background(generatedPlaceholderBrush()) else Modifier.background(ContentField))
                .clickable(enabled=current is ImageLoadState.Error) { retry++ }
                .semantics { contentDescription=label },contentAlignment=Alignment.Center) {
                if(!(quietLoading && current is ImageLoadState.Loading)) Column(Modifier.padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(current is ImageLoadState.Loading) {
                        CircularProgressIndicator(Modifier.size(22.dp),color=ContentMuted,strokeWidth=2.dp)
                        Text("正在加载图片",fontSize=12.sp,color=ContentMuted)
                    } else {
                        Text("图片暂时无法显示",fontSize=14.sp,color=ContentInk)
                        Text((current as? ImageLoadState.Error)?.reason.orEmpty().take(100),fontSize=12.sp,color=ContentMuted,maxLines=2)
                        Text("点按重试",fontSize=12.sp,color=ContentMuted)
                    }
                }
            }
        }
    }
}

/** LocalImage uses a server filesystem path, which can be relative to the chat workspace. */
@Composable
internal fun userMessageImageLocation(part: JSONObject, baseDirectory: String?): String {
    val raw = (part.opt("url") as? String).orEmpty().ifBlank { (part.opt("path") as? String).orEmpty() }
    val local = part.optString("type") == "localImage"
    return remember(raw, local, baseDirectory) {
        val base = baseDirectory?.takeIf { it.startsWith('/') && it.none { char -> char in "\u0000\r\n" } }
        when {
            !local -> raw
            raw.any { it in "\u0000\r\n" } -> ""
            base == null || raw.isBlank() || raw.startsWith('/') || "://" in raw || raw.startsWith("data:", true) -> raw
            else -> "${base.trimEnd('/')}/$raw"
        }
    }
}

/** Android native MessageImageListContent: 8 dp gaps; 2/4 images use two columns, others three. */
@Composable
internal fun UserImageGroup(
    parts: List<IndexedValue<JSONObject>>,
    messageId: String,
    loader: ImageLoader?,
    onOpenImage: (ImageSource) -> Unit,
    imageBaseDirectory: String?,
) {
    val decodedRatios=remember(messageId,parts.map { it.index }) { mutableStateMapOf<Int,Float>() }
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.TopEnd) {
        val gap=8.dp
        val columns=if(parts.size==2 || parts.size==4) 2 else 3
        // Native compact/expanded cell caps. On phones the available row width is the tighter bound.
        val cellCap=if(maxWidth<600.dp) 280.dp else 480.dp
        val side=minOf(((maxWidth-gap*(columns-1))/columns).coerceAtLeast(1.dp),cellCap)
        val twoRatios=if(parts.size==2) parts.map { part ->
            decodedRatios[part.index] ?: part.value.let {
                val width=it.optDouble("width",0.0);val height=it.optDouble("height",0.0)
                if(width>0 && height>0 && width.isFinite() && height.isFinite())
                    (width/height).toFloat().takeIf { ratio -> ratio.isFinite() && ratio>0f } ?: 1f
                else 1f
            }
        } else emptyList()
        val twoWidths=if(parts.size==2) twoRatios.map { ratio ->
            minOf((maxWidth-gap).coerceAtLeast(1.dp)*(ratio/twoRatios.sum()),cellCap).coerceAtLeast(1.dp)
        } else emptyList()
        val twoHeight=if(parts.size==2) minOf(twoWidths[0]/twoRatios[0],twoWidths[1]/twoRatios[1]) else side
        Column(verticalArrangement=Arrangement.spacedBy(gap),horizontalAlignment=Alignment.End) {
            parts.chunked(columns).forEachIndexed { rowIndex,row ->
                Row(horizontalArrangement=Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { columnIndex,part ->
                        val width=if(parts.size==2) twoWidths[columnIndex] else side
                        val height=if(parts.size==2) twoHeight else side
                        key(part.index) {
                            val raw=userMessageImageLocation(part.value,imageBaseDirectory)
                            val source=messageImageSource(raw,"$messageId:user:${part.index}")
                            val label="图片 ${rowIndex*columns+columnIndex+1}，共 ${parts.size} 张"
                            if(source!=null) MessageImage(source,label,loader,onOpenImage,Modifier.width(width).height(height),
                                fillCell=true,onImageSize={ w,h ->
                                    if(parts.size==2 && w>0 && h>0) {
                                        val ratio=w.toFloat()/h
                                        if(decodedRatios[part.index]!=ratio) decodedRatios[part.index]=ratio
                                    }
                                })
                            else Box(Modifier.width(width).height(height).clip(RoundedCornerShape(28.dp)).background(ContentField)
                                .semantics { contentDescription=label },contentAlignment=Alignment.Center) {
                                Text("图片内容不可用",fontSize=12.sp,color=ContentMuted,modifier=Modifier.padding(12.dp))
                            }
                        }
                    }
                    // Native incomplete rows retain their column positions instead of centering the tail.
                    repeat(columns-row.size) { Spacer(Modifier.size(side)) }
                }
            }
        }
    }
}

@Composable
internal fun ImageAttachment(raw: String,key: String,label: String,loader: ImageLoader?,onOpenImage: (ImageSource) -> Unit,
    allowRawBase64: Boolean = false,mimeType: String? = null) {
    val source=messageImageSource(raw,key,allowRawBase64,mimeType)
    if(source!=null) MessageImage(source,label,loader,onOpenImage)
    else Text("图片内容不可用",fontSize=12.sp,color=ContentMuted,modifier=Modifier.padding(12.dp))
}

@Composable
internal fun GeneratedImageContent(item: JSONObject,loader: ImageLoader?,onOpenImage: (ImageSource) -> Unit,
    onCopyImage: ((ImageSource) -> Unit)? = null) {
    val id=(item.opt("id") as? String).orEmpty().ifBlank { "generated@${System.identityHashCode(item)}" }
    val status=(item.opt("status") as? String).orEmpty()
    val result=(item.opt("result") as? String).orEmpty()
    val savedPath=(item.opt("savedPath") as? String).orEmpty()
    val url=(item.opt("url") as? String).orEmpty()
    val source=when {
        result.isNotEmpty() -> messageImageSource(result,"$id:generated:$status",allowRawBase64=true)
        savedPath.isNotEmpty() -> messageImageSource(savedPath,"$id:saved")
        url.isNotEmpty() -> messageImageSource(url,"$id:url")
        else -> null
    }
    val generating=status in setOf("inProgress","running","generating")
    Column(horizontalAlignment=Alignment.Start,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(source!=null) MessageImage(source,"生成的图片",loader,onOpenImage,quietLoading=true)
        else if(generating) GeneratedImagePlaceholder()
        val failure=item.optJSONObject("failure")
        when {
            failure!=null || status in setOf("failed","error","cancelled") -> Text(
                if(failure?.optString("type")=="usageLimitExceeded") "图片生成额度暂时已用完" else "图片生成未完成",
                fontSize=12.sp,color=ContentMuted)
            generating -> Unit
            source==null -> Text("服务器未提供可读取的图片内容",fontSize=12.sp,color=ContentMuted,modifier=Modifier.padding(vertical=12.dp))
        }
        if(source!=null && !generating && failure==null && onCopyImage!=null) {
            ContentIconButton("copy","复制图片",{onCopyImage(source)},modifier=Modifier.padding(top=2.dp))
        }
    }
}

@Composable
private fun GeneratedImagePlaceholder() {
    BoxWithConstraints {
        Box(Modifier.width(minOf(maxWidth,280.dp)).aspectRatio(1f).clip(RoundedCornerShape(28.dp))
            .background(generatedPlaceholderBrush()).semantics { contentDescription="正在生成图片" })
    }
}

@Composable
private fun generatedPlaceholderBrush(): Brush {
    val colors=NativeTheme.colors
    return Brush.horizontalGradient(listOf(colors.field,colors.field,colors.border))
}
