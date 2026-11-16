package com.nanci.codexremote.core

/** Local ownership and connection phases, independent of remote send permissions. */
internal object ComposerConnectionPolicy {
    fun sameOwner(a: ConnectionProfile?, b: ConnectionProfile): Boolean = a != null &&
        a.host == b.host && a.port == b.port && a.user == b.user &&
        a.fingerprint.trimEnd('=') == b.fingerprint.trimEnd('=') &&
        a.hostKeyAlgorithm == b.hostKeyAlgorithm && a.codexPath == b.codexPath

    fun shouldAutoConnect(status: ConnectionStatus, manualDisconnect: Boolean, hasProfile: Boolean): Boolean =
        hasProfile && !manualDisconnect && status == ConnectionStatus.DISCONNECTED

    fun editorReady(connectionId: String?, home: Boolean, creating: Boolean, carryingHomeDraft: Boolean,
        draftKey: String, selectedId: String?, boundId: String?): Boolean {
        if (creating) return carryingHomeDraft
        if (home) return draftKey == "$connectionId:new"
        return selectedId != null && selectedId == boundId && draftKey == "$connectionId:$selectedId"
    }
}
