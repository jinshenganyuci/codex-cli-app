package com.nanci.codexremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanci.codexremote.connection.*
import com.nanci.codexremote.core.*
import com.nanci.codexremote.ui.content.*
import com.nanci.codexremote.ui.shell.*
import com.nanci.codexremote.update.UpdateCoordinator
import com.nanci.codexremote.update.UpdateUi
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val vm:RemoteViewModel=viewModel()
            val updates:UpdateCoordinator=viewModel()
            val currentConnection by rememberUpdatedState(vm.activeConnection)
            LaunchedEffect(vm.activeConnection) { updates.onConnectionAvailable(vm.activeConnection) }
            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF0D0D0D),onPrimary=Color.White,
                secondary=Color(0xFF5D5D5D),background=Color(0xFFFAFAFA),surface=Color.White,
                onSurface=Color(0xFF0D0D0D),surfaceVariant=Color(0xFFF3F3F3))) {
                RemoteApp(vm)
                UpdateUi(updates)
            }
            DisposableEffect(this,vm,updates) {
                val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
                    if(event==androidx.lifecycle.Lifecycle.Event.ON_START)updates.onForeground(currentConnection)
                    if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)vm.foreground()
                    if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                        vm.background()
                        updates.onBackground(isChangingConfigurations)
                    }
                }
                lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
            }
        }
    }
}

private data class ThreadActionTarget(val id:String,val title:String,val owner:String)
private data class ProjectActionTarget(val path:String,val owner:String)

