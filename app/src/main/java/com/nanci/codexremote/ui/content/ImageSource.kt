package com.nanci.codexremote.ui.content

import androidx.compose.ui.graphics.ImageBitmap

enum class ImageSourceKind { DataUri, RemotePath, HttpUrl, GeneratedBase64 }

/** Payload is kept by reference; neither equals/hashCode nor toString scan or print image bytes. */
class ImageSource(
    val kind: ImageSourceKind,
    val value: String,
    val mimeType: String? = null,
    val cacheKey: String,
) {
    override fun toString(): String = "ImageSource(kind=$kind, cacheKey=$cacheKey)"
}

sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: ImageBitmap, val extension: String? = null) : ImageLoadState
    data class Error(val reason: String) : ImageLoadState
}

/** Implementations do IO/decoding away from the UI thread, and deliver callbacks on the main thread. */
interface ImageLoader {
    fun load(source: ImageSource, maxEdge: Int = 1024, onResult: (ImageLoadState) -> Unit): () -> Unit
}

internal fun messageImageSource(
    raw: String,
    key: String,
    allowRawBase64: Boolean = false,
    mimeType: String? = null,
): ImageSource? {
    if(raw.isEmpty()) return null
    val kind=when {
        raw.startsWith("data:image/",ignoreCase=true) -> ImageSourceKind.DataUri
        raw.startsWith("https://",ignoreCase=true) || raw.startsWith("http://",ignoreCase=true) -> ImageSourceKind.HttpUrl
        // Raw JPEG base64 often starts with /9j/; classify generated results before remote paths.
        allowRawBase64 -> ImageSourceKind.GeneratedBase64
        raw.startsWith('/') -> ImageSourceKind.RemotePath
        else -> return null
    }
    val version=if(kind==ImageSourceKind.RemotePath || kind==ImageSourceKind.HttpUrl) raw.take(4096) else raw.length.toString()
    return ImageSource(kind,raw,mimeType,"$key:$version")
}

/** Resolve model-authored image destinations as data, web URLs or files on this SSH server. */
fun resolveMarkdownImageSource(raw: String, key: String, cwd: String? = null): ImageSource? {
    val value = raw.trim()
    if (value.isEmpty() || value.any { it == '\u0000' || it == '\r' || it == '\n' }) return null
    if (value.startsWith("data:image/", true) || value.startsWith("https://", true) || value.startsWith("http://", true))
        return messageImageSource(value, key)
    val path = when {
        value.startsWith("sandbox:", true) -> value.substringAfter(':').takeIf { it.startsWith('/') } ?: return null
        value.startsWith("file://", true) -> {
            val rest = value.substring(7)
            when {
                rest.startsWith('/') -> rest
                rest.startsWith("localhost/", true) -> rest.substring(9)
                else -> return null // A file URI cannot select another host or a phone-local file.
            }
        }
        value.startsWith("file:", true) -> value.substringAfter(':').takeIf { it.startsWith('/') } ?: return null
        Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value) -> return null
        value.startsWith('~') || value.startsWith("//") -> return null
        else -> value
    }
    val decoded = try {
        java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) { path }
    if (decoded.any { it == '\u0000' || it == '\r' || it == '\n' }) return null
    val absolute = if (decoded.startsWith('/')) decoded else {
        val base = cwd?.takeIf { it.startsWith('/') && !it.any { c -> c == '\u0000' || c == '\r' || c == '\n' } } ?: return null
        "$base/$decoded"
    }
    val parts = mutableListOf<String>()
    absolute.split('/').forEach { part -> when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        else -> parts += part
    } }
    if (parts.isEmpty()) return null
    val normalized = "/" + parts.joinToString("/")
    val mime = when (normalized.substringAfterLast('.', "").lowercase()) {
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
    return messageImageSource(normalized, key, mimeType = mime)
}
