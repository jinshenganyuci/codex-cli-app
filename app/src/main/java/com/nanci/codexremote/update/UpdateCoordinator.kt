package com.nanci.codexremote.update

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.nanci.codexremote.BuildConfig
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.connection.SavedConnection
import com.nanci.codexremote.core.SshCredential
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

enum class UpdatePhase { Hidden, Available, Downloading, Ready, DownloadError, PermissionSettings, Installer }

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Hidden,
    val release: UpdateRelease? = null,
    val progress: UpdateProgress = UpdateProgress(0, 0),
    val apk: VerifiedApk? = null,
    val message: String? = null,
)

sealed interface UpdateInstallAction {
    data object RequestPermission : UpdateInstallAction
    data class OpenInstaller(val apk: VerifiedApk) : UpdateInstallAction
}

/**
 * Activity integration: ON_START -> onForeground(latestSaved),
 * ON_STOP -> onBackground(activity.isChangingConfigurations).
 * Call onConnectionAvailable when the saved active profile changes after composition.
 * This ViewModel survives rotation; UpdateUi owns only ActivityResult launchers.
 */
class UpdateCoordinator(application: Application) : AndroidViewModel(application) {
    private val main = Handler(Looper.getMainLooper())
    private val credentialsIO = Executors.newSingleThreadExecutor()
    // Public builds check GitHub independently of chat connections and never load SSH credentials.
    private val publicRelease = BuildConfig.PUBLIC_RELEASE
    private val store by lazy { ConnectionStore(application) }
    private val repository = UpdateRepository(application)
    private var active: SavedConnection? = null
    private var origin: SavedConnection? = null
    private var foreground = false
    private var sessionOpen = false
    private var session = 0L
    private var checked = false
    private var operation = 0L
    private var checking = false
    private var closed = false
    private var externalActive = false
    private var externalStartSeen = false
    private var externalWentBackground = false
    private var externalReturnPending = false

    var state by mutableStateOf(UpdateUiState()); private set
    /** Diagnostics only: contains neither credentials nor remote exception messages. */
    var automaticCheckCount by mutableStateOf(0); private set
    var lastAutomaticCheckStatus by mutableStateOf("not_started"); private set

    fun onForeground(saved: SavedConnection?) {
        if (closed) return
        foreground = true
        onConnectionAvailable(saved)
        if (externalActive || externalReturnPending) {
            if (externalActive) externalStartSeen = true
            externalReturnPending = false
            return
        }
        if (!sessionOpen) { sessionOpen = true; session++; checked = false }
        // A user-requested update already in progress is retained across backgrounding.
        // Starting a second repository operation here would cancel that download.
        if (state.phase in setOf(UpdatePhase.Downloading, UpdatePhase.Ready, UpdatePhase.DownloadError)) {
            checked = true
            return
        }
        checkIfNeeded()
    }

    fun onBackground(changingConfigurations: Boolean) {
        if (closed) return
        foreground = false
        if (changingConfigurations) return
        if (externalActive) { externalWentBackground = true; return }
        if (externalReturnPending) return
        sessionOpen = false
        if (checking) { operation++; checking = false; lastAutomaticCheckStatus = "cancelled_on_background"; repository.cancel() }
        if (state.phase == UpdatePhase.Available) { state = UpdateUiState(); origin = null }
    }

    fun onConnectionAvailable(saved: SavedConnection?) {
        if (closed) return
        if (publicRelease) { checkIfNeeded(); return }
        val changed = identity(active) != identity(saved)
        active = saved
        if (changed && origin != null && identity(origin) != identity(saved)) {
            // Never reinterpret an old release or verified file as belonging to a new host.
            operation++; checking = false; repository.cancel()
            state = UpdateUiState(); origin = null
        }
        checkIfNeeded()
    }

    private fun checkIfNeeded() {
        if (closed || !foreground || !sessionOpen || checked || checking || externalActive || externalReturnPending) return
        val saved = if (publicRelease) null else active?.takeIf { it.hasCredential }
        if (!publicRelease && saved == null) return
        if (state.phase != UpdatePhase.Hidden) return
        checked = true; checking = true; automaticCheckCount++; lastAutomaticCheckStatus = "checking"
        val expectedSession = session
        val token = ++operation
        origin = saved
        fun receive(request: CompletableFuture<UpdateCheck>) {
            request.whenComplete { result, failure ->
                if (!current(token, saved)) return@whenComplete
                checking = false
                if (failure == null && result.available && sessionOpen && session == expectedSession) {
                    lastAutomaticCheckStatus = "available"
                    state = UpdateUiState(UpdatePhase.Available, release = result.latest)
                } else {
                    lastAutomaticCheckStatus = if (failure == null) "current" else "failed_silently"
                    // Auto-check failures stay silent and never interfere with chat.
                    state = UpdateUiState(); origin = null
                }
            }
        }
        if (publicRelease) receive(repository.checkPublic())
        else withCredential(requireNotNull(saved), token, automatic = true) { secret ->
            receive(repository.check(saved.profile, secret))
        }
    }

    fun download() {
        if (closed || state.phase !in setOf(UpdatePhase.Available, UpdatePhase.DownloadError)) return
        val release = state.release ?: return
        val saved = origin
        if (!publicRelease && saved == null) return
        if (!originMatches()) { dismiss(); return }
        val token = ++operation
        checking = false
        state = UpdateUiState(UpdatePhase.Downloading, release, UpdateProgress(0, release.sizeBytes))
        val progress: (UpdateProgress) -> Unit = { value ->
            if (current(token, saved) && state.phase == UpdatePhase.Downloading) state = state.copy(progress = value)
        }
        fun receive(request: CompletableFuture<VerifiedApk>) {
            request.whenComplete { apk, failure ->
                if (!current(token, saved)) return@whenComplete
                state = if (failure == null) UpdateUiState(UpdatePhase.Ready, release, UpdateProgress(release.sizeBytes, release.sizeBytes), apk)
                else UpdateUiState(UpdatePhase.DownloadError, release, message = safeFailure(failure))
            }
        }
        if (publicRelease) receive(repository.downloadPublic(release, progress))
        else withCredential(requireNotNull(saved), token, automatic = false) { secret ->
            receive(repository.download(release, secret, progress))
        }
    }

