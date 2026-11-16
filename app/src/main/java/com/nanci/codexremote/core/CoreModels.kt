package com.nanci.codexremote.core

import org.json.JSONObject

/** Connection metadata only. Credentials must be persisted by the Android Keystore owner. */
data class ConnectionProfile(
    val host: String,
    val port: Int = 22,
    val user: String = "codex",
    val fingerprint: String,
    val name: String = host,
    val hostKeyAlgorithm: String = "ecdsa-sha2-nistp256",
    val codexPath: String = "~/.local/bin/codex",
)

/** Caller owns these arrays; connect copies them before returning. Never render or log this object. */
class SshCredential(
    val privateKey: ByteArray? = null,
    val passphrase: ByteArray? = null,
    val password: CharArray? = null,
) : AutoCloseable {
    internal fun copySecret() = SshCredential(privateKey?.clone(), passphrase?.clone(), password?.clone())
    override fun close() { privateKey?.fill(0); passphrase?.fill(0); password?.fill('\u0000') }
    override fun toString() = "SshCredential([redacted])"
}

enum class ConnectionStatus { DISCONNECTED, CONNECTING, INITIALIZING, CONNECTED }

/** Stable built-in section ID in App Server; pinning is ordinary server section membership. */
const val PINNED_SECTION_ID = "01984de2-8f74-7c91-a3b2-5c5e937cf318"
data class ChatSection(val id: String, val name: String) {
    val isPinned: Boolean get() = id == PINNED_SECTION_ID
}
data class ChatReadState(val unread: Boolean, val revision: String)

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val name: String = "",
    val message: String = "尚未连接",
    val generation: Long = 0,
)

data class ThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val cwd: String,
    val updatedAt: Long,
    val status: String,
    val model: String?,
    val modelProvider: String?,
    val canAcceptInput: Boolean?,
    val raw: JSONObject,
    val sectionId: String? = null,
)

data class ChatItem(
    val id: String,
    val turnId: String,
    val type: String,
    val text: String,
    val status: String,
    val raw: JSONObject,
)

data class ChatTurn(
    val id: String,
    val status: String,
    val items: List<ChatItem>,
    val error: String? = null,
    val raw: JSONObject,
)

data class ModelInfo(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val efforts: List<String>,
    val defaultEffort: String?,
    val isDefault: Boolean,
    val imageInput: Boolean,
    val raw: JSONObject,
)

data class PlanStep(val step: String, val status: String)
data class ExecutionPlan(val turnId: String, val explanation: String?, val steps: List<PlanStep>)

enum class RequestKind { COMMAND_APPROVAL, FILE_APPROVAL, PERMISSIONS_APPROVAL, USER_INPUT, ELICITATION, UNSUPPORTED }

/** key is connection-specific; rpcId retains its original JSON string/number type. */
data class PendingRequest(
    val key: String,
    val rpcId: Any,
    val method: String,
    val kind: RequestKind,
    val threadId: String?,
    val turnId: String?,
    val itemId: String?,
    val params: JSONObject,
    val submitting: Boolean = false,
)

data class AsyncQuestion(
    val key: String,
    val itemId: String,
    val turnId: String,
    val title: String,
    val options: List<String>,
    val raw: JSONObject,
)

data class TurnOptions(
    val model: String? = null,
    val effort: String? = null,
    val cwd: String? = null,
    val collaborationMode: String? = null,
    val approvalPolicy: Any? = null,
    val approvalsReviewer: String? = null,
    val sandboxPolicy: JSONObject? = null,
    val permissions: String? = null,
    val serviceTier: String? = null,
)

/** Preserve raw server fields so newer item kinds can be inspected without silently dropping data. */
data class RepositoryState(
    val connection: ConnectionState = ConnectionState(),
    val threads: List<ThreadSummary> = emptyList(),
    val nextThreadsCursor: String? = null,
    val loadingThreads: Boolean = false,
    val sections: List<ChatSection> = emptyList(),
    val sectionsSupported: Boolean? = null,
    val loadingSections: Boolean = false,
    val sectionsError: String? = null,
    /** Authoritative section_position order; only ranks needed by currently loaded rows are fetched. */
    val sectionThreadIds: Map<String, List<String>> = emptyMap(),
    /** Missing row means ineligible, whereas readStateSupported=false means unavailable globally. */
    val readStates: Map<String, ChatReadState> = emptyMap(),
    val readStateSupported: Boolean? = null,
    /** Enabled only for a verified native server version, never emulated by archive. */
    val threadDeletionSupported: Boolean? = null,
    /** Authoritative delete acknowledgements/notifications, scoped to the current host. */
    val deletedThreadIds: Set<String> = emptySet(),
    /** Reconnected, never-submitted local drafts replaced by a fresh subscribed thread. */
    val draftReplacements: Map<String, String> = emptyMap(),
    val selectedThread: ThreadSummary? = null,
    /** Cached content may remain visible before this connection has resumed/subscribed it. */
    val selectedThreadReady: Boolean = false,
    val loadingThread: Boolean = false,
    val turns: List<ChatTurn> = emptyList(),
    val nextTurnsCursor: String? = null,
    val pendingRequests: List<PendingRequest> = emptyList(),
    val asyncQuestions: List<AsyncQuestion> = emptyList(),
    val plans: Map<String, ExecutionPlan> = emptyMap(),
    val diff: String = "",
    val diffTurnId: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val config: JSONObject? = null,
    val configRequirements: JSONObject? = null,
    /** Effective guardian_approval feature for the selected thread, null while discovery is pending. */
    val guardianApprovalEnabled: Boolean? = null,
    val threadSettings: JSONObject? = null,
    val initialization: JSONObject? = null,
    val sending: Boolean = false,
    val lastError: String? = null,
) {
    val pinnedSectionId: String? get() = sections.firstOrNull { it.isPinned }?.id
    val activeTurnId: String? get() = turns.lastOrNull { it.status == "inProgress" }?.id
    val connected: Boolean get() = connection.status == ConnectionStatus.CONNECTED
    val items: List<ChatItem> get() = turns.flatMap { it.items }
    val canSend: Boolean get() = connected && !sending && !loadingThread &&
        (selectedThread == null || selectedThreadReady) && selectedThread?.canAcceptInput != false
}

data class RemoteEntry(val name: String, val path: String, val directory: Boolean, val size: Long)

class CodexException(
    val safeMessage: String,
    val code: Int? = null,
    val outcomeUnknown: Boolean = false,
) : Exception(safeMessage)
