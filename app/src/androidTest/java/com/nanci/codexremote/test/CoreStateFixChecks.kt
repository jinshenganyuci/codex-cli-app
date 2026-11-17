package com.nanci.codexremote.test

import com.nanci.codexremote.core.*
import org.json.JSONObject

/** Exercises the production request builder without starting a server or a model turn. */
object CoreStateFixChecks {
    fun run(): List<String> = buildList {
        val old = RepositoryState(threadSettings = jsonObject("model" to "gpt-6.1-sol", "effort" to "high",
            "sandboxPolicy" to jsonObject("type" to "dangerFullAccess"), "serviceTier" to "default"))
        val chosen = PermissionCapabilities.options(PermissionPreset.READ_ONLY).copy(
            model = "gpt-6.1-terra", effort = "xhigh", serviceTier = "priority", cwd = "/safe/workspace")
        for (mode in listOf("default", "plan")) {
            val params = JSONObject()
            TurnOptionPayload.apply(params, TurnOptionPayload.forPlan(chosen, mode), old)
            check(params.getString("model") == "gpt-6.1-terra")
            check(params.getString("effort") == "xhigh")
            check(params.getString("serviceTier") == "priority")
            check(params.getString("cwd") == "/safe/workspace")
            check(params.getString("approvalPolicy") == "on-request")
            check(params.getString("approvalsReviewer") == "user")
            check(params.getJSONObject("sandboxPolicy").getString("type") == "readOnly")
            val collaboration = params.getJSONObject("collaborationMode")
            check(collaboration.getString("mode") == mode)
            check(collaboration.getJSONObject("settings").getString("model") == "gpt-6.1-terra")
            check(collaboration.getJSONObject("settings").getString("reasoning_effort") == "xhigh")
            add("plan $mode submission preserves complete composer options")
        }
        check(runCatching { TurnOptionPayload.apply(JSONObject(), chosen.copy(permissions = ":read-only"), old) }.isFailure)
        add("conflicting permission profile and sandbox are refused")
        val failures = approvalDecisionSmokeChecks()
        check(failures.isEmpty()) { failures.joinToString() }
        add("approval UI and repository share native allowed decisions and human-only fallback")
    }
}
