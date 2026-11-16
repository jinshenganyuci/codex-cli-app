package com.nanci.codexremote.ui.shell

import android.graphics.Bitmap

data class ShellThread(
    val id: String,
    val title: String,
    val cwd: String = "",
    val pinned: Boolean = false,
    val running: Boolean = false,
    val sectionId: String? = null,
    val unread: Boolean = false,
    val readStateAvailable: Boolean = false,
)

data class ShellSection(val id: String, val name: String)

/** Remote actions are enabled only after the active server has advertised/validated them. */
data class ThreadMenuAvailability(
    val canTogglePin: Boolean = false,
    val canRename: Boolean = false,
    val canMoveToSection: Boolean = false,
    val canMarkUnread: Boolean = false,
    val canArchive: Boolean = false,
    val disabledReason: String? = null,
    val canDelete: Boolean = false,
)

/** Every callback names the pressed row; none of these actions opens that chat first. */
data class ThreadMenuActions(
    val onTogglePin: (String) -> Unit,
    val onRename: (String) -> Unit,
    val onMoveToSection: (String, String?) -> Unit,
    val onCreateSection: (String) -> Unit,
    val onMarkUnread: (String) -> Unit,
    val onCopyId: (String) -> Unit,
    val onArchive: (String) -> Unit,
    val onOpenSections: (String) -> Unit,
    val onDelete: ((String) -> Unit)? = null,
)

data class ShellModel(
    val id: String,
    val label: String,
    val reasoning: List<String> = emptyList(),
    val description: String = "",
)

enum class ThreadGrouping { Priority, Project, Time }
enum class ShellPermission { Default, AutoReview, ReadOnly, FullAccess, Custom }
enum class AttachmentAction { Camera, Photos, Files }

data class ShellState(
    val hostName: String = "本地 Codex",
    val title: String = "Codex",
    val subtitle: String = "",
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val threads: List<ShellThread> = emptyList(),
    val loading: Boolean = false,
    val grouping: ThreadGrouping = ThreadGrouping.Project,
    val pinned: Boolean = false,
    val workspace: String = "无项目",
    val hasMore: Boolean = false,
    val sections: List<ShellSection> = emptyList(),
    val sectionsLoading: Boolean = false,
    val sectionsError: String? = null,
    val sectionThreadIds: Map<String, List<String>> = emptyMap(),
    val threadMenuAvailability: ThreadMenuAvailability = ThreadMenuAvailability(),
    val threadId: String? = null,
    val hiddenProjectPaths: Set<String> = emptySet(),
)

data class ShellActions(
    val onThread: (String) -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onHost: () -> Unit = {},
    val onWorkspace: () -> Unit = {},
    val onProjectChat: (String) -> Unit = {},
    val onAll: () -> Unit = {},
    val onGroupingChanged: (ThreadGrouping) -> Unit = {},
    val onBack: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onArchive: () -> Unit = {},
    val onTogglePin: () -> Unit = {},
    val onCopyId: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val threadMenu: ThreadMenuActions? = null,
    val onDelete: ((String) -> Unit)? = null,
    val onRemoveProject: ((String) -> Unit)? = null,
    val onRestoreProject: ((String) -> Unit)? = null,
)

data class ShellAttachment(
    val id: String,
    val label: String,
    val thumbnail: Bitmap? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ComposerState(
    val text: String = "",
    val placeholder: String = "问问 Codex",
    val enabled: Boolean = true,
    val submitting: Boolean = false,
    val running: Boolean = false,
    val planMode: Boolean = false,
    val permission: ShellPermission = ShellPermission.Default,
    val permissions: List<ShellPermission> = listOf(ShellPermission.Default, ShellPermission.ReadOnly, ShellPermission.FullAccess),
    val models: List<ShellModel> = emptyList(),
    val modelId: String = "",
    val reasoning: String = "",
    val attachments: List<ShellAttachment> = emptyList(),
    val focusRequest: Int = 0,
    val allowCamera: Boolean = true,
    val allowPhotos: Boolean = true,
    val allowFiles: Boolean = true,
    val supportsFast: Boolean = false,
    val fast: Boolean = false,
    // Keep a live Home draft editable while chat creation still blocks sending.
    val editorEnabled: Boolean = enabled && !submitting,
)

data class ComposerActions(
    val onTextChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onPlanMode: (Boolean) -> Unit = {},
    val onPermission: (ShellPermission) -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onReasoning: (String) -> Unit = {},
    val onAttachment: (AttachmentAction) -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onFast: (Boolean) -> Unit = {},
    val onFocus: () -> Unit = {},
    val onFocusRequestHandled: (Int) -> Unit = {},
)