@Composable
private fun RemoteApp(vm:RemoteViewModel) {
    val context=LocalContext.current
    val installedVersion=remember(context) {runCatching {
        context.packageManager.getPackageInfo(context.packageName,0).versionName ?: BuildConfig.VERSION_NAME
    }.getOrDefault(BuildConfig.VERSION_NAME)}
    val keyboard=LocalSoftwareKeyboardController.current
    val state=vm.state
    var latestRequest by remember(state.selectedThread?.id) { mutableIntStateOf(0) }
    var route by remember { mutableStateOf("home") }
    var editing by remember { mutableStateOf<SavedConnection?>(null) }
    var planText by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var archive by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var delete by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var removeProject by remember {mutableStateOf<ProjectActionTarget?>(null)}
    var newSection by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var fullAccess by remember { mutableStateOf(false) }
    var workspaceOpen by remember { mutableStateOf(false) }
    var exportText by rememberSaveable { mutableStateOf("") }
    var cameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentOwner by rememberSaveable { mutableStateOf("") }
    var openedImage by remember {mutableStateOf<ImageSource?>(null)}
    var changedFilesOpen by remember(vm.imageOwner(),state.selectedThread?.id) {mutableStateOf(false)}
    val imageOwner=vm.imageOwner()
    val targetFor:(String)->ThreadActionTarget?={id ->
        (state.threads.firstOrNull {it.id==id} ?: state.selectedThread?.takeIf {it.id==id})?.let {
            ThreadActionTarget(id,it.title.ifBlank {it.preview.ifBlank {"新聊天"}},imageOwner)
        }
    }
    // A reconnect keeps the same image owner and cached previews. Replacing the
    // loader for each transport state resets every visible image to a placeholder.
    val imageLoader=remember(imageOwner) {vm.images.scoped(imageOwner)}
    LaunchedEffect(imageOwner,state.selectedThread?.id) {openedImage=null}
    LaunchedEffect(imageOwner) {removeProject=null}
    LaunchedEffect(imageOwner,state.connected,state.connection.generation) {rename=null;archive=null;delete=null;newSection=null}
    LaunchedEffect(state.deletedThreadIds) {
        if(state.selectedThread==null)planText=null
        if(rename?.id in state.deletedThreadIds)rename=null
        if(archive?.id in state.deletedThreadIds)archive=null
        if(delete?.id in state.deletedThreadIds)delete=null
        if(newSection?.id in state.deletedThreadIds)newSection=null
    }
    val openImage:(ImageSource)->Unit={openedImage=it}
    val snack=remember { SnackbarHostState() }
    val copy:(String)->Unit={ text -> (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("Codex",text)) }
    val openLink:(String)->Unit={ link ->
        if(link.startsWith("data:image/",true))openImage(ImageSource(ImageSourceKind.DataUri,link,cacheKey="link:${System.identityHashCode(link)}"))
        else if(link.startsWith('/') && link.substringAfterLast('.').lowercase() in setOf("png","jpg","jpeg","webp","gif","heic","heif"))
            openImage(ImageSource(ImageSourceKind.RemotePath,link,cacheKey=link))
        else if(link.startsWith('/'))vm.observe(vm.repo.readFile(link,8*1024*1024)) {vm.previewFile=link to it}
        else runCatching { val uri=Uri.parse(link); require(uri.scheme in listOf("https","http","mailto")); context.startActivity(Intent(Intent.ACTION_VIEW,uri)) }
                .onFailure { vm.message="无法打开此链接" }
    }
    val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if(uri!=null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) } ?: error("no stream") }
            .onFailure { vm.message="文件保存失败" }.onSuccess { vm.message="已保存" }
    }
    val download:(String)->Unit={ exportText=it; exportLauncher.launch("Codex-plan.md") }
    val fileExportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if(uri!=null)vm.exportFile?.let { bytes->vm.saveBytes(uri,bytes) }
        vm.exportFile=null
    }
    val imageExportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) {uri ->
        val export=vm.imageExport;vm.imageExport=null
        if(uri!=null && export!=null)vm.saveImage(uri,export.second,export.first)
    }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(vm::attach) }
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uris.forEach(vm::attach) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if(ok && attachmentOwner=="${vm.activeConnection?.id}:${state.selectedThread?.id}")cameraUriString?.let {vm.attach(Uri.parse(it))}
    }
    val attach:(AttachmentAction)->Unit={ type ->
        try { when(type) {
            AttachmentAction.Files->filePicker.launch(arrayOf("*/*"))
            AttachmentAction.Photos->photoPicker.launch("image/*")
            AttachmentAction.Camera->{ val file=File(context.cacheDir,"camera/photo-${System.currentTimeMillis()}.jpg");file.parentFile?.mkdirs();val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);cameraUriString=uri.toString();attachmentOwner="${vm.activeConnection?.id}:${state.selectedThread?.id}";camera.launch(uri) }
        } } catch(e:Exception) { vm.message="无法打开附件选择器" }
    }
    LaunchedEffect(vm.message) { vm.message?.let { text -> snack.showSnackbar(text);if(vm.message==text)vm.message=null } }
    BackHandler(route!="home" || !vm.home || planText!=null) {
        when {
            planText!=null->planText=null
            route!="home"->{
                if(route=="search" && state.connected)vm.observe(vm.repo.refreshThreads())
                route="home"
            }
            else->vm.showHome()
        }
    }
    val connect = { if(vm.activeConnection!=null)vm.connect() else { editing=null;route="connection" };Unit }
    val shell=ShellState(hostName=vm.activeConnection?.profile?.name ?: "本地 Codex",
        threadId=state.selectedThread?.id,
        title=state.selectedThread?.title?.ifBlank { "新聊天" } ?: "新聊天",
        subtitle=state.selectedThread?.cwd?.substringAfterLast('/') ?: vm.workspace.substringAfterLast('/'),
        connected=state.connected,connecting=state.connection.status in listOf(ConnectionStatus.CONNECTING,ConnectionStatus.INITIALIZING),
        threads=state.threads.map { ShellThread(it.id,it.title.ifBlank { it.preview.ifBlank { "新聊天" } },it.cwd,vm.isPinned(it.id),it.status=="active",it.sectionId,state.readStates[it.id]?.unread==true,readStateAvailable=state.readStates.containsKey(it.id)) },
        loading=state.loadingThreads, grouping=vm.grouping,pinned=state.selectedThread?.id?.let(vm::isPinned) ?: false,
        workspace=vm.workspace.substringAfterLast('/').ifBlank { "选择项目" },hasMore=state.nextThreadsCursor!=null,
        sections=state.sections.filterNot {it.isPinned}.map {ShellSection(it.id,it.name)},
        sectionThreadIds=state.sectionThreadIds,
        hiddenProjectPaths=vm.hiddenProjectPaths(),
        sectionsLoading=state.loadingSections,sectionsError=state.sectionsError,
        threadMenuAvailability=ThreadMenuAvailability(
            canTogglePin=state.connected&&!vm.threadActionsBusy&&state.sectionsSupported==true,
            canRename=state.connected&&!vm.threadActionsBusy,
            canMoveToSection=state.connected&&!vm.threadActionsBusy&&state.sectionsSupported==true,
            canMarkUnread=state.connected&&!vm.threadActionsBusy&&state.readStateSupported==true,
            canArchive=state.connected&&!vm.threadActionsBusy,
            canDelete=state.connected&&!vm.threadActionsBusy&&state.threadDeletionSupported==true,
            disabledReason=when { !state.connected->"连接服务器后可管理聊天";vm.threadActionsBusy->"正在保存…";else->null }))
    val actions=ShellActions(onThread=vm::openThread,onNewChat={ keyboard?.hide();vm.newChat() },
        onSearch={route="search"},onSettings={vm.reloadConnections();route="settings"},onConnect={editing=null;route="connection"},
        onHost={vm.reloadConnections();route="settings"},onWorkspace={workspaceOpen=true},onGroupingChanged=vm::updateGrouping,
        onProjectChat={path->vm.updateWorkspace(path);keyboard?.hide();vm.newChat()},onAll={if(state.connected)vm.observe(vm.repo.refreshThreads())},
        onBack=vm::showHome,onRename={state.selectedThread?.id?.let {rename=targetFor(it)}},onArchive={state.selectedThread?.id?.let {archive=targetFor(it)}},onTogglePin={state.selectedThread?.id?.let {vm.observe(vm.toggleThreadPin(it,imageOwner))}},
        onCopyId={state.selectedThread?.id?.let(copy)},onLoadMore={ vm.observe(vm.repo.refreshThreads(cursor=state.nextThreadsCursor)) },
        onDelete={id->delete=targetFor(id)},
        onRemoveProject={path->removeProject=ProjectActionTarget(path,imageOwner)},
        onRestoreProject={path->vm.restoreProjectToList(path,imageOwner)},
        threadMenu=ThreadMenuActions(
            onTogglePin={id->vm.observe(vm.toggleThreadPin(id,imageOwner))},
            onRename={id->rename=targetFor(id)},
            onMoveToSection={id,sectionId->vm.observe(vm.moveThreadToSection(id,sectionId,imageOwner))},
            onCreateSection={id->newSection=targetFor(id)},
            onMarkUnread={id->vm.observe(vm.markThreadUnread(id,imageOwner))},
            onCopyId={id->copy(id);vm.message="已复制对话串 ID"},
            onArchive={id->archive=targetFor(id)},
            onDelete={id->delete=targetFor(id)},
            onOpenSections={vm.repo.refreshSections()}))
    val activeRequests=state.pendingRequests.filter { it.threadId==null || it.threadId==state.selectedThread?.id }
    val pending=activeRequests.firstOrNull()
    val lastPlan=state.turns.lastOrNull()?.takeIf { it.status!="inProgress" }?.items?.lastOrNull { it.type=="plan" }
        ?.takeUnless { vm.planDismissed(it.id) }
    val planDecision=lastPlan?.takeIf { pending==null && state.activeTurnId==null && !vm.home }
    val availableModels=state.models.map { ShellModel(it.model,it.displayName,it.efforts,it.description) }
    val composer=ComposerState(text=vm.draft,enabled=!vm.creating&&vm.editorDraftReady&&
        (if(vm.home)state.connected&&!state.sending else state.canSend),
        editorEnabled=!state.sending&&vm.editorDraftReady,
        submitting=state.sending||vm.creating,running=state.activeTurnId!=null,planMode=vm.planMode,
        permission=vm.permission, permissions=vm.availablePermissions(), models=availableModels, modelId=vm.model,reasoning=vm.effort,
        attachments=vm.attachments.map { ShellAttachment(it.id,it.label,it.thumbnail,it.loading,it.error) },
        focusRequest=vm.focusRequest,allowCamera=state.connected&&!vm.creating&&!state.loadingThread,allowFiles=state.connected&&!vm.creating&&!state.loadingThread,allowPhotos=state.connected&&!vm.creating&&!state.loadingThread,
        supportsFast=state.models.firstOrNull {it.model==vm.model}?.raw?.optJSONArray("additionalSpeedTiers")?.let { a->(0 until a.length()).any {a.optString(it)=="fast"} }==true,fast=vm.fast)
    val composerActions=ComposerActions(onTextChange=vm::changeDraft,onSend={latestRequest++;keyboard?.hide();vm.send()},
        onStop={vm.observe(vm.repo.interrupt())},onPlanMode={vm.planMode=it},onPermission={ if(it==ShellPermission.FullAccess)fullAccess=true else vm.selectPermission(it) },
        onModel={vm.model=it;vm.effort=state.models.firstOrNull { m->m.model==it }?.defaultEffort.orEmpty()},onReasoning={vm.effort=it},
        onAttachment=attach,onRemoveAttachment=vm::removeAttachment,onFast={vm.fast=it},
        onFocusRequestHandled={request->if(vm.focusRequest==request)vm.focusRequest=0},
        onFocus={ if(vm.home&&state.connected)vm.newChat(true) })

    Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        when {
            planText!=null->PlanReader(planText!!,onClose={planText=null},onCopy=copy,onDownload=download)
            route=="connection"->ConnectionScreen(saved=editing,connected=state.connected&&editing?.id==vm.activeConnection?.id,
                onSave={profile,secret->if(vm.saveConnection(profile,secret,editing?.id))route="home"},onCancel={route="settings"},
                onDelete=editing?.let { saved->{vm.removeConnection(saved.id);route="settings"} })
            route=="settings"->SettingsScreen(connections=vm.connections,activeId=vm.activeConnection?.id,workspace=vm.workspace,
                version=installedVersion,
                onBack={route="home"},onAdd={editing=null;route="connection"},onEdit={editing=it;route="connection"},
                onSelect={vm.connect(it);route="home"},onWorkspaceChange=vm::updateWorkspace)
            route=="search"->SearchPage(vm,onBack={route="home"},onOpen={id->route="home";vm.openThread(id)})
            else->Column(Modifier.fillMaxSize()) {
                if(!vm.home && pending!=null)Spacer(Modifier.height(68.dp))
                Box(Modifier.weight(1f)) {
                    if(vm.home)key(imageOwner) {HomeScreen(shell,actions,Modifier.fillMaxSize(),bottomPadding=16.dp)}
                    else {
                        ChatTimeline(vm,latestRequest=latestRequest,onCopy=copy,onPlanOpen={planText=it},onDownload=download,onOpenLink=openLink,
                            imageLoader=imageLoader,onOpenImage=openImage)
                    }
                    if(!state.connected && !vm.home) {
                        TextButton(onClick=connect,enabled=!shell.connecting,modifier=Modifier.align(Alignment.BottomCenter)) {
                            Text(when(state.connection.status) {
                                ConnectionStatus.CONNECTING -> "正在重新连接…"
                                ConnectionStatus.INITIALIZING -> "正在恢复会话…"
                                else -> "连接已断开，点按重连"
                            })
                        }
                    }
                }
                if(!vm.home && pending!=null) {
                    Column(Modifier.padding(horizontal=16.dp).padding(bottom=8.dp)) {
                        if(activeRequests.size>1)Text("${activeRequests.size} 个请求等待处理",fontSize=12.sp,color=Color(0xFF5D5D5D),modifier=Modifier.padding(bottom=6.dp))
                        key(pending.key) {
                            when(pending.kind) {
                                RequestKind.USER_INPUT -> ToolUserInputCard(pending.params,!pending.submitting&&state.connected,{vm.answer(pending,it)})
                                RequestKind.ELICITATION -> ElicitationCard(pending.params,!pending.submitting&&state.connected,{vm.answer(pending,it)},openLink)
                                RequestKind.COMMAND_APPROVAL,RequestKind.FILE_APPROVAL,RequestKind.PERMISSIONS_APPROVAL ->
                                    ApprovalCard(pending.method,pending.params,!pending.submitting&&state.connected,{result,feedback->vm.answer(pending,result,feedback)})
                                RequestKind.UNSUPPORTED -> Surface(shape=RoundedCornerShape(16.dp)) { Text("此请求需要在服务器上的 Codex 客户端处理。",Modifier.padding(16.dp)) }
                            }
                        }
                    }
                } else if(planDecision!=null) {
                    PlanDecisionCard(enabled=state.connected,onImplement={vm.implementPlan(planDecision.id)},
                        onRevise={vm.revisePlan(planDecision.id,it)},onSkip={vm.dismissPlan(planDecision.id)},modifier=Modifier.padding(horizontal=16.dp).padding(bottom=8.dp))
                } else {
                    if(!vm.home)vm.currentAsync?.let { question ->
                        key(question.key) {
                            AsyncQuestionCard(vm.asyncParams(question),enabled=state.connected&&!state.sending,
                                onClose=vm::closeAsync,onSkip={vm.answerAsync("")},
                                onSubmit=vm::answerAsync,modifier=Modifier.padding(horizontal=16.dp))
                        }
                    }
                    if(!vm.home && state.items.isEmpty() && !state.loadingThread)NewChatContext(shell,actions,Modifier.padding(horizontal=20.dp))
                    if(!vm.home)vm.changedFiles?.let {summary ->
                        Box(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                            ChangedFilesChip(summary,onClick={keyboard?.hide();changedFilesOpen=true;vm.refreshWorkingDiff(immediate=true)})
                        }
                    }
                    Composer(composer,composerActions,Modifier.fillMaxWidth().padding(top=8.dp,bottom=12.dp))
                }
            }
        }
        if(route=="home" && !vm.home && planText==null) {
            if(state.items.isEmpty()&&!state.loadingThread)NewChatChrome(actions,Modifier.align(Alignment.TopCenter))
            else ChatChrome(shell,actions,Modifier.align(Alignment.TopCenter))
        }
        SnackbarHost(snack,Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    rename?.let {target -> key(target) {ThreadNameDialog("重命名聊天",target.title,"保存",onDismiss={rename=null},onSave={vm.renameThread(target.id,it,target.owner)})} }
    newSection?.let {target -> key(target) {
        var createdSectionId by remember {mutableStateOf<String?>(null)}
        ThreadNameDialog(if(createdSectionId==null)"新建分区" else "移动聊天","",if(createdSectionId==null)"创建" else "重试移动",
            fieldEnabled=createdSectionId==null,onDismiss={newSection=null},onSave={name ->
                val existingId=createdSectionId
                if(existingId!=null)vm.moveThreadToSection(target.id,existingId,target.owner)
                else vm.createSectionAndMove(target.id,name,target.owner).whenComplete {_,error ->
                    if(newSection==target && error!=null) {
                        (generateSequence(error) {it.cause}.last() as? SectionMovePendingException)?.let {createdSectionId=it.sectionId}
                    }
                }
            })
    } }
    archive?.let {target -> key(target) {
        var saving by remember {mutableStateOf(false)}
        var failure by remember {mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={if(!saving)archive=null},containerColor=Color.White,shape=RoundedCornerShape(28.dp),
            title={Text("归档聊天？")},text={Column {Text("“${target.title}”会保留在服务器中，可在搜索中查看已归档聊天。");failure?.let {Text(it,color=Color(0xFFB42318),modifier=Modifier.padding(top=12.dp))}}},
            confirmButton={TextButton(enabled=!saving,onClick={saving=true;failure=null;vm.archiveThread(target.id,target.owner).whenComplete {_,error->
                if(archive==target) {saving=false;if(error==null)archive=null else failure=threadActionError(error)}
            }}){Text(if(saving)"正在归档…" else "归档",color=Color(0xFFE02D3C))}},
            dismissButton={TextButton(enabled=!saving,onClick={archive=null}){Text("取消")}})
    } }
    delete?.let {target -> key(target) {
        var deleting by remember {mutableStateOf(false)}
        var failure by remember {mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={if(!deleting)delete=null},containerColor=Color.White,shape=RoundedCornerShape(28.dp),
            title={Text("永久删除聊天？")},
            text={Column {
                Text("将永久删除“${target.title}”及其子代理聊天记录，删除后无法恢复。项目文件不受影响。")
                failure?.let {Text(it,color=Color(0xFFB42318),modifier=Modifier.padding(top=12.dp))}
            }},
            confirmButton={TextButton(enabled=!deleting,onClick={
                deleting=true;failure=null
                vm.deleteThread(target.id,target.owner).whenComplete {_,error->
                    if(delete==target) {deleting=false;if(error==null)delete=null else failure=threadActionError(error)}
                }
            }){Text(if(deleting)"正在删除…" else "永久删除",color=Color(0xFFE02D3C))}},
            dismissButton={TextButton(enabled=!deleting,onClick={delete=null}){Text("取消")}})
    } }
    removeProject?.let {target ->
        AlertDialog(onDismissRequest={removeProject=null},containerColor=Color.White,shape=RoundedCornerShape(28.dp),
            title={Text("从项目列表移除？")},
            text={Text("将从这台手机的项目列表移除“${target.path.substringAfterLast('/').ifBlank {target.path}}”。聊天和服务器文件会保留，可在“已移除的项目”中恢复。")},
            confirmButton={TextButton(onClick={vm.removeProjectFromList(target.path,target.owner);removeProject=null}){Text("移除",color=Color(0xFFE02D3C))}},
            dismissButton={TextButton(onClick={removeProject=null}){Text("取消")}})
    }
    if(fullAccess)AlertDialog(onDismissRequest={fullAccess=false},title={Text("允许完全访问？")},text={Text("Codex 将能够修改此服务器上的文件并执行命令，无需逐次审批。")},
        confirmButton={TextButton(onClick={vm.selectPermission(ShellPermission.FullAccess);fullAccess=false}){Text("允许")}},dismissButton={TextButton(onClick={fullAccess=false}){Text("取消")}})
    if(workspaceOpen)WorkspacePicker(vm,onClose={workspaceOpen=false})
    vm.previewFile?.let { (path,bytes)->RemoteFilePreview(path,bytes,onClose={vm.previewFile=null},onDownload={vm.exportFile=bytes;fileExportLauncher.launch(path.substringAfterLast('/'))}) }
    openedImage?.let {source -> ImageViewer(source,imageLoader,onClose={openedImage=null},onSave={
        vm.imageExport=imageOwner to source;imageExportLauncher.launch(imageFileName(source))
    })}
    if(changedFilesOpen)vm.changedFiles?.let {summary ->
        ChangedFilesSheet(summary,onDismiss={changedFilesOpen=false},onCopy={copy(it);vm.message="已复制差异"})
    }
}

