package com.nanci.codexremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.Xml
import com.caverock.androidsvg.SVG
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/** Static SVG only: native Canvas rendering, no WebView, scripts or resource resolver. */
internal object SvgImageDecoder {
    const val MAX_SVG_BYTES = 2 * 1024 * 1024
    private const val MAX_NODES = 10000
    private const val MAX_EXPANDED_NODES = 50000L
    private val reference = Regex("url\\(\\s*['\"]?#([^)'\"\\s]+)['\"]?\\s*\\)", RegexOption.IGNORE_CASE)

    /** A cheap XML signature check; the root element is validated before rendering. */
    fun isSvg(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val encoding = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        return String(bytes, 0, minOf(bytes.size, 512), encoding).trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith('<')
    }

    private fun standardEntity(name: String): Boolean {
        if (name in setOf("amp", "lt", "gt", "apos", "quot")) return true
        // nextToken() can expose numeric references too; those are ordinary XML text.
        val code = when {
            name.startsWith("#x") -> name.substring(2).toIntOrNull(16)
            name.startsWith('#') -> name.substring(1).toIntOrNull(10)
            else -> null
        } ?: return false
        return code in setOf(9, 10, 13) || code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
    }

    /** Preflight separately from AndroidSVG, before any renderer can allocate embedded images. */
    fun validate(bytes: ByteArray) {
        require(bytes.size in 1..MAX_SVG_BYTES) { "SVG 超过 2 MB 或内容为空" }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        val children = mutableListOf<MutableList<Int>>()
        val references = mutableListOf<MutableList<String>>()
        val ids = mutableMapOf<String, Int>()
        val stack = mutableListOf<Int>()
        var embeddedPixels = 0L
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> error("SVG 不支持文档实体声明")
                XmlPullParser.PROCESSING_INSTRUCTION -> require(!parser.text.trimStart().startsWith("xml-stylesheet", true)) { "SVG 不支持外部样式表" }
                XmlPullParser.ENTITY_REF -> require(standardEntity(parser.name)) { "SVG 不支持自定义实体" }
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    require(children.size < MAX_NODES && stack.size < 64) { "SVG 内容过于复杂" }
                    if (children.isEmpty()) require(tag == "svg" && parser.namespace.orEmpty() in setOf("", "http://www.w3.org/2000/svg")) { "图片格式无法识别" }
                    require(tag !in setOf("script", "foreignObject", "iframe", "object", "embed")) { "SVG 包含不支持的动态内容" }
                    val index = children.size
                    children.add(mutableListOf()); references.add(mutableListOf())
                    stack.lastOrNull()?.let { children[it] += index }
                    stack += index
                    for (attribute in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(attribute)
                        val value = parser.getAttributeValue(attribute)
                        require(!name.startsWith("on", true)) { "SVG 不支持脚本事件" }
                        if (name == "id") {
                            require(ids.put(value, index) == null) { "SVG 元素标识重复" }
                        }
                        if (name == "href") {
                            when {
                                value.startsWith('#') -> references[index] += value.drop(1)
                                tag == "image" && value.startsWith("data:image/", true) -> {
                                    val comma = value.indexOf(',')
                                    require(comma in 1..128 && value.substring(0, comma).endsWith(";base64", true)) { "SVG 内嵌图片编码不支持" }
                                    val data = Base64.decode(value.substring(comma + 1), Base64.DEFAULT)
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                                    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "SVG 内嵌图片格式无法识别" }
                                    embeddedPixels += bounds.outWidth.toLong() * bounds.outHeight
                                    require(embeddedPixels <= 8L * 1024 * 1024) { "SVG 内嵌图片过大" }
                                }
                                tag == "a" -> Unit // Anchors are inert in the bitmap renderer.
                                value.isBlank() -> Unit
                                else -> error("SVG 不支持引用外部文件")
                            }
                        }
                        reference.findAll(value).forEach { references[index] += it.groupValues[1] }
                    }
                }
                XmlPullParser.END_TAG -> { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
            }
            parser.nextToken()
        }
        require(children.isNotEmpty() && stack.isEmpty()) { "SVG 内容不完整" }
        // Bound nested <use>, markers and paint-server references, including indirect cycles.
        val states = IntArray(children.size)
        val costs = LongArray(children.size)
        fun cost(index: Int, depth: Int): Long {
            require(depth <= 64 && states[index] != 1) { "SVG 引用循环或层级过深" }
            if (states[index] == 2) return costs[index]
            states[index] = 1
            var total = 1L
            for (child in children[index] + references[index].mapNotNull(ids::get)) {
                total += cost(child, depth + 1)
                require(total <= MAX_EXPANDED_NODES) { "SVG 内容过于复杂" }
            }
            states[index] = 2; costs[index] = total
            return total
        }
        cost(0, 0)
    }

    @Synchronized private fun parse(bytes: ByteArray): SVG {
        // AndroidSVG 1.4 config is static. This project never installs a file resolver.
        SVG.setInternalEntitiesEnabled(false)
        SVG.deregisterExternalFileResolver()
        return SVG.getFromInputStream(ByteArrayInputStream(bytes))
    }

    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap {
        validate(bytes)
        var bitmap: Bitmap? = null
        try {
            val svg = parse(bytes)
            val box = svg.documentViewBox
            fun usable(value: Float): Boolean = value.isFinite() && value > 0f && value <= 100000f
            val width = svg.documentWidth.takeIf(::usable) ?: box?.width()?.takeIf(::usable) ?: 300f
            val height = svg.documentHeight.takeIf(::usable) ?: box?.height()?.takeIf(::usable) ?: 150f
            if (box == null) svg.setDocumentViewBox(0f, 0f, width, height)
            else require(usable(box.width()) && usable(box.height()) && box.left.isFinite() && box.top.isFinite()) { "SVG 尺寸无效" }
            val edge = maxEdge.coerceIn(128, 2048)
            val largest = maxOf(width, height)
            val targetWidth = (width / largest * edge).roundToInt().coerceIn(1, edge)
            val targetHeight = (height / largest * edge).roundToInt().coerceIn(1, edge)
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap = output
            svg.renderToCanvas(Canvas(output))
            return output
        } catch (_: StackOverflowError) {
            bitmap?.recycle()
            error("SVG 内容过于复杂")
        } catch (error: Throwable) {
            bitmap?.recycle()
            throw error
        }
    }
}