    /** Cancel and Later both suppress this foreground session, including late callbacks. */
    fun dismiss() {
        if (closed || state.phase in setOf(UpdatePhase.PermissionSettings, UpdatePhase.Installer)) return
        operation++; checking = false; checked = true
        repository.cancel(); state = UpdateUiState(); origin = null
    }

    fun requestInstall(permissionGranted: Boolean): UpdateInstallAction? {
        if (closed || state.phase != UpdatePhase.Ready) return null
        val apk = state.apk ?: return null
        if (!originMatches()) { dismiss(); return null }
        if (!apk.file.isFile || apk.file.length() != apk.release.sizeBytes) {
            state = UpdateUiState(UpdatePhase.DownloadError, apk.release, message = "已下载的更新包不可用，请重新下载。")
            return null
        }
        beginExternal()
        return if (!permissionGranted) {
            state = state.copy(phase = UpdatePhase.PermissionSettings, message = null)
            UpdateInstallAction.RequestPermission
        } else {
            state = state.copy(phase = UpdatePhase.Installer, message = null)
            UpdateInstallAction.OpenInstaller(apk)
        }
    }

    /** The UI must re-read PackageManager.canRequestPackageInstalls(), ignoring resultCode. */
    fun onPermissionReturned(permissionGranted: Boolean): UpdateInstallAction? {
        returnedExternal()
        if (closed || state.phase != UpdatePhase.PermissionSettings) return null
        if (!originMatches()) { state = UpdateUiState(); origin = null; return null }
        state = state.copy(phase = UpdatePhase.Ready, message = if (permissionGranted) null else "尚未允许安装更新。需要时可再次点击“安装”。")
        return if (permissionGranted) requestInstall(true) else null
    }

    /** No trust in installer resultCode: success is the actual installed package version. */
    fun onInstallerReturned() {
        returnedExternal()
        if (closed || state.phase != UpdatePhase.Installer) return
        val release = state.release ?: return
        val installed = runCatching {
            @Suppress("DEPRECATION")
            val info = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(-1)
        if (installed >= release.versionCode.toLong()) { state = UpdateUiState(); origin = null }
        else state = state.copy(phase = UpdatePhase.Ready, message = "安装尚未完成，可以再次点击“安装”。")
    }

    fun onExternalLaunchFailed() {
        externalActive = false; externalStartSeen = false; externalWentBackground = false; externalReturnPending = false
        if (state.apk != null) state = state.copy(phase = UpdatePhase.Ready, message = "无法打开系统安装页面，请稍后重试。")
    }

    private fun beginExternal() {
        externalActive = true; externalStartSeen = false; externalWentBackground = false; externalReturnPending = false
    }
    private fun returnedExternal() {
        // ActivityResult may arrive before or after ON_START. Preserve suppression until
        // the matching start consumes it; rotation never launches another external flow.
        externalReturnPending = externalWentBackground && !externalStartSeen
        externalActive = false; externalStartSeen = false; externalWentBackground = false
    }

    private fun withCredential(saved: SavedConnection, token: Long, automatic: Boolean, block: (SshCredential) -> Unit) {
        credentialsIO.execute {
            val loaded = runCatching {
                val current = store.list().firstOrNull { it.id == saved.id }
                    ?: throw UpdateException("连接已移除，请重新选择服务器。")
                if (identity(current) != identity(saved)) throw UpdateException("连接设置已改变，请重新打开应用后检查更新。")
                store.loadCredential(saved.id) ?: throw UpdateException("连接凭据不可用，请重新导入后再试。")
            }
            main.post {
                val secret = loaded.getOrNull()
                if (!current(token, saved)) { secret?.close(); return@post }
                if (secret == null) {
                    checking = false
                    if (automatic) { lastAutomaticCheckStatus = "credentials_unavailable"; state = UpdateUiState(); origin = null }
                    else state = UpdateUiState(UpdatePhase.DownloadError, state.release, message = "无法读取连接凭据，请重新导入后再试。")
                } else try { block(secret) }
                catch (failure: Exception) {
                    checking = false
                    if (automatic) { lastAutomaticCheckStatus = "failed_silently"; state = UpdateUiState(); origin = null }
                    else state = UpdateUiState(UpdatePhase.DownloadError, state.release, message = safeFailure(failure))
                } finally { secret.close() }
            }
        }
    }

    private fun current(token: Long, saved: SavedConnection?): Boolean = !closed && token == operation &&
        (publicRelease || saved != null && identity(saved) == identity(active))
    private fun originMatches(): Boolean = publicRelease || identity(origin) == identity(active)
    private fun identity(saved: SavedConnection?): String? = saved?.let {
        listOf(it.id, it.profile.host, it.profile.port.toString(), it.profile.user, it.profile.fingerprint, it.profile.hostKeyAlgorithm).joinToString("\u0000")
    }
    private fun safeFailure(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        return (cause as? UpdateException)?.safeMessage ?: "更新下载未完成，请检查连接后重试。"
    }

    override fun onCleared() {
        closed = true; operation++; repository.close(); credentialsIO.shutdown()
        // Pending credential tasks still close their result when this controller is closed.
        super.onCleared()
    }
}