/** Shared form keeps a failed save visible instead of dismissing as if it succeeded. */
@Composable
private fun ThreadNameDialog(title:String,initial:String,confirm:String,fieldEnabled:Boolean=true,onDismiss:()->Unit,onSave:(String)->CompletableFuture<JSONObject>) {
    var text by remember {mutableStateOf(initial)}
    var saving by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf<String?>(null)}
    var alive by remember {mutableStateOf(true)}
    DisposableEffect(Unit) {onDispose {alive=false}}
    AlertDialog(onDismissRequest={if(!saving)onDismiss()},containerColor=Color.White,shape=RoundedCornerShape(28.dp),
        title={Text(title)},text={Column {
            OutlinedTextField(text,{text=it;failure=null},enabled=!saving&&fieldEnabled,singleLine=true)
            failure?.let {Text(it,color=Color(0xFFB42318),modifier=Modifier.padding(top=12.dp))}
        }},confirmButton={TextButton(enabled=!saving&&text.isNotBlank(),onClick={
            saving=true;failure=null
            onSave(text.trim()).whenComplete {_,error->if(alive) {saving=false;if(error==null)onDismiss() else failure=threadActionError(error)}}
        }){Text(if(saving)"正在保存…" else confirm)}},dismissButton={TextButton(enabled=!saving,onClick=onDismiss){Text("取消")}})
}

