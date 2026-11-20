package com.nanci.codexremote

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.FileProvider
import com.nanci.codexremote.connection.*
import com.nanci.codexremote.core.*
import com.nanci.codexremote.ui.shell.*
import com.nanci.codexremote.ui.content.ImageSource
import com.nanci.codexremote.ui.content.ImageSourceKind
import com.nanci.codexremote.ui.content.ChangedFilesSummary
import com.nanci.codexremote.ui.content.ChangedFilesScope
import com.nanci.codexremote.ui.content.parseChangedFiles
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

data class DraftAttachment(
    val id: String,
    val label: String,
    val input: JSONObject? = null,
    val thumbnail: Bitmap? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val imagePreview: ImageSource? = null,
)
class SectionMovePendingException(val sectionId:String) : Exception("分区已创建，但移动聊天未完成。请重试移动，或稍后在“移至分区”中选择它。")

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    val repo = CodexRepository()
    val store = ConnectionStore(app)
    private val prefs = app.getSharedPreferences("ui-state", 0)
    private val modelPreferences = ModelPreferences(app)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    var state by mutableStateOf(repo.state); private set
    var connections by mutableStateOf(emptyList<SavedConnection>()); private set
    var activeConnection by mutableStateOf<SavedConnection?>(null); private set
    var home by mutableStateOf(true)
    var creating by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var draft by mutableStateOf(""); private set
    var attachments by mutableStateOf(emptyList<DraftAttachment>()); private set
    private var modelSelection by mutableStateOf(ModelSelection())
    val model get() = modelSelection.model
    val effort get() = modelSelection.effort
    val speed get() = modelSelection.speed
    val speedTiers get() = composerSpeeds(model, state.models)
    var planMode by mutableStateOf(false)
    private var selectedPermission by mutableStateOf<PermissionPreset?>(null)
    private val effectivePermission get() = PermissionCapabilities.composerPreset(state, model, selectedPermission)
    val permission get() = shellPermission(effectivePermission)
    var focusRequest by mutableStateOf(0)
    var imageExport:Pair<String,ImageSource>?=null
    var changedFiles by mutableStateOf<ChangedFilesSummary?>(null); private set
    private var diffOwner:String?=null
    private var diffSequence=0L
    private var diffRefreshTask:Runnable?=null
    private var disposed=false
    val files = RemoteFileCoordinator(app, ::imageOwner, repo::downloadFile) { message = it }
    val images=ImagePipeline(app) {owner,path,maxBytes ->
        val result=CompletableFuture<ByteArray>()
        main.post {
            if(owner!=imageOwner())result.completeExceptionally(CodexException("连接已切换，请重新打开图片"))
            else repo.readFile(path,maxBytes).whenComplete {bytes,error ->
                if(error!=null)result.completeExceptionally(error) else result.complete(bytes)
            }
        }
        result
    }
    fun imageOwner():String=activeConnection?.ownerKey ?: "no-connection"
    // Existing records keep their on-device draft keys. Replacing credentials creates a fresh space.
    private fun draftOwner():String?=activeConnection?.let { if(it.credentialRevision.isEmpty()) it.id else it.identityKey }
    fun copyImage(source:ImageSource) {
        images.clipboardFile(imageOwner(),source).whenComplete {file,error ->
            if(error!=null)message=safeError(error)
            else runCatching {
                val app=getApplication<Application>()
                val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file)
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(app.contentResolver,"图片",uri))
                message="已复制图片"
            }.onFailure {message="图片复制失败"}
        }
    }
    fun saveImage(uri:Uri,source:ImageSource,owner:String) {
        images.original(owner,source).whenComplete {bytes,error ->
            if(error!=null)message=safeError(error) else saveBytes(uri,bytes)
        }
    }
    fun saveBytes(uri:Uri,bytes:ByteArray) {
        io.execute {
            val saved=runCatching {getApplication<Application>().contentResolver.openOutputStream(uri)?.use {it.write(bytes)} ?: error("no stream")}.isSuccess
            main.post {message=if(saved)"已保存" else "文件保存失败"}
        }
    }
    var grouping by mutableStateOf(runCatching { ThreadGrouping.valueOf(prefs.getString("grouping", "Project")!!) }.getOrDefault(ThreadGrouping.Project))
    var workspace by mutableStateOf(""); private set
    var dismissedPlans by mutableStateOf(prefs.getStringSet("dismissedPlans", emptySet())!!.toSet()); private set
    var pinned by mutableStateOf(prefs.getStringSet("pinned", emptySet())!!.toSet()); private set
    private var removedProjects by mutableStateOf(prefs.getStringSet("removed-projects",emptySet())!!.toSet())
    var threadActionsBusy by mutableStateOf(false); private set
    private var threadActionSequence = 0L
    private var stagedAsync by mutableStateOf(emptyMap<String,String>())
    private val asyncGroup get() = state.asyncQuestions.firstOrNull()?.let { first -> state.asyncQuestions.filter { it.itemId==first.itemId && it.turnId==first.turnId } }.orEmpty()
    val currentAsync get() = asyncGroup.firstOrNull { it.key !in stagedAsync }
    fun asyncParams(q:AsyncQuestion):JSONObject = JSONObject(q.raw.toString()).put("index",asyncGroup.indexOfFirst {it.key==q.key}.coerceAtLeast(0)).put("total",asyncGroup.size)
    fun closeAsync() { asyncGroup.toList().forEach {repo.dismissAsync(it.key)};stagedAsync=emptyMap() }
    fun answerAsync(answer:String) {
        val question=currentAsync ?: return
        val group=asyncGroup.toList()
        val answers=stagedAsync.filterKeys { key->group.any { it.key==key } }+(question.key to answer)
        if(group.any { it.key !in answers }) { stagedAsync=answers; return }
        val nonblank=answers.filterValues {it.isNotBlank()}
        if(nonblank.isEmpty()) {closeAsync();return}
        observe(repo.replyAsync(nonblank)) { group.forEach {repo.dismissAsync(it.key)};stagedAsync=emptyMap() }
    }
    private var draftKey = "new"
    private var selectionId:String? = null
    private class HomeDraftTransfer(val owner: String, val homeKey: String, val keepEditor: Boolean) {
        var threadKey: String? = null
    }
    private var homeDraftTransfer: HomeDraftTransfer? = null
    private var homeDraftRevision = 0L
    private var chatCreation = 0L
    val editingHomeDraftWhileCreating: Boolean
        get() = creating && homeDraftTransfer?.let { it.owner == imageOwner() && it.keepEditor } == true
    val editorDraftReady: Boolean
        get() = ComposerConnectionPolicy.editorReady(draftOwner(), home, creating,
            editingHomeDraftWhileCreating, draftKey, state.selectedThread?.id, selectionId)
    private var reconnectCount = 0
    private var manualDisconnect = false
    private var reconnectScheduled = false
    private var reconnectTask:Runnable?=null
    private var appVisible=false
    private var connectAttempt=0L
    private data class PendingAttachment(val uri:Uri,val connection:String?,val thread:String?,val id:String)
    private val waitingAttachments=mutableListOf<PendingAttachment>()
    private val attachmentReads=mutableMapOf<String,Long>()
    private var attachmentReadSequence=0L
    private val subscription = repo.addListener { incoming ->
        val wasConnected = state.connected
        val modelsChanged = incoming.models != state.models
        val previousError=state.lastError
        val refreshDiff=incoming.selectedThread?.id!=state.selectedThread?.id ||
            incoming.selectedThread?.cwd!=state.selectedThread?.cwd ||
            incoming.connection.generation!=state.connection.generation ||
            (state.loadingThread && !incoming.loadingThread) || incoming.diff!=state.diff ||
            incoming.activeTurnId!=state.activeTurnId ||
            incoming.turns.lastOrNull()?.items?.lastOrNull {it.type=="fileChange"} !=
                state.turns.lastOrNull()?.items?.lastOrNull {it.type=="fileChange"}
        val newlyDeleted=incoming.deletedThreadIds-state.deletedThreadIds
        val deletedSelection=state.selectedThread?.id in newlyDeleted || selectionId in newlyDeleted
        val replacements=incoming.draftReplacements.filter { (old,new)->state.draftReplacements[old]!=new }
        if (!incoming.connected || state.connection.generation != incoming.connection.generation) {
            threadActionSequence++; threadActionsBusy=false
        }
        state = incoming
        replacements.forEach { (old,new) ->
            val oldKey="${draftOwner()}:$old"
            val newKey="${draftOwner()}:$new"
            val transfer = boundHomeDraft()?.takeIf { draftKey == oldKey }
            val saved = if (transfer != null) null else if (draftKey == oldKey) draft else prefs.getString("draft:$oldKey", null)
            prefs.edit().apply { if(saved!=null)putString("draft:$newKey",saved);remove("draft:$oldKey") }.apply()
            if(draftKey==oldKey) {
                draftKey=newKey
                transfer?.threadKey = newKey
                if(selectionId==old)selectionId=new
                // A reconnect can recreate only a proven unsent draft. Keep the text,
                // attachments and selected model instead of loading a blank new draft.
            }
            for(index in waitingAttachments.indices) {
                val pending=waitingAttachments[index]
                if(pending.connection==draftOwner() && pending.thread==old)
                    waitingAttachments[index]=pending.copy(thread=new)
            }
        }
        if(newlyDeleted.isNotEmpty()) {
            val connectionId=draftOwner()
            val deletedDraftKeys=newlyDeleted.map {"$connectionId:$it"}.toSet()
            val editor=prefs.edit()
            deletedDraftKeys.forEach {editor.remove("draft:$it")}
            pinned=pinned-deletedDraftKeys
            editor.putStringSet("pinned",pinned).apply()
            waitingAttachments.removeAll {it.connection==connectionId && it.thread in newlyDeleted}
            if(deletedSelection && incoming.selectedThread==null) {
                // Do not persist the deleted draft again when returning to the list.
                selectionId=null;home=true;creating=false;attachments=emptyList();stagedAsync=emptyMap()
                homeDraftTransfer = null
                selectedPermission = null
                draftKey="$connectionId:new";draft=prefs.getString("draft:$draftKey","").orEmpty()
                restoreModelDefaults()
            } else if(draftKey in deletedDraftKeys) {
                draft=""
            }
        }
        // Transport recovery has a persistent status beside the chat. Do not put
        // each automatic retry over the editor as a transient error snackbar.
        if(incoming.connected && incoming.lastError!=null && incoming.lastError!=previousError)message=incoming.lastError
        incoming.selectedThread?.takeIf { !home && !incoming.loadingThread }?.let { thread ->
            if (thread.id != selectionId) {
                val transfer = homeDraftTransfer?.takeIf { creating && it.owner == imageOwner() && it.homeKey == draftKey }
                persistDraft()
                selectionId = thread.id
                draftKey = "${draftOwner()}:${thread.id}"
                if (transfer != null) {
                    // The unsubmitted thread may not appear in the list. Keep its
                    // Home draft as the only persistent copy until the first send.
                    transfer.threadKey = draftKey
                    for(index in waitingAttachments.indices) {
                        val pending=waitingAttachments[index]
                        if(pending.connection==draftOwner())waitingAttachments[index]=pending.copy(thread=thread.id)
                    }
                } else {
                    homeDraftTransfer = null
                    draft = prefs.getString("draft:$draftKey", "") ?: ""
                    val retained=waitingAttachments.filter {it.connection==draftOwner() && it.thread==thread.id}.map {it.id}.toSet()
                    attachments=attachments.filter {it.id in retained}
                    waitingAttachments.removeAll {it.id !in retained}
                }
                // A newly-created thread inherits server defaults, but the unsent composer already
                // holds the user's choices. Preserve those choices (including any newer edits).
                if (!creating) {
                    if(modelPreferences.initialChoicePending(activeConnection)) {
                        // Opening an old chat during the upgrade must not undo the forced default.
                        // Keep the marker until a turn is accepted or the user chooses explicitly.
                        restoreModelDefaults(incoming.models)
                    } else {
                        val defaults = modelPreferences.read(activeConnection).available(incoming.models)
                        val threadModel = incoming.threadSettings?.optString("model")?.takeIf { it.isNotBlank() && it!="null" }
                            ?: thread.model?.takeIf { it.isNotBlank() } ?: defaults.model
                        val threadEffort = incoming.threadSettings?.optString("effort")?.takeIf { it.isNotBlank() && it!="null" }
                            ?: thread.raw.optString("reasoningEffort", "").takeIf { it.isNotBlank() && it!="null" }
                            ?: ModelSelection(threadModel, defaults.effort).available(incoming.models).effort
                        val threadSpeed = ComposerSpeed.fromServiceTier(incoming.threadSettings?.stringOrNull("serviceTier"))
                            ?: defaults.speed.takeIf { threadModel == defaults.model } ?: ComposerSpeed.STANDARD
                        modelSelection = ModelSelection(threadModel, threadEffort, threadSpeed)
                    }
                    planMode = incoming.threadSettings?.optJSONObject("collaborationMode")?.optString("mode") == "plan"
                }
            }
        }
        if (modelsChanged && (home || (!creating && modelPreferences.initialChoicePending(activeConnection)))) {
            restoreModelDefaults(incoming.models)
        } else if (modelsChanged && creating) modelSelection = modelSelection.available(incoming.models)
        if (incoming.connected) reconnectCount = 0
        if (wasConnected && !incoming.connected && !manualDisconnect) scheduleReconnect()
        if(incoming.connected && !incoming.loadingThread) {
            waitingAttachments.removeAll {pending->attachments.none {it.id==pending.id}}
            val ready=waitingAttachments.filter {it.connection==draftOwner() && it.thread==incoming.selectedThread?.id}
            waitingAttachments.removeAll(ready.toSet())
            ready.forEach {readAttachment(it.uri,it.id)}
        }
        if(refreshDiff || !incoming.connected)refreshWorkingDiff()
    }

    val bootstrap = BootstrapCoordinator(app, store) { saved, initialWorkspace ->
        reloadConnections()
        restoreModelDefaults(emptyList())
        workspace = initialWorkspace
        connect(saved)
    }

    init {
        reloadConnections()
        restoreModelDefaults(emptyList())
        draftKey="${draftOwner()}:new"
        draft=prefs.getString("draft:$draftKey","").orEmpty()
        runCatching { workspace = activeConnection?.let(store::workspaceFor).orEmpty() }.onFailure { message = "无法读取工作目录" }
        bootstrap.start()
    }

    fun reloadConnections() {
        try { connections = store.list();modelPreferences.migrate(connections); activeConnection = connections.firstOrNull { it.id == store.activeId };workspace=activeConnection?.let(store::workspaceFor).orEmpty();files.onOwnerChanged() }
        catch(e:Exception) { message = e.message ?: "读取连接失败" }
    }
    fun saveConnection(profile:ConnectionProfile, secret:SshCredential?, id:String?):Boolean {
        try { val saved=store.save(profile,secret,id); connections=store.list(); connect(saved,force=true); return true }
        catch(e:Exception) { message=e.message ?: "保存连接失败"; return false }
        finally { secret?.close() }
    }
    fun removeConnection(id:String) {
        try {
            persistDraft()
            if(activeConnection?.id==id) { manualDisconnect=true;connectAttempt++;repo.disconnect(clearSession=true);selectionId=null;attachments=emptyList();waitingAttachments.clear();stagedAsync=emptyMap() }
            store.remove(id);reloadConnections();home=true;creating=false;homeDraftTransfer=null
            selectedPermission = null
            draftKey="${draftOwner()}:new";draft=prefs.getString("draft:$draftKey","").orEmpty();restoreModelDefaults(emptyList())
        }
        catch(e:Exception) { message=e.message ?: "删除连接失败" }
    }
    fun connect(saved:SavedConnection?=activeConnection,force:Boolean=false,silent:Boolean=false) {
        if(saved==null) return
        val sameRecord=activeConnection?.id==saved.id
        val sameOwner=activeConnection?.ownerKey==saved.ownerKey
        if(!force && sameRecord && sameOwner && state.connection.status in setOf(ConnectionStatus.CONNECTING,ConnectionStatus.INITIALIZING)) {
            activeConnection=saved
            return
        }
        try {
            val secret=store.loadCredential(saved) ?: throw IllegalStateException("请先导入连接密钥或填写密码")
            try {
                val attempt=++connectAttempt
                reconnectTask?.let(main::removeCallbacks);reconnectTask=null;reconnectScheduled=false
                persistDraft(); store.setActive(saved.id)
                val switching=!sameRecord || !sameOwner
                if(switching) { manualDisconnect=true;repo.disconnect();selectionId=null;home=true;creating=false;homeDraftTransfer=null;attachments=emptyList();waitingAttachments.clear();stagedAsync=emptyMap();planMode=false;selectedPermission=null }
                else if(force) {manualDisconnect=true;repo.disconnect()}
                activeConnection=saved;files.onOwnerChanged();manualDisconnect=false;message=null
                workspace=store.workspaceFor(saved)
                if(switching) {draftKey="${draftOwner()}:new";draft=prefs.getString("draft:$draftKey","").orEmpty();restoreModelDefaults(emptyList())}
                repo.connect(saved.profile,secret,resetSession=switching).whenComplete { _,error -> if(attempt==connectAttempt && activeConnection?.ownerKey==saved.ownerKey && error!=null) {if(!silent)message=safeError(error);scheduleReconnect()} }
            } finally {secret.close()}
        } catch(e:Exception) { message=e.message ?: "连接失败" }
    }
    private fun scheduleReconnect() {
        if(manualDisconnect || !appVisible || reconnectScheduled || activeConnection==null) return
        if(reconnectCount>=4) { attachments=attachments.map {a->if(waitingAttachments.any {it.id==a.id})a.copy(loading=false,error="连接未恢复，请重新连接或移除此附件") else a};return }
        reconnectScheduled=true
        val delay=longArrayOf(2000,5000,10000,20000)[reconnectCount++]
        val task=Runnable {reconnectScheduled=false;reconnectTask=null;if(appVisible && !manualDisconnect && state.connection.status==ConnectionStatus.DISCONNECTED)connect(silent=true)}
        reconnectTask=task;main.postDelayed(task,delay)
    }
    fun foreground() {
        appVisible=true;reconnectTask?.let(main::removeCallbacks);reconnectTask=null;reconnectScheduled=false
        if(ComposerConnectionPolicy.shouldAutoConnect(state.connection.status,manualDisconnect,activeConnection!=null)) {reconnectCount=0;connect(silent=true)}
        else if(state.connected)refreshWorkingDiff()
    }
    fun background() {appVisible=false;reconnectTask?.let(main::removeCallbacks);reconnectTask=null;reconnectScheduled=false}
    fun newChat(focus: Boolean = false) = createChat(focus, sendDraft = false)

    private fun createChat(focus: Boolean, sendDraft: Boolean) {
        if (!state.connected) { message = "请先连接服务器"; return }
        if (creating) return
        if (!home) {
            restoreModelDefaults()
            selectedPermission = null
        }
        val owner = imageOwner()
        val generation = state.connection.generation
        val keepExistingEditorFocus = focus && home
        persistDraft()
        val transfer = if (home && (focus || sendDraft)) HomeDraftTransfer(owner, draftKey, keepExistingEditorFocus) else null
        homeDraftTransfer = transfer
        if (transfer == null) {
            attachments = emptyList()
            waitingAttachments.clear()
        } else homeDraftRevision++
        home = false
        creating = true
        val request = ++chatCreation
        repo.newThread(workspace.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() }, speed.serviceTier).whenComplete { result, error ->
            if (disposed || owner != imageOwner() || request != chatCreation || !creating) return@whenComplete
            creating = false
            val id = result?.optJSONObject("thread")?.optString("id")
            val sameGeneration = generation == state.connection.generation
            if (error != null || !sameGeneration || id == null || state.selectedThread?.id != id) {
                if (transfer != null) returnToHome(preserveAttachments = true)
                if (error != null && sameGeneration) message = safeError(error)
                return@whenComplete
            }
            if (sendDraft) send()
            // Home's focused editor retains its IME state during creation, including dismissal.
            else if (focus && !keepExistingEditorFocus) focusRequest++
        }
    }
    fun openThread(id:String) {
        val owner=imageOwner()
        val readAtOpen=state.readStates[id]
        if (home || selectionId != id) selectedPermission = null
        persistDraft();homeDraftTransfer=null;creating=false;attachments=emptyList();waitingAttachments.clear(); selectionId=null;home=false
        observe(repo.openThread(id)) {
            if (owner==imageOwner() && !home && state.selectedThread?.id==id) {
                readAtOpen?.takeIf {it.unread && state.readStates[id]==it}?.let { read ->
                    // Read only the revision actually opened. A conflict refreshes state; it is
                    // never retried blindly over a newer response from another client.
                    repo.updateThreadReadState(id,false,read.revision)
                }
            }
        }
    }
    fun showHome() = returnToHome(preserveAttachments = false)

    private fun returnToHome(preserveAttachments: Boolean) {
        persistDraft()
        homeDraftTransfer = null
        creating = false
        home = true
        restoreModelDefaults()
        draftKey = "${draftOwner()}:new"
        draft = prefs.getString("draft:$draftKey", "").orEmpty()
        if (!preserveAttachments) {
            selectedPermission = null
            attachments = emptyList()
            waitingAttachments.clear()
        }
        if (state.connected) observe(repo.refreshThreads())
    }
    private fun restoreModelDefaults(models:List<ModelInfo> = state.models) {
        modelSelection = modelPreferences.read(activeConnection).available(models)
    }
    private fun rememberModelSelection(selection:ModelSelection) {
        val connection=activeConnection ?: return
        try { modelPreferences.save(connection,selection);modelSelection=selection }
        catch(e:Exception) { message=e.message ?: "无法保存默认模型" }
    }
    fun selectModel(value:String) {
        if(state.models.none {it.model==value})return
        val selection = if (value == model) modelSelection else ModelSelection(value, effort)
        rememberModelSelection(selection.available(state.models))
    }
    fun selectEffort(value:String) {
        if(state.models.firstOrNull {it.model==model}?.efforts?.contains(value)!=true)return
        rememberModelSelection(modelSelection.copy(effort = value))
    }
    fun selectSpeed(value: ComposerSpeed) {
        if (value in speedTiers) rememberModelSelection(modelSelection.copy(speed = value))
    }
    private fun boundHomeDraft(): HomeDraftTransfer? =
        homeDraftTransfer?.takeIf { it.owner == imageOwner() && it.threadKey == draftKey }

    private fun savedDraftKey(): String = boundHomeDraft()?.homeKey ?: draftKey

    fun changeDraft(text: String) {
        if (draft == text) return
        draft = text
        if (savedDraftKey() == "${draftOwner()}:new") homeDraftRevision++
        persistDraft()
    }

    private fun persistDraft() {
        val key = "draft:${savedDraftKey()}"
        prefs.edit().apply { if (draft.isEmpty()) remove(key) else putString(key, draft) }.apply()
    }
    fun updateGrouping(value:ThreadGrouping) { grouping=value; prefs.edit().putString("grouping",value.name).apply() }
    private fun projectOwnerPrefix():String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(imageOwner().toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}+":"
    fun hiddenProjectPaths():Set<String> {
        val prefix=projectOwnerPrefix()
        return removedProjects.filter {it.startsWith(prefix)}.map {it.removePrefix(prefix)}.toSet()
    }
    fun removeProjectFromList(path:String,owner:String=imageOwner()) {
        if(owner!=imageOwner() || !path.startsWith('/') || path.contains('\u0000'))return
        removedProjects=removedProjects+(projectOwnerPrefix()+path)
        prefs.edit().putStringSet("removed-projects",removedProjects).apply()
        message="项目已从列表移除，聊天和服务器文件已保留"
    }
    fun restoreProjectToList(path:String,owner:String=imageOwner()) {
        if(owner!=imageOwner())return
        removedProjects=removedProjects-(projectOwnerPrefix()+path)
        prefs.edit().putStringSet("removed-projects",removedProjects).apply()
        message="项目已恢复"
    }
    fun updateWorkspace(path:String) {
        if(path.isNotBlank() && !path.startsWith('/')) { message="工作目录需要是服务器上的绝对路径"; return }
        val connection=activeConnection ?: run {message="请先选择服务器连接";return}
        try { store.setWorkspace(connection,path); workspace=path } catch(e:Exception) { message=e.message }
    }
    private fun pinKey(id:String) = "${draftOwner()}:$id"
    private fun threadSummary(id:String)=state.threads.firstOrNull {it.id==id} ?: state.selectedThread?.takeIf {it.id==id}
    fun isPinned(id:String):Boolean {
        val section=threadSummary(id)?.sectionId
        // Preserve old handset-only pins until an explicit operation. A custom server
        // section takes precedence; new pin operations always use the actual server.
        return if(section!=null)section==PINNED_SECTION_ID else pinned.contains(pinKey(id))
    }
    private fun clearLegacyPin(id:String) {
        val k=pinKey(id)
        if(k in pinned) { pinned=pinned-k; prefs.edit().putStringSet("pinned",pinned).apply() }
    }
    fun togglePin() { state.selectedThread?.id?.let { observe(toggleThreadPin(it)) } }

    private fun <T> threadMutation(owner:String,action:()->CompletableFuture<T>):CompletableFuture<T> {
        if(owner!=imageOwner() || !state.connected)
            return CompletableFuture<T>().apply {completeExceptionally(CodexException("连接已改变，请重新选择聊天。"))}
        if(threadActionsBusy)
            return CompletableFuture<T>().apply {completeExceptionally(CodexException("正在保存聊天设置，请稍后再试。"))}
        val token=++threadActionSequence
        threadActionsBusy=true
        val result=try {action()} catch(e:Exception) {CompletableFuture<T>().apply {completeExceptionally(e)}}
        return result.whenComplete { _,_ -> if(token==threadActionSequence)threadActionsBusy=false }
    }
    fun toggleThreadPin(id:String,owner:String=imageOwner()):CompletableFuture<JSONObject> {
        val destination=if(isPinned(id))null else PINNED_SECTION_ID
        return moveThreadToSection(id,destination,owner)
    }
    fun moveThreadToSection(id:String,sectionId:String?,owner:String=imageOwner()):CompletableFuture<JSONObject> =
        threadMutation(owner) {repo.moveThreadToSection(id,sectionId)}.thenApply { result ->
            if(owner==imageOwner())clearLegacyPin(id)
            result
        }
    fun renameThread(id:String,name:String,owner:String=imageOwner()):CompletableFuture<JSONObject> =
        threadMutation(owner) {repo.renameThread(id,name)}
    fun archiveThread(id:String,owner:String=imageOwner()):CompletableFuture<JSONObject> =
        threadMutation(owner) {repo.archiveThread(id)}.thenApply { result ->
            if(owner==imageOwner()) {
                clearLegacyPin(id)
                if(state.selectedThread?.id==id && !home)showHome()
            }
            result
        }
    fun deleteThread(id:String,owner:String=imageOwner()):CompletableFuture<JSONObject> =
        threadMutation(owner) {repo.deleteThread(id)}.thenApply { result ->
            if(owner==imageOwner())message="聊天已永久删除"
            result
        }
    fun prepareThreadDeletion(id:String,owner:String=imageOwner()):CompletableFuture<ThreadDeletionPlan> =
        threadMutation(owner) {repo.prepareThreadDeletion(id)}
    fun deleteThread(plan:ThreadDeletionPlan,owner:String=imageOwner()):CompletableFuture<JSONObject> =
        threadMutation(owner) {repo.deleteThread(plan)}.thenApply { result ->
            if(owner==imageOwner())message=if(plan.threads.size==1)"聊天已永久删除" else "已永久删除 ${plan.threads.size} 个聊天"
            result
        }
    fun markThreadUnread(id:String,owner:String=imageOwner()):CompletableFuture<JSONObject> = threadMutation(owner) {
        val ready=if(state.readStates[id]!=null)CompletableFuture.completedFuture(JSONObject()) else repo.refreshThreadReadState(id)
        ready.thenCompose {
            val read=state.readStates[id] ?: throw CodexException("无法读取这条聊天的已读状态，请刷新后重试。")
            if(owner!=imageOwner() || !state.connected)throw CodexException("连接已改变，请重新选择聊天。")
            repo.updateThreadReadState(id,true,read.revision)
        }
    }
    fun createSectionAndMove(id:String,name:String,owner:String=imageOwner()):CompletableFuture<JSONObject> = threadMutation(owner) {
        repo.createSection(name).thenCompose { result ->
            val sectionId=result.optJSONObject("section")?.optString("id")?.takeIf {it.isNotBlank()}
                ?: throw CodexException("分区已创建，但未能读取分区信息；请刷新分区列表。")
            if(owner!=imageOwner() || !state.connected)throw CodexException("分区已创建，连接已改变；请重新选择聊天并移动。")
            val moved=CompletableFuture<JSONObject>()
            repo.moveThreadToSection(id,sectionId).whenComplete { value,error ->
                if(error==null) { if(owner==imageOwner())clearLegacyPin(id);moved.complete(value) }
                else moved.completeExceptionally(SectionMovePendingException(sectionId))
            }
            moved
        }
    }
    fun dismissPlan(id:String) { dismissedPlans=dismissedPlans+pinKey(id); prefs.edit().putStringSet("dismissedPlans",dismissedPlans).apply() }
    fun planDismissed(id:String)=pinKey(id) in dismissedPlans

    fun send() {
        val ready=if(home)state.connected&&!state.sending else state.canSend
        if(!ready || !editorDraftReady || creating || attachments.any { it.loading || it.error!=null } || (draft.isBlank() && attachments.isEmpty())) return
        if ((home || state.activeTurnId == null) && !composerPermissionsReady()) return
        if (home) {
            createChat(focus = false, sendDraft = true)
            return
        }
        val sentText=draft; val sentAttachments=attachments
        val sentDraftKey = draftKey
        val transfer = boundHomeDraft()
        val sentHomeRevision = homeDraftRevision
        val id=state.selectedThread?.id
        val connection=activeConnection
        val startsNewTurn=state.activeTurnId==null
        observe(repo.sendMessage(sentText, sentAttachments.mapNotNull { it.input }, options())) {
            // Steering an existing turn cannot change its model; consume only an accepted turn/start.
            if(startsNewTurn)acceptedInitialModelChoice(connection)
            if (transfer != null) {
                if (homeDraftTransfer === transfer) homeDraftTransfer = null
                val homePref = "draft:${transfer.homeKey}"
                if (homeDraftRevision == sentHomeRevision && prefs.getString(homePref, "").orEmpty() == sentText) {
                    prefs.edit().remove(homePref).apply()
                    if (home && draftKey == transfer.homeKey) draft = ""
                }
            }
            if(state.selectedThread?.id==id && draftKey == sentDraftKey) {
                if(draft==sentText) changeDraft("") else if (transfer != null) persistDraft()
                attachments=attachments.filterNot { a -> sentAttachments.any { it.id==a.id } }
            }
        }
    }
    fun availablePermissions(): List<ShellPermission> = PermissionCapabilities.available(state, model).map(::shellPermission)

    private fun composerPermissionsReady():Boolean {
        val preset = effectivePermission
        if(preset==PermissionPreset.CUSTOM)return true
        // Older servers may not implement capability discovery. They still validate turn/start.
        if(selectedPermission==null && state.configRequirements==null)return true
        val reason=PermissionCapabilities.disabledReason(state,preset,model)
        if(reason!=null) {message=reason;return false}
        return true
    }
    private fun acceptedInitialModelChoice(connection:SavedConnection?) {
        if(connection!=null)runCatching {modelPreferences.initialChoiceUsed(connection)}.onFailure {message=it.message}
    }

    fun selectPermission(value: ShellPermission) {
        val preset = permissionPreset(value)
        PermissionCapabilities.disabledReason(state, preset, model)?.let { message=it; return }
        selectedPermission=preset
    }

    private fun permissionPreset(value: ShellPermission): PermissionPreset = when(value) {
        ShellPermission.Default -> PermissionPreset.DEFAULT
        ShellPermission.AutoReview -> PermissionPreset.AUTO_REVIEW
        ShellPermission.ReadOnly -> PermissionPreset.READ_ONLY
        ShellPermission.FullAccess -> PermissionPreset.FULL_ACCESS
        ShellPermission.Custom -> PermissionPreset.CUSTOM
    }

    private fun shellPermission(value: PermissionPreset): ShellPermission = when(value) {
        PermissionPreset.DEFAULT -> ShellPermission.Default
        PermissionPreset.AUTO_REVIEW -> ShellPermission.AutoReview
        PermissionPreset.READ_ONLY -> ShellPermission.ReadOnly
        PermissionPreset.FULL_ACCESS -> ShellPermission.FullAccess
        PermissionPreset.CUSTOM -> ShellPermission.Custom
    }

    private fun options():TurnOptions {
        return PermissionCapabilities.options(effectivePermission).copy(
            model=model.takeIf { it.isNotBlank() },effort=effort.takeIf { it.isNotBlank() && it!="null" },
            collaborationMode=if(planMode)"plan" else "default",
            serviceTier=speed.serviceTier)
    }
    fun answer(p:PendingRequest,result:JSONObject,feedback:String?=null) {
        val thread=p.threadId ?: state.selectedThread?.id
        observe(repo.answer(p.key,result)) {
            if(!feedback.isNullOrBlank() && state.selectedThread?.id==thread &&
                (state.activeTurnId != null || composerPermissionsReady())) {
                observe(repo.sendMessage(feedback, options = PermissionCapabilities.options(effectivePermission)))
            }
        }
    }
    fun implementPlan(id:String) = submitPlan(id,false) { selected -> repo.implementPlan(id,selected) }
    fun revisePlan(id:String,text:String) = submitPlan(id,true) { selected -> repo.revisePlan(id,text,selected) }
    private fun submitPlan(id:String,revising:Boolean,submit:(TurnOptions)->CompletableFuture<JSONObject>) {
        if(!state.canSend || !composerPermissionsReady())return
        val connection=activeConnection
        // All new-turn entrances use the visible composer settings. Only plan/default mode differs.
        observe(submit(options())) {
            acceptedInitialModelChoice(connection)
            dismissPlan(id)
            planMode=revising
        }
    }

    fun attach(uri:Uri) {
        if(activeConnection==null) { message="请先添加服务器连接"; return }
        val id=UUID.randomUUID().toString()
        attachments=attachments+DraftAttachment(id,"正在读取…")
        if(!state.connected || state.loadingThread) {
            waitingAttachments+=PendingAttachment(uri,draftOwner(),state.selectedThread?.id,id)
            attachments=attachments.map {if(it.id==id)it.copy(label="等待连接恢复…") else it}
            if(appVisible && state.connection.status==ConnectionStatus.DISCONNECTED)connect()
        } else readAttachment(uri,id)
    }
    private fun readAttachment(uri:Uri,id:String) {
        if(attachments.none {it.id==id})return
        val connection=draftOwner();val owner=imageOwner()
        val read=++attachmentReadSequence;attachmentReads[id]=read
        fun isCurrent()=connection==draftOwner() && owner==imageOwner() &&
            attachmentReads[id]==read && attachments.any {it.id==id}
        attachments=attachments.filterNot {it.id==id}+DraftAttachment(id,"正在读取…")
        io.execute {
            try {
                val resolver=getApplication<Application>().contentResolver
                var name="附件"; var size=0L
                resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
                    if(c.moveToFirst()) { name=c.getString(0) ?: name; size=c.getLong(1) }
                }
                require(size <= 20*1024*1024) { "附件超过 20 MB，请在服务器上直接选择文件" }
                val bytes=resolver.openInputStream(uri)?.use { input ->
                    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); var n:Int
                    while(input.read(buffer).also { n=it }!=-1) { out.write(buffer,0,n); require(out.size()<=20*1024*1024) { "附件超过 20 MB" } }; out.toByteArray()
                } ?: throw IllegalArgumentException("无法读取附件")
                val mimeType = resolver.getType(uri)
                // Some document providers report image files as application/octet-stream.
                val thumb = runCatching { ImagePipeline.decode(bytes, 320) }.getOrNull()
                val image = thumb != null || mimeType?.startsWith("image/", ignoreCase = true) == true
                // Other previewable formats (such as SVG) still go to Codex as files.
                val modelImage = thumb != null && ImagePipeline.extension(bytes) in setOf("png", "jpg", "gif", "webp")
                val preview = if (image && uri.scheme == "content") {
                    ImageSource(ImageSourceKind.LocalUri, uri.toString(), mimeType, "draft:$id", displayName = name)
                } else null
                main.post {
                    if(!isCurrent()) return@post
                    attachments = attachments.map {
                        if (it.id == id) it.copy(label = name, thumbnail = thumb, imagePreview = preview) else it
                    }
                    if(!state.connected || state.loadingThread) {
                        if(waitingAttachments.none {it.id==id})waitingAttachments+=PendingAttachment(uri,connection,state.selectedThread?.id,id)
                        attachments=attachments.map {if(it.id==id)it.copy(label="等待连接恢复…") else it}
                        return@post
                    }
                    repo.uploadAttachment(name,ByteArrayInputStream(bytes),bytes.size.toLong()).whenComplete { path,error ->
                        if(isCurrent()) {
                            if(image && error==null)images.seedRemote(owner,path,bytes)
                            attachments = attachments.map { attachment ->
                                when {
                                    attachment.id != id -> attachment
                                    error != null -> attachment.copy(loading = false, error = safeError(error))
                                    else -> attachment.copy(
                                        loading = false,
                                        input = if (modelImage) UserInputs.remoteImage(path) else UserInputs.remoteFile(name, path),
                                        imagePreview = if (image) ImageSource(ImageSourceKind.RemotePath, path, mimeType,
                                            "draft:$id:uploaded", displayName = name) else null,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch(e:Exception) { main.post { if(isCurrent())attachments=attachments.map { if(it.id==id)it.copy(loading=false,error=e.message ?: "读取失败") else it } } }
        }
    }
    fun removeAttachment(id:String) { attachmentReads.remove(id);waitingAttachments.removeAll {it.id==id};attachments=attachments.filterNot { it.id==id } }
    private fun workingDiffContext():String? {
        val thread=state.selectedThread ?: return null
        if(!state.connected || state.loadingThread || thread.cwd.isBlank())return null
        return "${imageOwner()}\u0000${state.connection.generation}\u0000${thread.id}\u0000${thread.cwd}"
    }
    private fun workingDiffOwner():String? {
        val thread=state.selectedThread ?: return null
        if(thread.cwd.isBlank())return null
        return "${imageOwner()}\u0000${thread.id}\u0000${thread.cwd}"
    }
    /** Debounced read-only snapshots; streaming text never starts a new Git read. */
    fun refreshWorkingDiff(immediate:Boolean=false) {
        diffRefreshTask?.let(main::removeCallbacks);diffRefreshTask=null
        val context=workingDiffContext()
        val owner=workingDiffOwner()
        val sequence=++diffSequence
        if(owner!=diffOwner) {diffOwner=owner;changedFiles=null}
        if(context==null || disposed)return
        val snapshot=state
        val task=Runnable {
            diffRefreshTask=null
            WorkingTreeDiffReader(repo::backgroundRead,isCurrent={
                !disposed && sequence==diffSequence && context==workingDiffContext()
            }).read(snapshot.selectedThread!!.cwd).whenComplete { result,error ->
                val fallback=snapshot.diff.takeIf {snapshot.diffTurnId!=null && snapshot.diffTurnId==snapshot.turns.lastOrNull()?.id}
                val patch=result?.patch ?: fallback
                val scope=if(result?.patch!=null)ChangedFilesScope.WorkingTree else ChangedFilesScope.CurrentTurn
                CompletableFuture.supplyAsync {
                    patch?.takeIf {it.length<=512*1024}?.let {parseChangedFiles(it,scope)}?.let {summary ->
                        if(result?.basis==WorkingTreeDiffBasis.Head)summary.copy(basisNote="相对上次提交") else summary
                    }
                }.whenComplete { summary,parseError -> main.post {
                    if(!disposed && sequence==diffSequence && context==workingDiffContext()) {
                        // An unavailable refresh must not keep yesterday's counts looking current.
                        changedFiles=if(parseError==null && ((error==null && result?.status!=WorkingTreeDiffStatus.UNAVAILABLE) || fallback!=null))summary else null
                    }
                } }
            }
        }
        diffRefreshTask=task
        main.postDelayed(task,if(immediate)0 else 650)
    }
    fun <T> observe(future:CompletableFuture<T>,done:(T)->Unit={}):CompletableFuture<T> {
        val owner=imageOwner()
        val generation=state.connection.generation
        future.whenComplete { value,error ->
            if(owner==imageOwner() && generation==state.connection.generation) {
                if(error!=null)message=safeError(error) else done(value)
            }
        }
        return future
    }
    private fun safeError(error:Throwable):String {
        val cause=generateSequence(error) { it.cause }.last()
        return (cause as? CodexException)?.safeMessage ?: cause.message?.take(220) ?: "操作未完成，请检查连接"
    }
    override fun onCleared() { disposed=true;diffSequence++;bootstrap.close();files.close();persistDraft(); manualDisconnect=true; subscription.close(); images.close(); repo.close(); io.shutdown(); main.removeCallbacksAndMessages(null) }
}
