package com.nanci.codexremote.test

import com.nanci.codexremote.connection.ModelSelection
import com.nanci.codexremote.core.*
import org.json.JSONObject

/** Exercises the production request builder without starting a server or a model turn. */
object CoreStateFixChecks {
    fun run(): List<String> = buildList {
        val unrestricted = RepositoryState(configRequirements = jsonObject("requirements" to JSONObject.NULL))
        val legacy = unrestricted.copy(threadSettings = jsonObject(
            "sandboxPolicy" to jsonObject("type" to "readOnly"), "approvalPolicy" to "on-request"))
        for (chat in listOf(unrestricted, legacy)) {
            val preset = PermissionCapabilities.composerPreset(chat, GPT_6_2_SOL, choice = null)
            check(preset == PermissionPreset.FULL_ACCESS)
            val options = PermissionCapabilities.options(preset)
            for (turn in listOf(options, TurnOptionPayload.forPlan(options, "plan"), TurnOptionPayload.forPlan(options, "default"))) {
                val params = JSONObject()
                TurnOptionPayload.apply(params, turn.copy(model = GPT_6_2_SOL), chat)
                check(params.getJSONObject("sandboxPolicy").getString("type") == "dangerFullAccess")
                check(params.getString("approvalPolicy") == "never")
                check(params.getString("approvalsReviewer") == "user")
            }
        }
        check(PermissionCapabilities.composerPreset(legacy, GPT_6_2_SOL, PermissionPreset.READ_ONLY) == PermissionPreset.READ_ONLY)
        val restricted = legacy.copy(configRequirements = jsonObject("requirements" to jsonObject(
            "allowedSandboxModes" to jsonArray(listOf("read-only")))))
        check(PermissionCapabilities.composerPreset(restricted, GPT_6_2_SOL, choice = null) == PermissionPreset.CUSTOM)
        check(PermissionCapabilities.disabledReason(RepositoryState(), PermissionPreset.FULL_ACCESS) != null)
        add("new and resumed chats default to full access for every turn and honor managed constraints")
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
        val sol = ProtocolParser.model(jsonObject("model" to GPT_6_2_SOL, "defaultReasoningEffort" to "low",
            "supportedReasoningEfforts" to jsonArray(listOf(jsonObject("reasoningEffort" to "xhigh"))),
            "serviceTiers" to jsonArray(listOf(jsonObject("id" to "priority")))))
        val other = ProtocolParser.model(jsonObject("model" to "other", "isDefault" to true,
            "additionalSpeedTiers" to jsonArray(listOf("fast"))))
        check(composerSpeeds(sol.model, listOf(sol)) == ComposerSpeed.entries)
        check(composerSpeeds(other.model, listOf(other)) == listOf(ComposerSpeed.STANDARD, ComposerSpeed.FAST))
        check(ModelSelection().available(listOf(sol)) == ModelSelection(GPT_6_2_SOL, "xhigh", ComposerSpeed.ULTRAFAST))
        check(ModelSelection().available(listOf(other)).let { it.model == other.model && it.speed == ComposerSpeed.STANDARD })
        add("Sol exposes ultrafast with a legacy registry and other models honor their advertised tiers")
        for (speed in ComposerSpeed.entries) {
            val options = chosen.copy(model = sol.model, serviceTier = speed.serviceTier)
            val params = JSONObject()
            TurnOptionPayload.apply(params, TurnOptionPayload.forPlan(options, "plan"), old)
            check(params.getString("model") == GPT_6_2_SOL)
            check(params.getString("serviceTier") == speed.serviceTier)
        }
        check(ComposerSpeed.entries.map { it.serviceTier } == listOf("default", "priority", "ultrafast"))
        add("standard, fast and ultrafast send their distinct wire IDs")
        check(runCatching { TurnOptionPayload.apply(JSONObject(), chosen.copy(permissions = ":read-only"), old) }.isFailure)
        add("conflicting permission profile and sandbox are refused")
        val failures = approvalDecisionSmokeChecks()
        check(failures.isEmpty()) { failures.joinToString() }
        add("approval UI and repository share native allowed decisions and human-only fallback")
    }
}