private fun threadActionError(error:Throwable):String = when(val cause=generateSequence(error) {it.cause}.last()) {
    is CodexException -> cause.safeMessage
    is SectionMovePendingException -> cause.message.orEmpty()
    else -> "操作未完成，请检查连接后重试。"
}

private class DecodedFile(val bitmap:android.graphics.Bitmap?,val text:String?)
@Composable
private fun RemoteFilePreview(path:String,bytes:ByteArray,onClose:()->Unit,onDownload:()->Unit) {
    val decoded by produceState<DecodedFile?>(null,bytes) {
        value=withContext(Dispatchers.IO) {
            val bitmap=runCatching {ImagePipeline.decode(bytes,2048)}.getOrNull()
            val text=if(bitmap==null)runCatching {Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes,0,minOf(bytes.size,48*1024))).toString() +
                (if(bytes.size>48*1024)"\n\n仅预览前 48 KB，保存可查看完整文件。" else "")}.getOrNull() else null
            DecodedFile(bitmap,text)
        }
    }
    val bitmap=decoded?.bitmap
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onClose){Text("关闭")};Text(path.substringAfterLast('/'),Modifier.weight(1f),maxLines=1,fontSize=14.sp);TextButton(onClick=onDownload){Text("保存")}
            }
            if(decoded==null)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
            else if(bitmap!=null) {
                var scale by remember {mutableFloatStateOf(1f)}
                val transform=rememberTransformableState { zoom,_,_->scale=(scale*zoom).coerceIn(1f,5f) }
                Image(bitmap.asImageBitmap(),path,Modifier.fillMaxSize().transformable(transform).graphicsLayer(scaleX=scale,scaleY=scale))
            } else {
                val text=decoded?.text
                if(text!=null)androidx.compose.foundation.text.selection.SelectionContainer {Text(text,Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),fontSize=13.sp,lineHeight=20.sp)}
                else Text("此文件无法直接预览，可以保存后打开。",Modifier.padding(20.dp))
            }
        }
    }
}

