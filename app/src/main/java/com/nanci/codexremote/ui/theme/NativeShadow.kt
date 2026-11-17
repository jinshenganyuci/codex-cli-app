package com.nanci.codexremote.ui.theme

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Radius-based, zero-spread drop shadow. Unlike Modifier.shadow, radius is blur,
 * not elevation. The reference APK's cbz0/s900/waz0 route supplies these semantics.
 * A cached software alpha mask works on API 26+ and is then composed by the GPU.
 * Very large masks are sampled down (resource bound, not an APK design token).
 */
fun Modifier.nativeShadow(
    shape: Shape,
    radius: Dp,
    color: Color = Color.Black.copy(alpha = .05f),
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp,
): Modifier = drawWithCache {
    val blur = radius.toPx().coerceAtLeast(0f)
    val dx = offsetX.toPx()
    val dy = offsetY.toPx()
    if (size.width <= 0f || size.height <= 0f || color.alpha <= 0f) {
        onDrawWithContent { drawContent() }
    } else {
        val extent = blur * 2f + 2f
        val totalWidth = size.width + extent * 2f
        val totalHeight = size.height + extent * 2f
        val sample = min(1f, min(2048f / maxOf(totalWidth, totalHeight),
            sqrt(2_000_000f / (totalWidth * totalHeight))))
        val padding = ceil(extent * sample).toFloat()
        val width = ceil(size.width * sample + padding * 2).toInt().coerceAtLeast(1)
        val height = ceil(size.height * sample + padding * 2).toInt().coerceAtLeast(1)
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val path = when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rectangle -> Path().apply {
                addRect(outline.rect.left, outline.rect.top, outline.rect.right, outline.rect.bottom, Path.Direction.CW)
            }
            is Outline.Rounded -> Path().apply {
                val r = outline.roundRect
                addRoundRect(RectF(r.left, r.top, r.right, r.bottom), floatArrayOf(
                    r.topLeftCornerRadius.x, r.topLeftCornerRadius.y,
                    r.topRightCornerRadius.x, r.topRightCornerRadius.y,
                    r.bottomRightCornerRadius.x, r.bottomRightCornerRadius.y,
                    r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y,
                ), Path.Direction.CW)
            }
            is Outline.Generic -> Path(outline.path.asAndroidPath())
        }
        path.transform(Matrix().apply { setScale(sample, sample); postTranslate(padding, padding) })
        Canvas(mask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            if (blur > 0f) maskFilter = BlurMaskFilter(blur * sample, BlurMaskFilter.Blur.NORMAL)
        })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.color = color.toArgb() }
        val destination = RectF(dx - padding / sample, dy - padding / sample,
            dx + (width - padding) / sample, dy + (height - padding) / sample)
        onDrawWithContent {
            drawIntoCanvas { it.nativeCanvas.drawBitmap(mask, null, destination, paint) }
            drawContent()
        }
    }
}
