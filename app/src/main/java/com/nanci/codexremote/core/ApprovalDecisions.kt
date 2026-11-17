package com.nanci.codexremote.core

import org.json.JSONArray
import org.json.JSONObject

/** One policy for both rendering and validation; server decisions retain their original payloads. */
fun approvalDecisionsFor(method: String, params: JSONObject): List<Any> {
    val legacy = method == "execCommandApproval" || method == "applyPatchApproval"
    val available = params.optJSONArray("availableDecisions")
    val decisions = if (available != null) (0 until available.length()).mapNotNull { index ->
        available.opt(index)?.takeUnless { it == JSONObject.NULL }
    } else if (legacy) listOf("approved", "approved_for_session", legacyDenial(""), "abort")
    else listOf("accept", "acceptForSession", "decline", "cancel")
    // humanCommand is a one-time approval even when an older/custom server omits its options.
    return if (params.optString("kind") == "humanCommand") decisions.filter { decision ->
        decision in listOf("accept", "approved", "decline", "denied", "cancel", "abort") || isLegacyDenial(decision)
    } else decisions
}

fun isApprovalDecisionAllowed(method: String, params: JSONObject, decision: Any?): Boolean {
    val available = approvalDecisionsFor(method, params)
    if (available.any { sameApprovalJson(it, decision) }) return true
    val legacy = method == "execCommandApproval" || method == "applyPatchApproval"
    // The rejection text is user input, rather than a new grant or policy amendment.
    return legacy && isLegacyDenial(decision) && available.any { it == "denied" || isLegacyDenial(it) }
}

private fun legacyDenial(text: String): JSONObject = JSONObject().put("denied", JSONObject().put("rejection", text))

private fun isLegacyDenial(value: Any?): Boolean {
    val outer = value as? JSONObject ?: return false
    if (outer.keys().asSequence().toSet() != setOf("denied")) return false
    val denial = outer.optJSONObject("denied") ?: return false
    return denial.keys().asSequence().toSet() == setOf("rejection") && denial.opt("rejection") is String
}

private fun sameApprovalJson(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> {
        val keys = left.keys().asSequence().toSet()
        keys == right.keys().asSequence().toSet() && keys.all { sameApprovalJson(left.opt(it), right.opt(it)) }
    }
    left is JSONArray && right is JSONArray -> left.length() == right.length() &&
        (0 until left.length()).all { sameApprovalJson(left.opt(it), right.opt(it)) }
    else -> left == right
}

internal fun approvalDecisionSmokeChecks(): List<String> = buildList {
    val human = JSONObject().put("kind", "humanCommand")
    if ("acceptForSession" in approvalDecisionsFor("item/commandExecution/requestApproval", human)) add("human fallback session grant")
    human.put("availableDecisions", JSONArray().put("accept").put("acceptForSession").put("decline"))
    if (isApprovalDecisionAllowed("item/commandExecution/requestApproval", human, "acceptForSession")) add("human explicit session grant")
    val legacy = JSONObject()
    if (!isApprovalDecisionAllowed("execCommandApproval", legacy, legacyDenial("use another command"))) add("legacy rejection feedback")
    if (isApprovalDecisionAllowed("execCommandApproval", legacy,
            legacyDenial("no").put("approved_for_session", true))) add("legacy denial extra grant")
    val limited = JSONObject().put("availableDecisions", JSONArray().put("cancel"))
    if (isApprovalDecisionAllowed("item/commandExecution/requestApproval", limited, "accept")) add("unlisted decision")
    val rule = JSONObject().put("acceptWithExecpolicyAmendment", JSONObject().put("execpolicy_amendment", JSONArray().put("ls")))
    val rules = JSONObject().put("availableDecisions", JSONArray().put(rule))
    if (!isApprovalDecisionAllowed("item/commandExecution/requestApproval", rules, JSONObject(rule.toString()))) add("exact rule payload")
    if (isApprovalDecisionAllowed("item/commandExecution/requestApproval", rules,
            JSONObject().put("acceptWithExecpolicyAmendment", JSONObject().put("execpolicy_amendment", JSONArray().put("sh"))))) add("altered rule payload")
}