@Composable
private fun ChatTimeline(vm:RemoteViewModel,latestRequest:Int,onCopy:(String)->Unit,onPlanOpen:(String)->Unit,onDownload:(String)->Unit,onOpenLink:(String)->Unit,
    imageLoader:ImageLoader,onOpenImage:(ImageSource)->Unit) {
    ChatTimelineContent(state=vm.state,creating=vm.creating,latestRequest=latestRequest,
        onLoadEarlier={vm.observe(vm.repo.loadEarlierTurns())},onCopy=onCopy,onPlanOpen=onPlanOpen,
        onDownload=onDownload,onOpenLink=onOpenLink,imageLoader=imageLoader,onOpenImage=onOpenImage,onCopyImage=vm::copyImage)
}

@Composable
private fun SearchPage(vm:RemoteViewModel,onBack:()->Unit,onOpen:(String)->Unit) {
    var query by remember { mutableStateOf("") }; var archived by remember { mutableStateOf(false) }
    LaunchedEffect(query,archived) { kotlinx.coroutines.delay(300); if(vm.state.connected)vm.observe(vm.repo.refreshThreads(search=query,archived=archived)) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { TextButton(onClick={vm.observe(vm.repo.refreshThreads());onBack()}){Text("返回")};OutlinedTextField(query,{query=it},placeholder={Text("搜索聊天")},singleLine=true,modifier=Modifier.weight(1f),shape=RoundedCornerShape(26.dp)) }
        Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(archived,{archived=it});Text("已归档聊天",fontSize=14.sp) }
        LazyColumn { items(vm.state.threads,key={it.id}) { thread ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(thread.id) }.padding(vertical=16.dp)) {
                Text(thread.title.ifBlank { thread.preview.ifBlank { "新聊天" } },fontSize=17.sp,maxLines=2)
                Text(thread.cwd,fontSize=12.sp,color=Color(0xFF5D5D5D),maxLines=1)
                if(archived)TextButton(onClick={vm.observe(vm.repo.archiveThread(thread.id,false)) {vm.observe(vm.repo.refreshThreads(search=query,archived=true))}}){Text("取消归档")}
            }
        }; if(vm.state.nextThreadsCursor!=null)item { TextButton(onClick={vm.observe(vm.repo.refreshThreads(vm.state.nextThreadsCursor,query,archived))}){Text("加载更多")}} }
    }
}

