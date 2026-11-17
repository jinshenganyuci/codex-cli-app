package com.nanci.codexremote.core

import org.json.JSONObject

/** The shared wire builder used by ordinary messages and plan-card submissions. */
internal object TurnOptionPayload {
    fun forPlan(options: TurnOptions, mode: String): TurnOptions {
        require(mode in listOf("default", "plan"))
        return options.copy(collaborationMode = mode)
    }

    fun apply(params: JSONObject, options: TurnOptions, state: RepositoryState) {
        require(options.permissions == null || options.sandboxPolicy == null) { "不能同时设置权限配置和沙箱策略" }
        params.putIfNotNull("model", options.model).putIfNotNull("effort", options.effort)
            .putIfNotNull("cwd", options.cwd).putIfNotNull("approvalPolicy", options.approvalPolicy)
            .putIfNotNull("approvalsReviewer", options.approvalsReviewer).putIfNotNull("sandboxPolicy", options.sandboxPolicy)
            .putIfNotNull("permissions", options.permissions).putIfNotNull("serviceTier", options.serviceTier)
        options.collaborationMode?.let { mode ->
            require(mode in listOf("default", "plan"))
            val model = options.model ?: state.threadSettings?.stringOrNull("model") ?: state.selectedThread?.model
                ?: state.models.firstOrNull { it.isDefault }?.model
                ?: state.config?.optJSONObject("config")?.stringOrNull("model")
                ?: throw CodexException("无法确定当前模型，请先选择模型")
            params.put("collaborationMode", jsonObject("mode" to mode, "settings" to jsonObject(
                "model" to model, "reasoning_effort" to (options.effort ?: state.threadSettings?.stringOrNull("effort")
                    ?: state.threadSettings?.stringOrNull("reasoningEffort")),
                "developer_instructions" to null)))
        }
    }
}
