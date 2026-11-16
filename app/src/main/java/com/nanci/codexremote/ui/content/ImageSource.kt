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
    data class Ready(val bitmap: ImageBitmap) : ImageLoadState
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
