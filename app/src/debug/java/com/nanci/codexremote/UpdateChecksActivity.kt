package com.nanci.codexremote

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.connection.SavedConnection
import com.nanci.codexremote.update.UpdateCoordinator
import com.nanci.codexremote.update.UpdateUi
import com.nanci.codexremote.update.AndroidApkVerifier
import com.nanci.codexremote.update.UpdateException
import com.nanci.codexremote.update.UpdateManifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Real installed package, saved SSH profile and production updater; never overrides the version. */
class UpdateChecksActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var updates: UpdateCoordinator
    private var active by mutableStateOf<SavedConnection?>(null)
    private var installed by mutableStateOf("正在读取实际安装版本…")
    private var installedCode = -1L
    private var installedName = ""
    private var loadError by mutableStateOf<String?>(null)
    private val events = mutableListOf<JSONObject>()
    private var destroyed = false
    private var started = false
    private var profileLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updates = ViewModelProvider(this)[UpdateCoordinator::class.java]
        enableEdgeToEdge()
        loadSavedConnection()
        setContent {
            val state = updates.state
            LaunchedEffect(state.phase, state.progress, state.message, updates.automaticCheckCount, updates.lastAutomaticCheckStatus) {
                record("state")
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0D0D0D), onPrimary = Color.White, background = Color(0xFFFAFAFA))) {
                Column(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("自动更新平台核验 · Debug", fontSize = 20.sp)
                    Text(installed, fontSize = 15.sp)
                    Text("当前连接：${active?.profile?.name ?: "尚未加载"}", fontSize = 14.sp)
                    Text("检查次数：${updates.automaticCheckCount} · ${updates.lastAutomaticCheckStatus}", fontSize = 14.sp)
                    Text("阶段：${state.phase.name}", fontSize = 14.sp)
                    state.release?.let { Text("服务器版本：${it.versionName} (${it.versionCode})", fontSize = 14.sp) }
                    loadError?.let { Text(it, fontSize = 14.sp, color = Color(0xFFB42318)) }
                    Text("使用实际保存的 SSH 连接与实际 PackageManager 版本。没有替换版本号，也没有模拟下载或安装成功。", fontSize = 13.sp, color = Color(0xFF5D5D5D))
                    Text("检查安装授权返回、取消后重试、旋转不重复检查，以及稍后后新前台再次检查。结果保存到 update-ui-checks.json。", fontSize = 13.sp, color = Color(0xFF5D5D5D))
                }
                UpdateUi(updates)
            }
        }
        if (intent.getStringExtra("action") == "verify-fixtures") verifyFixtures()
    }

    private fun loadSavedConnection() {
        io.execute {
            val loaded = runCatching {
                val store = ConnectionStore(this)
                val connection = store.list().firstOrNull { it.id == store.activeId }
                @Suppress("DEPRECATION") val info = packageManager.getPackageInfo(packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                Triple(connection, code, info.versionName.orEmpty())
            }
            main.post {
                if (destroyed) return@post
                loaded.onSuccess { (connection, code, name) ->
                    active = connection; profileLoaded = true; installedCode = code; installedName = name
                    installed = "实际已安装：$name ($code)"
                    if (started) updates.onForeground(connection) else updates.onConnectionAvailable(connection)
                    record("profile_loaded")
                }.onFailure { loadError = "无法读取连接或安装版本，请先在主界面添加连接。"; record("setup_failed") }
            }
        }
    }

    override fun onStart() {
        super.onStart(); started = true
        if (profileLoaded) updates.onForeground(active)
        record("on_start")
    }
    override fun onStop() {
        started = false; updates.onBackground(isChangingConfigurations)
        record(if (isChangingConfigurations) "configuration_stop" else "on_stop"); super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        when (intent.getStringExtra("action")) {
            "reload" -> loadSavedConnection()
            "dump" -> record("dump")
            "verify-fixtures" -> verifyFixtures()
        }
    }

    /** Actual PM and production policy checks; fixture SHA is independently checked first. */
    private fun verifyFixtures() {
        io.execute {
            val rows = JSONArray()
            var installedVersion = -1L
            var setupError: String? = null
            try {
                val store = ConnectionStore(this)
                val source = store.list().firstOrNull { it.id == store.activeId }?.profile
                    ?: throw UpdateException("请先保存实际服务器连接。")
                val verifier = AndroidApkVerifier(this)
                val installed = verifier.installed()
                installedVersion = installed.versionCode
                val directory = File(filesDir, "update-fixtures")
                val names = listOf("valid", "wrong-signer", "tampered").filter { File(directory, "$it.apk").exists() || File(directory, "$it.json").exists() }
                if (names.isEmpty()) throw UpdateException("尚未提供更新验签 fixture。")
                for (name in names) {
                    val row = JSONObject().put("fixture", name).put("expected", if (name == "valid") "accept" else "reject")
                    var stage = "fixture_io"
                    var hashPassed = false
                    try {
                        val apk = File(directory, "$name.apk")
                        val manifest = File(directory, "$name.json")
                        require(apk.isFile && manifest.isFile)
                        require(manifest.length() in 1..UpdateManifest.METADATA_LIMIT.toLong())
                        stage = "manifest"
                        val release = UpdateManifest.parse(manifest.readBytes(), source)
                        row.put("candidateVersionCode", release.versionCode)
                        stage = "size_sha256"
                        require(apk.length() == release.sizeBytes)
                        val digest = MessageDigest.getInstance("SHA-256")
                        apk.inputStream().use { input ->
                            val bytes = ByteArray(64 * 1024)
                            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
                        }
                        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                        require(actualHash == release.sha256)
                        hashPassed = true
                        stage = "android_archive"
                        val candidate = verifier.archive(apk)
                        row.put("archivePackageMatched", candidate.packageName == installed.packageName)
                        stage = "installability"
                        UpdateManifest.requireInstallable(release, installed, candidate, verifier.deviceSdk)
                        row.put("accepted", true).put("category", "accepted").put("checkPassed", name == "valid")
                    } catch (failure: Exception) {
                        val safe = (failure as? UpdateException)?.safeMessage
                        val category = when {
                            stage == "fixture_io" -> "fixture_missing_or_unreadable"
                            stage == "manifest" -> "manifest_rejected"
                            stage == "size_sha256" -> "size_or_sha256_rejected"
                            safe?.contains("签名") == true -> "signature_rejected"
                            stage == "android_archive" -> "android_archive_rejected"
                            safe?.contains("版本") == true -> "version_rejected"
                            else -> "installability_rejected"
                        }
                        val expectedNegative = when (name) {
                            "wrong-signer" -> hashPassed && stage == "installability" && category == "signature_rejected"
                            "tampered" -> hashPassed && (stage == "android_archive" || category == "signature_rejected")
                            else -> false
                        }
                        row.put("accepted", false).put("category", category).put("checkPassed", expectedNegative)
                    }
                    row.put("stage", stage).put("sizeAndSha256Passed", hashPassed)
                    rows.put(row)
                }
            } catch (failure: Exception) { setupError = (failure as? UpdateException)?.safeMessage ?: "无法准备实际 APK 验签检查。" }
            val result = JSONObject().put("scope", "Actual AndroidApkVerifier and production UpdateManifest policy; no fake installed version and no installation")
                .put("installedVersionCode", installedVersion).put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("checks", rows).put("setupError", setupError)
                .put("allProvidedChecksPassed", setupError == null && rows.length() > 0 && (0 until rows.length()).all { rows.getJSONObject(it).optBoolean("checkPassed") })
            runCatching {
                val temporary = File(filesDir, "update-fixture-checks.json.tmp"); temporary.writeText(result.toString(2))
                check(temporary.renameTo(File(filesDir, "update-fixture-checks.json")))
            }
            main.post { if (!destroyed) { loadError = setupError; record("fixture_verification_finished") } }
        }
    }

    private fun record(event: String) {
        if (destroyed) return
        val state = updates.state
        val row = JSONObject().put("event", event).put("elapsedMs", android.os.SystemClock.elapsedRealtime())
            .put("phase", state.phase.name).put("checkCount", updates.automaticCheckCount).put("checkStatus", updates.lastAutomaticCheckStatus)
            .put("downloadedBytes", state.progress.downloadedBytes).put("totalBytes", state.progress.totalBytes)
            .put("canRequestPackageInstalls", Build.VERSION.SDK_INT < 26 || runCatching { packageManager.canRequestPackageInstalls() }.getOrDefault(false))
        events += row
        if (events.size > 400) events.removeAt(0)
        val json = JSONObject().put("scope", "Real production updater and actual installed Android package; user-controlled platform installation")
            .put("installedVersionCodeAtActivityStart", installedCode).put("installedVersionNameAtActivityStart", installedName)
            .put("automaticCheckCount", updates.automaticCheckCount).put("checkStatus", updates.lastAutomaticCheckStatus)
            .put("phase", state.phase.name).put("candidateVersionCode", state.release?.versionCode)
            .put("candidateVersionName", state.release?.versionName).put("message", state.message)
            .put("events", JSONArray(events.toList())).put("installationAutomaticallyDeclaredSuccessful", false).toString(2)
        io.execute {
            runCatching {
                val temp = File(filesDir, "update-ui-checks.json.tmp"); temp.writeText(json)
                check(temp.renameTo(File(filesDir, "update-ui-checks.json")))
            }
        }
    }

    override fun onDestroy() { destroyed = true; io.shutdown(); super.onDestroy() }
}
