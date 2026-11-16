package com.nanci.codexremote.update

import com.nanci.codexremote.core.ConnectionProfile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime

data class UpdateRelease(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val publishedAt: String,
    val releaseNotes: List<String>,
    val apkFile: String,
    val sizeBytes: Long,
    val sha256: String,
    val minSdk: Int,
    /** Immutable origin: changing the active chat connection cannot retarget a download. */
    val source: ConnectionProfile,
)

data class UpdateCheck(val installedVersionCode: Long, val latest: UpdateRelease, val available: Boolean)
data class UpdateProgress(val downloadedBytes: Long, val totalBytes: Long)
data class VerifiedApk(val release: UpdateRelease, val file: File)

/** Contains only deliberate, safe UI text; never attach a raw SSH/provider exception. */
class UpdateException(val safeMessage: String) : Exception(safeMessage)

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val signerSha256: Set<String>,
    val debuggable: Boolean = false,
    val testOnly: Boolean = false,
)

internal interface UpdateApkVerifier {
    val deviceSdk: Int
    fun installed(): ApkIdentity
    fun archive(file: File): ApkIdentity
}

internal object UpdateManifest {
    const val PACKAGE_NAME = "com.nanci.codexremote"
    const val METADATA_LIMIT = 64 * 1024
    const val APK_LIMIT = 100L * 1024 * 1024
    const val REMOTE_DIRECTORY = ".local/share/codex-mobile/updates"
    private val pathPattern = Regex("releases/[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.apk")
    private val hashPattern = Regex("[a-fA-F0-9]{64}")
    private val keys = setOf("schemaVersion", "packageName", "versionCode", "versionName", "publishedAt",
        "releaseNotes", "apkFile", "sizeBytes", "sha256", "minSdk")

    fun parse(bytes: ByteArray, source: ConnectionProfile): UpdateRelease {
        if (bytes.isEmpty() || bytes.size > METADATA_LIMIT) throw UpdateException("更新索引超过 64 KiB 限制或内容为空")
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            StrictJson(text).validate()
            val root = JSONObject(text)
            if (root.keys().asSequence().toSet() != keys) throw UpdateException("更新索引字段不完整或不受支持")
            fun integer(name: String, max: Long): Long {
                val raw = root.get(name)
                if (raw !is Int && raw !is Long) throw UpdateException("更新索引中的数字字段格式无效")
                return (raw as Number).toLong().also { if (it !in 1..max) throw UpdateException("更新索引中的数字超出允许范围") }
            }
            fun string(name: String): String = (root.get(name) as? String)
                ?: throw UpdateException("更新索引中的文本字段格式无效")
            val schema = integer("schemaVersion", Int.MAX_VALUE.toLong()).toInt()
            if (schema != 1) throw UpdateException("此版本的客户端不支持该更新索引")
            val notes = root.get("releaseNotes") as? JSONArray ?: throw UpdateException("更新说明格式无效")
            if (notes.length() > 100) throw UpdateException("更新说明过长")
            val release = UpdateRelease(schema, string("packageName"), integer("versionCode", Int.MAX_VALUE.toLong()).toInt(),
                string("versionName"), string("publishedAt"), (0 until notes.length()).map { index ->
                    (notes.get(index) as? String)?.takeIf { it.length <= 4096 && !it.contains('\u0000') }
                        ?: throw UpdateException("更新说明格式无效或过长")
                }, string("apkFile"), integer("sizeBytes", APK_LIMIT), string("sha256").lowercase(),
                integer("minSdk", 10000).toInt(), source)
            validate(release)
            return release
        } catch (error: UpdateException) { throw error }
        catch (_: Exception) { throw UpdateException("更新索引格式无效") }
    }

    fun validate(release: UpdateRelease) {
        if (release.schemaVersion != 1 || release.packageName != PACKAGE_NAME) throw UpdateException("更新包不属于此应用")
        if (release.versionCode <= 0 || release.minSdk !in 1..10000 || release.sizeBytes !in 1..APK_LIMIT)
            throw UpdateException("更新版本或文件大小无效")
        if (!pathPattern.matches(release.apkFile) || ".." in release.apkFile)
            throw UpdateException("更新文件路径不在允许的发布目录内")
        if (!hashPattern.matches(release.sha256)) throw UpdateException("更新包校验值格式无效")
        if (release.versionName.isBlank() || release.versionName.length > 128 || release.versionName.any { it.code < 32 })
            throw UpdateException("更新版本名称格式无效")
        if (release.publishedAt.length > 64 || runCatching { OffsetDateTime.parse(release.publishedAt) }.isFailure)
            throw UpdateException("更新发布时间格式无效")
        if (release.releaseNotes.size > 100 || release.releaseNotes.any { it.length > 4096 || '\u0000' in it })
            throw UpdateException("更新说明格式无效或过长")
    }

    fun requireInstallable(release: UpdateRelease, installed: ApkIdentity, candidate: ApkIdentity, deviceSdk: Int) {
        validate(release)
        if (installed.packageName != PACKAGE_NAME || candidate.packageName != PACKAGE_NAME)
            throw UpdateException("更新 APK 的应用包名不匹配")
        if (candidate.versionCode != release.versionCode.toLong() || candidate.versionName != release.versionName)
            throw UpdateException("更新 APK 的版本与索引不一致")
        if (candidate.versionCode <= installed.versionCode) throw UpdateException("不能安装相同版本或降级版本")
        if (candidate.debuggable || candidate.testOnly) throw UpdateException("更新 APK 是调试或测试专用构建，已拒绝安装")
        if (candidate.minSdk != release.minSdk) throw UpdateException("更新 APK 的系统要求与索引不一致")
        if (candidate.minSdk > deviceSdk) throw UpdateException("此更新需要更高版本的 Android 系统")
        if (installed.signerSha256.isEmpty() || candidate.signerSha256.isEmpty() || installed.signerSha256 != candidate.signerSha256)
            throw UpdateException("更新 APK 的签名与已安装应用不一致，已拒绝安装")
    }

    /** Android's JSON parser accepts duplicate keys and lenient syntax; reject both before interpretation. */
    private class StrictJson(private val text: String) {
        private var at = 0
        fun validate() { value(0); space(); require(at == text.length) }
        private fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
        private fun take(char: Char) { space(); require(at < text.length && text[at++] == char) }
        private fun value(depth: Int) {
            require(depth <= 32); space(); require(at < text.length)
            when (text[at]) {
                '{' -> {
                    at++; space(); val names = mutableSetOf<String>()
                    if (at < text.length && text[at] == '}') { at++; return }
                    while (true) {
                        require(names.add(string())); take(':'); value(depth + 1); space()
                        if (at < text.length && text[at] == '}') { at++; break }; take(',')
                    }
                }
                '[' -> {
                    at++; space(); if (at < text.length && text[at] == ']') { at++; return }
                    while (true) { value(depth + 1); space(); if (at < text.length && text[at] == ']') { at++; break }; take(',') }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, at)
                    require(match != null && match.range.first == at); at = match.range.last + 1
                }
            }
        }
        private fun literal(s: String) { require(text.startsWith(s, at)); at += s.length }
        private fun string(): String {
            space(); val start = at; require(at < text.length && text[at++] == '"')
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return JSONTokener(text.substring(start, at)).nextValue() as String
                require(c.code >= 32)
                if (c == '\\') {
                    require(at < text.length)
                    when (text[at++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) { require(at < text.length && text[at++].digitToIntOrNull(16) != null) }
                        else -> error("escape")
                    }
                }
            }
            error("unterminated string")
        }
    }
}
