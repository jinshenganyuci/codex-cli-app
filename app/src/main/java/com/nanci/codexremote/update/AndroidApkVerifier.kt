package com.nanci.codexremote.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** PackageManager parses the actual archive; the platform installer additionally verifies it during install. */
internal class AndroidApkVerifier(context: Context) : UpdateApkVerifier {
    private val context = context.applicationContext
    private val pm = this.context.packageManager
    override val deviceSdk: Int get() = Build.VERSION.SDK_INT
    private val flags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    override fun installed(): ApkIdentity = try { identity(pm.getPackageInfo(context.packageName, flags)) }
    catch (_: Exception) { throw UpdateException("无法读取当前应用的版本和签名") }

    override fun archive(file: File): ApkIdentity = try {
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException("下载文件不是可识别的 Android 安装包")
        if (!info.splitNames.isNullOrEmpty()) throw UpdateException("此更新不是可独立安装的完整 APK")
        ZipFile(file).use { zip ->
            val abis = mutableSetOf<String>()
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                if (++count > 100_000) throw UpdateException("更新 APK 包含过多文件")
                val name = entries.nextElement().name
                if (name.startsWith("lib/") && name.endsWith(".so")) name.split('/').getOrNull(1)?.let(abis::add)
            }
            if (abis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in abis })
                throw UpdateException("更新 APK 不支持此设备的处理器架构")
        }
        identity(info)
    } catch (error: UpdateException) { throw error }
    catch (_: Exception) { throw UpdateException("无法解析更新 APK 的版本或签名") }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val hashes = signatures?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet().orEmpty()
        if (hashes.isEmpty()) throw UpdateException("无法验证 APK 的当前签名")
        return ApkIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.versionName.orEmpty(), info.applicationInfo?.minSdkVersion ?: throw UpdateException("APK 缺少系统版本要求"), hashes,
            (info.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            (info.applicationInfo!!.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0)
    }
}
