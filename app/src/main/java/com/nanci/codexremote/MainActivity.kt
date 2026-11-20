package com.nanci.codexremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nanci.codexremote.ui.theme.*
import com.nanci.codexremote.connection.*
import com.nanci.codexremote.core.*
import com.nanci.codexremote.ui.content.*
import com.nanci.codexremote.ui.shell.*
import com.nanci.codexremote.update.UpdateCoordinator
import com.nanci.codexremote.update.UpdateUi
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        val appearance = AppearancePreferences(this)
        applyColorModeToSystemBars(appearance.read())
        setContent {
            var colorMode by remember { mutableStateOf(appearance.read()) }
            SideEffect { applyColorModeToSystemBars(colorMode) }
            val vm:RemoteViewModel=viewModel()
            val updates:UpdateCoordinator=viewModel()
            val currentConnection by rememberUpdatedState(vm.activeConnection)
            LaunchedEffect(vm.activeConnection) { updates.onConnectionAvailable(vm.activeConnection) }
            NativeAppTheme(darkTheme = colorMode == AppColorMode.Dark) {
                RemoteApp(vm, colorMode) { selected ->
                    appearance.save(selected)
                    colorMode = selected
                }
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

    private fun applyColorModeToSystemBars(mode: AppColorMode) {
        val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) {
            mode == AppColorMode.Dark
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

private data class ThreadActionTarget(val id:String,val title:String,val owner:String)
private data class ProjectActionTarget(val path:String,val owner:String)

@Composable
private fun RemoteApp(vm:RemoteViewModel, colorMode:AppColorMode, onColorModeChange:(AppColorMode)->Unit) {
    val context=LocalContext.current
    val installedVersion=remember(context) {runCatching {
        context.packageManager.getPackageInfo(context.packageName,0).versionName ?: BuildConfig.VERSION_NAME
    }.getOrDefault(BuildConfig.VERSION_NAME)}
    val keyboard=LocalSoftwareKeyboardController.current
    val state=vm.state
    val density=LocalDensity.current
    var chromeHeight by remember {mutableStateOf(72.dp)}
    var latestRequest by remember(state.selectedThread?.id) { mutableIntStateOf(0) }
    var route by remember { mutableStateOf("home") }
    var editing by remember { mutableStateOf<SavedConnection?>(null) }
    BootstrapUi(vm.bootstrap) { editing = null; route = "connection" }
    var planText by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var archive by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var delete by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var removeProject by remember {mutableStateOf<ProjectActionTarget?>(null)}
    var newSection by remember { mutableStateOf<ThreadActionTarget?>(null) }
    var fullAccess by remember { mutableStateOf(false) }
    var workspaceOpen by remember { mutableStateOf(false) }
    var exportText by rememberSaveable { mutableStateOf("") }
    var fileExportId by rememberSaveable { mutableStateOf<Long?>(null) }
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
    // Revalidate mutable remote paths on foreground and completed turns, without
    // rebuilding the loader for every streamed text token or transport notification.
    var imageRefresh by remember(imageOwner,state.selectedThread?.id) {mutableLongStateOf(0L)}
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,imageOwner,state.selectedThread?.id) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)imageRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    val completedImageTurn=state.turns.lastOrNull {it.status!="inProgress"}?.let {it.id to it.status}
    val imageLoader=remember(imageOwner,state.selectedThread?.id,completedImageTurn,imageRefresh) {vm.images.scoped(imageOwner)}
    LaunchedEffect(imageOwner,state.selectedThread?.id) {openedImage=null}
    LaunchedEffect(imageOwner) {removeProject=null;workspaceOpen=false;planText=null}
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
        val source=resolveMarkdownImageSource(link,"link:${System.identityHashCode(link)}",state.selectedThread?.cwd ?: vm.workspace)
        val imagePath=source?.let { if(it.kind==ImageSourceKind.HttpUrl) Uri.parse(it.value).path.orEmpty() else it.value }.orEmpty()
        val isImage=source?.kind==ImageSourceKind.DataUri || hasImageFileExtension(imagePath)
        if(source!=null && isImage)openImage(source)
        else if(source?.kind==ImageSourceKind.RemotePath)vm.files.open(source.value)
        else runCatching { val uri=Uri.parse(link); require(uri.scheme in listOf("https","http","mailto")); context.startActivity(Intent(Intent.ACTION_VIEW,uri)) }
                .onFailure { vm.message="无法打开此链接" }
    }
    val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if(uri!=null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) } ?: error("no stream") }
            .onFailure { vm.message="文件保存失败" }.onSuccess { vm.message="已保存" }
    }
    val download:(String)->Unit={ exportText=it; exportLauncher.launch("Codex-plan.md") }
    val fileExportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.files.completeDocumentSave(fileExportId,result.data?.data.takeIf { result.resultCode == android.app.Activity.RESULT_OK })
        fileExportId=null
    }
    val imageExportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) {uri ->
        val export=vm.imageExport;vm.imageExport=null
        if(uri!=null && export!=null)vm.saveImage(uri,export.second,export.first)
    }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(attachmentOwner=="${vm.imageOwner()}:${vm.state.selectedThread?.id}")uris.forEach(vm::attach) }
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if(attachmentOwner=="${vm.imageOwner()}:${vm.state.selectedThread?.id}")uris.forEach(vm::attach) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if(ok && attachmentOwner=="${vm.imageOwner()}:${vm.state.selectedThread?.id}")cameraUriString?.let {vm.attach(Uri.parse(it))}
    }
    val attach:(AttachmentAction)->Unit={ type ->
        attachmentOwner="${vm.imageOwner()}:${vm.state.selectedThread?.id}"
        try { when(type) {
            AttachmentAction.Files->filePicker.launch(arrayOf("*/*"))
            AttachmentAction.Photos->photoPicker.launch("image/*")
            AttachmentAction.Camera->{ val file=File(context.cacheDir,"camera/photo-${System.currentTimeMillis()}.jpg");file.parentFile?.mkdirs();val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);cameraUriString=uri.toString();attachmentOwner="${vm.imageOwner()}:${vm.state.selectedThread?.id}";camera.launch(uri) }
        } } catch(e:Exception) { vm.message="无法打开附件选择器" }
    }
    LaunchedEffect(vm.message) { vm.message?.let { text -> snack.showSnackbar(text);if(vm.message==text)vm.message=null } }
    val fileNotice=vm.files.notices.firstOrNull()
    LaunchedEffect(fileNotice?.id) {
        fileNotice?.let { notice ->
            val result=snack.showSnackbar(if(notice.file.savedToDownloads)"已保存到“下载”文件夹" else "已保存",actionLabel="打开",duration=SnackbarDuration.Long)
            if(result==SnackbarResult.ActionPerformed)vm.files.openSaved(context,notice.file)
            vm.files.consumeNotice(notice.id)
        }
    }
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
        attachments=vm.attachments.map { ShellAttachment(it.id,it.label,it.thumbnail,it.loading,it.error,it.imagePreview) },
        focusRequest=vm.focusRequest,allowCamera=state.connected&&!vm.creating&&!state.loadingThread,allowFiles=state.connected&&!vm.creating&&!state.loadingThread,allowPhotos=state.connected&&!vm.creating&&!state.loadingThread,
        speedTiers=vm.speedTiers,speed=vm.speed)
    val composerActions=ComposerActions(onTextChange=vm::changeDraft,onSend={latestRequest++;keyboard?.hide();vm.send()},
        onStop={vm.observe(vm.repo.interrupt())},onPlanMode={vm.planMode=it},onPermission={ if(it==ShellPermission.FullAccess)fullAccess=true else vm.selectPermission(it) },
        onModel=vm::selectModel,onReasoning=vm::selectEffort,
        onAttachment=attach,onRemoveAttachment=vm::removeAttachment,onSpeed=vm::selectSpeed,onPreviewAttachment=openImage,
        onFocusRequestHandled={request->if(vm.focusRequest==request)vm.focusRequest=0},
        onFocus={ if(vm.home&&state.connected)vm.newChat(true) })

    Box(Modifier.fillMaxSize().background(NativeTheme.colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        when {
            planText!=null->PlanReader(planText!!,onClose={planText=null},onCopy=copy,onDownload=download,
                onOpenLink=openLink,imageLoader=imageLoader,onOpenImage=openImage,
                imageBaseDirectory=state.selectedThread?.cwd ?: vm.workspace,imageKey="plan-reader:${state.selectedThread?.id}")
            route=="connection"->ConnectionScreen(saved=editing,connected=state.connected&&editing?.id==vm.activeConnection?.id,
                onSave={profile,secret->if(vm.saveConnection(profile,secret,editing?.id))route="home"},onCancel={route="settings"},
                onDelete=editing?.let { saved->{vm.removeConnection(saved.id);route="settings"} })
            route=="settings"->SettingsScreen(connections=vm.connections,activeId=vm.activeConnection?.id,workspace=vm.workspace,
                version=installedVersion,colorMode=colorMode,onColorModeChange=onColorModeChange,
                onBack={route="home"},onAdd={editing=null;route="connection"},onEdit={editing=it;route="connection"},
                onSelect={vm.connect(it);route="home"},onWorkspaceChange=vm::updateWorkspace)
            route=="search"->SearchPage(vm,onBack={route="home"},onOpen={id->route="home";vm.openThread(id)})
            else->Column(Modifier.fillMaxSize()) {
                if(!vm.home && pending!=null)Spacer(Modifier.height(68.dp))
                Box(Modifier.weight(1f)) {
                    if(vm.home)key(imageOwner) {HomeScreen(shell,actions,Modifier.fillMaxSize(),bottomPadding=16.dp)}
                    else {
                        ChatTimeline(vm,latestRequest=latestRequest,onCopy=copy,onPlanOpen={planText=it},onDownload=download,onOpenLink=openLink,
                            imageLoader=imageLoader,onOpenImage=openImage,topInset=chromeHeight+12.dp)
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
                        if(activeRequests.size>1)Text("${activeRequests.size} 个请求等待处理",fontSize=12.sp,color=NativeTheme.colors.muted,modifier=Modifier.padding(bottom=6.dp))
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
                    PlanDecisionCard(planId="$imageOwner:${state.selectedThread?.id}:${planDecision.id}",enabled=state.connected,onImplement={vm.implementPlan(planDecision.id)},
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
            else ChatChrome(shell,actions,Modifier.align(Alignment.TopCenter).onSizeChanged {chromeHeight=with(density){it.height.toDp()}})
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
        AlertDialog(onDismissRequest={if(!saving)archive=null},containerColor=NativeTheme.colors.surface,shape=RoundedCornerShape(28.dp),
            title={Text("归档聊天？")},text={Column {Text("“${target.title}”会保留在服务器中，可在搜索中查看已归档聊天。");failure?.let {Text(it,color=NativeTheme.colors.danger,modifier=Modifier.padding(top=12.dp))}}},
            confirmButton={TextButton(enabled=!saving,onClick={saving=true;failure=null;vm.archiveThread(target.id,target.owner).whenComplete {_,error->
                if(archive==target) {saving=false;if(error==null)archive=null else failure=threadActionError(error)}
            }}){Text(if(saving)"正在归档…" else "归档",color=NativeTheme.colors.danger)}},
            dismissButton={TextButton(enabled=!saving,onClick={archive=null}){Text("取消")}})
    } }
    delete?.let {target -> key(target) {
        var deleting by remember {mutableStateOf(false)}
        var checking by remember {mutableStateOf(true)}
        var loadAttempt by remember {mutableIntStateOf(0)}
        var deletionPlan by remember {mutableStateOf<ThreadDeletionPlan?>(null)}
        var failure by remember {mutableStateOf<String?>(null)}
        LaunchedEffect(loadAttempt) {
            checking=true;failure=null;deletionPlan=null
            vm.prepareThreadDeletion(target.id,target.owner).whenComplete {plan,error ->
                if(delete==target) {
                    checking=false
                    if(error==null)deletionPlan=plan else failure=threadActionError(error)
                }
            }
        }
        val related=deletionPlan?.threads.orEmpty().filter {it.id!=target.id}
        AlertDialog(onDismissRequest={if(!deleting)delete=null},containerColor=NativeTheme.colors.surface,shape=RoundedCornerShape(28.dp),
            title={Text("永久删除聊天？")},
            text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                Text("将永久删除“${target.title}”，删除后无法恢复。项目文件不受影响。")
                if(checking)Text("正在检查关联聊天…",color=NativeTheme.colors.muted,modifier=Modifier.padding(top=12.dp))
                if(related.isNotEmpty()) {
                    Text("以下 ${related.size} 个关联聊天（含已归档分叉、子代理）也将一起永久删除。若要保留它们，请取消。",
                        modifier=Modifier.padding(top=12.dp))
                    related.forEach {thread ->
                        Text("• ${thread.title.ifBlank {thread.preview.ifBlank {"未命名聊天"}}}",modifier=Modifier.padding(top=8.dp))
                        Text(thread.id,fontSize=11.sp,color=NativeTheme.colors.muted)
                    }
                }
                failure?.let {Text(it,color=NativeTheme.colors.danger,modifier=Modifier.padding(top=12.dp))}
            }},
            confirmButton={TextButton(enabled=!deleting&&!checking,onClick={
                val plan=deletionPlan
                if(plan==null)loadAttempt++ else {
                    deleting=true;failure=null
                    vm.deleteThread(plan,target.owner).whenComplete {_,error->
                        if(delete==target) {
                            deleting=false
                            if(error==null)delete=null else {failure=threadActionError(error);deletionPlan=null}
                        }
                    }
                }
            }){Text(when {deleting->"正在删除…";checking->"正在检查…";deletionPlan==null->"重新读取";related.isNotEmpty()->"永久删除全部 ${related.size+1} 个";else->"永久删除"},color=NativeTheme.colors.danger)}},
            dismissButton={TextButton(enabled=!deleting,onClick={delete=null}){Text("取消")}})
    } }
    removeProject?.let {target ->
        AlertDialog(onDismissRequest={removeProject=null},containerColor=NativeTheme.colors.surface,shape=RoundedCornerShape(28.dp),
            title={Text("从项目列表移除？")},
            text={Text("将从这台手机的项目列表移除“${target.path.substringAfterLast('/').ifBlank {target.path}}”。聊天和服务器文件会保留，可在“已移除的项目”中恢复。")},
            confirmButton={TextButton(onClick={vm.removeProjectFromList(target.path,target.owner);removeProject=null}){Text("移除",color=NativeTheme.colors.danger)}},
            dismissButton={TextButton(onClick={removeProject=null}){Text("取消")}})
    }
    if(fullAccess)AlertDialog(onDismissRequest={fullAccess=false},title={Text("允许完全访问？")},text={Text("Codex 将能够修改此服务器上的文件并执行命令，无需逐次审批。")},
        confirmButton={TextButton(onClick={vm.selectPermission(ShellPermission.FullAccess);fullAccess=false}){Text("允许")}},dismissButton={TextButton(onClick={fullAccess=false}){Text("取消")}})
    if(workspaceOpen)WorkspacePicker(vm,onClose={workspaceOpen=false})
    vm.files.state?.let { file -> RemoteFileSheet(file,onDismiss=vm.files::dismiss,onDownload={
        if(Build.VERSION.SDK_INT>=29)vm.files.saveToDownloads()
        else vm.files.beginDocumentSave()?.let { save ->
            fileExportId=save.id
            runCatching {fileExportLauncher.launch(save.intent)}.onFailure {vm.files.completeDocumentSave(save.id,null);fileExportId=null;vm.message="无法打开文件保存器"}
        }
    },onOpen={vm.files.openSaved(context,it)},onOpenImage={
        openImage(ImageSource(ImageSourceKind.RemotePath,file.path,mimeType=file.mimeType,cacheKey=file.path))
    }) }
    openedImage?.let {source -> ImageViewer(source,imageLoader,onClose={openedImage=null},onSave={suggestedName ->
        vm.imageExport=imageOwner to source;imageExportLauncher.launch(suggestedName)
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
    AlertDialog(onDismissRequest={if(!saving)onDismiss()},containerColor=NativeTheme.colors.surface,shape=RoundedCornerShape(28.dp),
        title={Text(title)},text={Column {
            OutlinedTextField(text,{text=it;failure=null},enabled=!saving&&fieldEnabled,singleLine=true)
            failure?.let {Text(it,color=NativeTheme.colors.danger,modifier=Modifier.padding(top=12.dp))}
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

@Composable
private fun ChatTimeline(vm:RemoteViewModel,latestRequest:Int,onCopy:(String)->Unit,onPlanOpen:(String)->Unit,onDownload:(String)->Unit,onOpenLink:(String)->Unit,
    imageLoader:ImageLoader,onOpenImage:(ImageSource)->Unit,topInset:Dp=84.dp) {
    ChatTimelineContent(state=vm.state,creating=vm.creating,latestRequest=latestRequest,
        onLoadEarlier={vm.observe(vm.repo.loadEarlierTurns())},onCopy=onCopy,onPlanOpen=onPlanOpen,
        onDownload=onDownload,onOpenLink=onOpenLink,imageLoader=imageLoader,onOpenImage=onOpenImage,onCopyImage=vm::copyImage,topInset=topInset)
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
                Text(thread.cwd,fontSize=12.sp,color=NativeTheme.colors.muted,maxLines=1)
                if(archived)TextButton(onClick={vm.observe(vm.repo.archiveThread(thread.id,false)) {vm.observe(vm.repo.refreshThreads(search=query,archived=true))}}){Text("取消归档")}
            }
        }; if(vm.state.nextThreadsCursor!=null)item { TextButton(onClick={vm.observe(vm.repo.refreshThreads(vm.state.nextThreadsCursor,query,archived))}){Text("加载更多")}} }
    }
}

@Composable
private fun WorkspacePicker(vm:RemoteViewModel,onClose:()->Unit) {
    val owner=vm.imageOwner()
    val browser=remember(owner) {WorkspaceBrowserState(vm.workspace.ifBlank {vm.state.selectedThread?.cwd ?: "/home/codex"},vm.repo::listDirectory)}
    DisposableEffect(browser) {onDispose {browser.close()}}
    LaunchedEffect(browser) {browser.load()}
    WorkspacePickerDialog(browser,onClose) {path -> if(vm.imageOwner()==owner)vm.updateWorkspace(path);onClose()}
}

@Composable
fun WorkspacePickerDialog(browser:WorkspaceBrowserState,onClose:()->Unit,onSelect:(String)->Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text("选择项目目录")},text={Column {
        OutlinedTextField(browser.path,browser::edit,singleLine=true,label={Text("服务器目录")})
        Row {
            TextButton(onClick={browser.load(browser.path.substringBeforeLast('/').ifBlank {"/"})}){Text("上一级")}
            TextButton(onClick={browser.load()}){Text("打开")}
        }
        if(browser.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        browser.error?.let {Text(it,color=NativeTheme.colors.danger)}
        LazyColumn(Modifier.heightIn(max=280.dp)) {
            items(browser.entries,key={it.path}) {entry ->
                Text(entry.name,Modifier.fillMaxWidth().clickable {browser.load(entry.path)}.padding(vertical=12.dp),style=NativeTypography.body16)
            }
        }
    }},confirmButton={TextButton(onClick={if(browser.canSelect)onSelect(browser.path)},enabled=browser.canSelect){Text("选择此目录")}},
        dismissButton={TextButton(onClick=onClose){Text("取消")}})
}