@Composable
private fun WorkspacePicker(vm:RemoteViewModel,onClose:()->Unit) {
    var path by remember { mutableStateOf(vm.workspace.ifBlank { vm.state.selectedThread?.cwd ?: "/home/codex" }) }
    var entries by remember { mutableStateOf(emptyList<RemoteEntry>()) };var loading by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    fun load(value:String) { loading=true;error=null;vm.repo.listDirectory(value).whenComplete { list,e -> loading=false;if(e!=null)error="无法读取此目录" else {path=value;entries=list.filter { it.directory }} } }
    LaunchedEffect(Unit) {load(path)}
    AlertDialog(onDismissRequest=onClose,title={Text("选择项目目录")},text={Column {
        OutlinedTextField(path,{path=it},singleLine=true,label={Text("服务器目录")})
        Row { TextButton(onClick={load(path.substringBeforeLast('/').ifBlank { "/" })}){Text("上一级")};TextButton(onClick={load(path)}){Text("打开")}}
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Text(it,color=Color(0xFFB42318))}
        LazyColumn(Modifier.heightIn(max=280.dp)) { items(entries,key={it.path}) { entry -> Text(entry.name,Modifier.fillMaxWidth().clickable {load(entry.path)}.padding(vertical=12.dp),fontSize=16.sp) } }
    }},confirmButton={TextButton(onClick={vm.updateWorkspace(path);onClose()},enabled=path.startsWith('/')){Text("选择此目录")}},dismissButton={TextButton(onClick=onClose){Text("取消")}})
}
