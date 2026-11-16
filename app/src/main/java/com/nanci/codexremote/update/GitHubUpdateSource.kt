package com.nanci.codexremote.update

import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Public, unauthenticated GitHub-only transport. Never falls back to a saved SSH server. */
internal class GitHubUpdateSource : UpdateSource {
    private val stopped = AtomicBoolean(false)
    private val connection = AtomicReference<HttpsURLConnection?>()
    private var connected = false
    private var offered: UpdateRelease? = null
    private var apkUrl: String? = null

    override fun connect(profile: ConnectionProfile, credential: SshCredential) {
        ensureActive()
        if (profile != PROFILE) throw UpdateException("GitHub 更新来源不匹配")
        // No Authorization header, token, SSH password or key is ever sent to HTTP.
        connected = true
    }

    override fun metadata(): ByteArray {
        ensureConnected()
        try {
            val release = json(readBounded(API_LATEST, UpdateManifest.METADATA_LIMIT))
            if (release.getBoolean("draft") || release.getBoolean("prerelease"))
                throw UpdateException("GitHub 尚未发布可用的正式更新")
            val tag = release.getString("tag_name")
            if (!TAG_PATTERN.matches(tag)) throw UpdateException("GitHub 版本标签无效")
            val version = tag.removePrefix("v")
            val apkName = "Codex-$version.apk"
            val expectedBase = "$DOWNLOAD_ROOT/$tag/"
            val assets = release.getJSONArray("assets")
            if (assets.length() > 100) throw UpdateException("GitHub 发布附件过多")
            fun asset(name: String): JSONObject {
                val matching = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
                if (matching.size != 1) throw UpdateException("GitHub 更新附件缺失或重复")
                return matching.single().also {
                    if (it.getString("state") != "uploaded" || it.getString("browser_download_url") != expectedBase + name)
                        throw UpdateException("GitHub 更新附件地址不属于此版本")
                }
            }
            val metadataAsset = asset("release-metadata.json")
            val binaryAsset = asset(apkName)
            val metadataSize = integer(metadataAsset, "size", UpdateManifest.METADATA_LIMIT.toLong())
            val metadataBytes = readBounded(metadataAsset.getString("browser_download_url"), UpdateManifest.METADATA_LIMIT, metadataSize)
            val metadata = json(metadataBytes)
            if (metadata.getString("versionName") != version || metadata.getString("updateChannel") != "github" ||
                metadata.getString("signerSha256") != SIGNER || metadata.getString("apkFile") != "releases/$apkName")
                throw UpdateException("GitHub 更新信息与发布版本不一致")
            val binarySize = integer(binaryAsset, "size", UpdateManifest.APK_LIMIT)
            if (integer(metadata, "sizeBytes", UpdateManifest.APK_LIMIT) != binarySize)
                throw UpdateException("GitHub APK 大小与更新信息不一致")
            // Convert public release metadata to the existing strict, bounded manifest format.
            val normalized = JSONObject()
            for (key in MANIFEST_FIELDS) normalized.put(key, metadata.get(key))
            normalized.put("publishedAt", release.getString("published_at"))
            val bytes = normalized.toString().toByteArray(Charsets.UTF_8)
            val parsed = UpdateManifest.parse(bytes, PROFILE)
            val assetDigest = binaryAsset.optString("digest")
            if (assetDigest.isNotEmpty() && assetDigest != "null" && assetDigest != "sha256:${parsed.sha256}")
                throw UpdateException("GitHub 附件校验值与更新信息不一致")
            offered = parsed
            apkUrl = binaryAsset.getString("browser_download_url")
            return bytes
        } catch (error: UpdateException) { throw error }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw UpdateException("无法读取 GitHub 更新信息，请稍后重试") }
    }

    override fun apk(release: UpdateRelease): InputStream {
        ensureConnected()
        if (release.source != PROFILE || offered != release) throw UpdateException("GitHub 更新信息已变化，请重新检查")
        return open(requireNotNull(apkUrl), UpdateManifest.APK_LIMIT, release.sizeBytes)
    }

    private fun json(bytes: ByteArray): JSONObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JSONObject(text)
    }

    private fun integer(objectValue: JSONObject, key: String, max: Long): Long {
        val raw = objectValue.get(key)
        if (raw !is Int && raw !is Long) throw UpdateException("GitHub 更新信息中的数字无效")
        return (raw as Number).toLong().also {
            if (it !in 1..max) throw UpdateException("GitHub 更新附件大小无效")
        }
    }

    private fun readBounded(address: String, limit: Int, expectedSize: Long? = null): ByteArray =
        open(address, limit.toLong(), expectedSize).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw UpdateException("GitHub 更新信息超过大小限制")
                output.write(buffer, 0, count)
            }
            if (expectedSize != null && output.size().toLong() != expectedSize)
                throw UpdateException("GitHub 更新信息下载不完整")
            output.toByteArray()
        }

    private fun open(address: String, limit: Long, expectedSize: Long?): InputStream {
        var url = URL(address)
        repeat(6) { redirect ->
            ensureActive()
            requireAllowedUrl(url)
            val current = url.openConnection() as HttpsURLConnection
            current.instanceFollowRedirects = false
            current.connectTimeout = 10_000
            current.readTimeout = 20_000
            current.useCaches = false
            current.setRequestProperty("User-Agent", "Codex-Android-Updater")
            current.setRequestProperty("Accept-Encoding", "identity")
            current.setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            if (url.host == "api.github.com") current.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.set(current)
            try {
                ensureActive()
                val status = current.responseCode
                ensureActive()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirect == 5) throw UpdateException("GitHub 更新下载跳转过多")
                    val location = current.getHeaderField("Location") ?: throw UpdateException("GitHub 更新下载地址无效")
                    url = URL(url, location)
                    requireAllowedUrl(url)
                    current.disconnect()
                    connection.compareAndSet(current, null)
                } else {
                    if (status != HttpURLConnection.HTTP_OK) throw UpdateException(when (status) {
                        404 -> "GitHub 尚未发布可用更新"
                        403, 429 -> "GitHub 更新检查暂时受限，请稍后重试"
                        else -> "GitHub 更新服务器暂时不可用，请稍后重试"
                    })
                    val length = current.contentLengthLong
                    if (length > limit || (length >= 0 && expectedSize != null && length != expectedSize))
                        throw UpdateException("GitHub 更新文件长度与声明不一致")
                    val input = current.inputStream
                    ensureActive()
                    return object : FilterInputStream(input) {
                        override fun read(): Int { ensureActive(); return super.read().also { ensureActive() } }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            ensureActive(); return super.read(bytes, offset, length).also { ensureActive() }
                        }
                        override fun close() {
                            try { super.close() } finally {
                                current.disconnect(); connection.compareAndSet(current, null)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                current.disconnect()
                connection.compareAndSet(current, null)
                throw error
            }
        }
        throw UpdateException("GitHub 更新下载地址无效")
    }

    private fun requireAllowedUrl(url: URL) {
        if (url.protocol != "https" || url.userInfo != null || url.port !in listOf(-1, 443) || url.ref != null)
            throw UpdateException("GitHub 更新下载地址不安全")
        val allowed = when (url.host.lowercase()) {
            "api.github.com" -> url.toExternalForm() == API_LATEST
            "github.com" -> url.path.startsWith("/$REPOSITORY/releases/download/") && url.query == null && ".." !in url.path
            // GitHub's release attachments redirect to its HTTPS asset delivery hosts.
            "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
            else -> false
        }
        if (!allowed) throw UpdateException("更新下载跳转到了非 GitHub 地址，已拒绝连接")
    }

    private fun ensureConnected() { ensureActive(); if (!connected) throw UpdateException("GitHub 更新尚未初始化") }
    private fun ensureActive() { if (stopped.get()) throw CancellationException() }
    override fun close() { stopped.set(true); connection.getAndSet(null)?.disconnect(); connected = false }

    companion object {
        const val REPOSITORY = "jinshenganyuci/codex-cli-app"
        const val API_LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        private const val DOWNLOAD_ROOT = "https://github.com/$REPOSITORY/releases/download"
        private const val SIGNER = "12c9858dc73f6bfab725bb10ec341e3a203344f9bd8f1cea0b3874106ba25d3e"
        private val TAG_PATTERN = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")
        private val MANIFEST_FIELDS = listOf("schemaVersion", "packageName", "versionCode", "versionName",
            "releaseNotes", "apkFile", "sizeBytes", "sha256", "minSdk")
        val PROFILE = ConnectionProfile(host = "github.com", port = 443, user = "", fingerprint = "",
            name = "GitHub Releases", hostKeyAlgorithm = "", codexPath = "")
    }
}
