package com.nanci.codexremote.core

import org.json.JSONArray
import org.json.JSONObject

/** Wire presets from the official Android client. CUSTOM is a display state, never an action. */
enum class PermissionPreset { DEFAULT, AUTO_REVIEW, READ_ONLY, FULL_ACCESS, CUSTOM }

object PermissionCapabilities {
    private val presets = listOf(PermissionPreset.DEFAULT, PermissionPreset.AUTO_REVIEW,
        PermissionPreset.READ_ONLY, PermissionPreset.FULL_ACCESS)

    fun available(state: RepositoryState, model: String? = null): List<PermissionPreset> =
        presets.filter { disabledReason(state, it, model) == null }

    /** Mobile's default applies to each new turn, including turns in resumed chats. */
    internal fun composerPreset(state: RepositoryState, model: String?, choice: PermissionPreset?): PermissionPreset {
        if (choice != null) return choice
        return if (state.configRequirements != null && disabledReason(state, PermissionPreset.FULL_ACCESS, model) != null)
            PermissionPreset.CUSTOM else PermissionPreset.FULL_ACCESS
    }

    fun normalizeReviewer(value: String?): String = when (value) {
        null, "", "null" -> "user"
        "guardian_subagent" -> "auto_review"
        else -> value
    }

    /** Null rules are unrestricted; an absent discovery response is still unknown. */
    fun disabledReason(state: RepositoryState, preset: PermissionPreset, model: String? = null): String? {
        if (preset == PermissionPreset.CUSTOM) return "自定义权限由服务器配置管理"
        val response = state.configRequirements ?: return "正在读取服务器权限要求，请稍后重试"
        val rules = if (response.has("requirements")) {
            if (response.isNull("requirements")) null
            else response.optJSONObject("requirements") ?: return "服务器权限要求格式无法识别"
        } else response
        val policy = if (preset == PermissionPreset.FULL_ACCESS) "never" else "on-request"
        val reviewer = if (preset == PermissionPreset.AUTO_REVIEW) "auto_review" else "user"
        val mode = when (preset) {
            PermissionPreset.READ_ONLY -> "read-only"
            PermissionPreset.FULL_ACCESS -> "danger-full-access"
            else -> "workspace-write"
        }
        fun allowed(field: String, value: String, reviewerField: Boolean = false): Boolean {
            if (rules == null || !rules.has(field) || rules.isNull(field)) return true
            val values = rules.optJSONArray(field) ?: return false
            return (0 until values.length()).any {
                val candidate = values.opt(it) as? String
                candidate != null && (if (reviewerField) normalizeReviewer(candidate) else candidate) == value
            }
        }
        if (!allowed("allowedApprovalPolicies", policy) ||
            !allowed("allowedApprovalsReviewers", reviewer, true) || !allowed("allowedSandboxModes", mode))
            return "服务器管理策略不允许此权限模式"
        if (rules != null && rules.has("allowedPermissionProfiles") && !rules.isNull("allowedPermissionProfiles")) {
            val profile = when (preset) {
                PermissionPreset.READ_ONLY -> ":read-only"
                PermissionPreset.FULL_ACCESS -> ":danger-full-access"
                else -> ":workspace"
            }
            if (rules.optJSONObject("allowedPermissionProfiles")?.opt(profile) != true)
                return "服务器管理策略不允许此权限配置"
        }
        if (autoReviewRequired(rules, model) && preset != PermissionPreset.AUTO_REVIEW)
            return "此模型必须使用自动审查"
        if (preset == PermissionPreset.AUTO_REVIEW) {
            val features = rules?.optJSONObject("featureRequirements")
            if (features?.opt("auto_review") == false || features?.opt("guardian_approval") == false)
                return "服务器管理策略已关闭自动审查"
            return when (state.guardianApprovalEnabled) {
                true -> null
                false -> "此服务器尚未启用自动审查"
                null -> "正在确认服务器自动审查能力"
            }
        }
        return null
    }

    internal fun autoReviewRequired(rules: JSONObject?, model: String?): Boolean {
        if (model.isNullOrBlank()) return false
        val required = rules?.optJSONObject("autoReview")?.optJSONArray("requiredOnModels") ?: return false
        // Match the server's exact model slug / single provider namespace normalization, not a prefix/glob.
        val slash = model.indexOf('/')
        val slug = if (slash < 0) model else {
            val namespace = model.substring(0, slash)
            val suffix = model.substring(slash + 1)
            if (namespace.isEmpty() || !namespace.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' } || '/' in suffix) return false
            suffix
        }
        return (0 until required.length()).any { required.opt(it) == slug }
    }

    fun options(preset: PermissionPreset): TurnOptions {
        if (preset == PermissionPreset.CUSTOM) return TurnOptions()
        val sandbox = when (preset) {
            PermissionPreset.READ_ONLY -> JSONObject().put("type", "readOnly").put("networkAccess", false)
            PermissionPreset.FULL_ACCESS -> JSONObject().put("type", "dangerFullAccess")
            else -> JSONObject().put("type", "workspaceWrite").put("writableRoots", JSONArray())
                .put("networkAccess", false).put("excludeTmpdirEnvVar", false).put("excludeSlashTmp", false)
        }
        return TurnOptions(sandboxPolicy = sandbox,
            approvalPolicy = if (preset == PermissionPreset.FULL_ACCESS) "never" else "on-request",
            approvalsReviewer = if (preset == PermissionPreset.AUTO_REVIEW) "auto_review" else "user")
    }
}
