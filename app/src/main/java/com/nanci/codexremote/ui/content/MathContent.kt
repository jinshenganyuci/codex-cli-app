package com.nanci.codexremote.ui.content

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.RaTeXException
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class FormulaKey(val latex: String, val color: Int, val displayMode: Boolean)
private val formulaCache = object : LruCache<FormulaKey, Result<DisplayList>>(4096) {
    override fun sizeOf(key: FormulaKey, value: Result<DisplayList>): Int =
        (value.getOrNull()?.items?.size ?: 0).coerceAtLeast(1)
}

@Composable
internal fun formulaLayouts(expressions: Set<String>, color: Color, displayMode: Boolean): Map<String, DisplayList> {
    val context = LocalContext.current.applicationContext
    var layouts by remember(color, displayMode) { mutableStateOf(emptyMap<String, DisplayList>()) }
    LaunchedEffect(expressions, color, displayMode) {
        if (expressions.isEmpty()) return@LaunchedEffect
        layouts = withContext(Dispatchers.Default) {
            RaTeXFontLoader.ensureLoaded(context)
            buildMap {
                for (latex in expressions) {
                    val key = FormulaKey(latex, color.toArgb(), displayMode)
                    val result = formulaCache.get(key) ?: try {
                        Result.success(RaTeXEngine.parseBlocking(latex, displayMode, key.color))
                    } catch (error: RaTeXException) {
                        Result.failure(error)
                    } catch (error: LinkageError) {
                        Result.failure(error)
                    }.also { formulaCache.put(key, it) }
                    val layout = result.getOrNull()
                    if (layout != null && layout.items.isNotEmpty() && layout.width.isFinite() && layout.width >= 0 &&
                        (layout.height + layout.depth).let { it.isFinite() && it >= 0 }) put(latex, layout)
                }
            }
        }
    }
    return layouts
}

@Composable
internal fun MathBlock(latex: String, source: String) {
    val color = ContentInk
    val layouts = formulaLayouts(remember(latex) { setOf(latex) }, color, displayMode = true)
    val layout = layouts[latex]
    if (layout == null) {
        Text(source, color = color, fontSize = 16.sp, lineHeight = 24.sp)
    } else {
        val fontSize = with(LocalDensity.current) { 18.sp.toPx() }
        val renderer = remember(layout, fontSize) { mathRenderer(layout, fontSize) }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            FormulaCanvas(latex, renderer)
        }
    }
}

@Composable
internal fun inlineFormulas(
    layouts: Map<String, DisplayList>, fontSizePx: Float, maxWidthPx: Float,
): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    return remember(layouts, fontSizePx, maxWidthPx, density) {
        layouts.mapValues { (latex, layout) ->
            val size = minOf(fontSizePx, ((maxWidthPx - 4).coerceAtLeast(1f) / layout.width).toFloat())
            val renderer = mathRenderer(layout, size)
            val placeholder = with(density) {
                Placeholder(formulaWidth(renderer).toSp(), ceil(renderer.totalHeightPx).toSp(), PlaceholderVerticalAlign.TextCenter)
            }
            InlineTextContent(placeholder) { FormulaCanvas(latex, renderer) }
        }
    }
}

private fun mathRenderer(layout: DisplayList, fontSize: Float) =
    RaTeXRenderer(layout, fontSize, RaTeXFontLoader::getTypeface)

private fun formulaWidth(renderer: RaTeXRenderer): Float = ceil(renderer.widthPx) + 4f

@Composable
private fun FormulaCanvas(latex: String, renderer: RaTeXRenderer) {
    val density = LocalDensity.current
    val width = with(density) { formulaWidth(renderer).toDp() }
    val height = with(density) { ceil(renderer.totalHeightPx).toDp() }
    Canvas(Modifier.size(width, height).semantics { contentDescription = latex }) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(2f, 0f)
            renderer.draw(native)
            native.restore()
        }
    }
}
