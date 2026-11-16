package com.nanci.codexremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nanci.codexremote.connection.ConnectionStore
import com.nanci.codexremote.core.ConnectionProfile
import com.nanci.codexremote.core.SshCredential
import org.json.JSONObject
import java.io.File

/** Debug-only provisioning, from files delivered to this application's private UID directory. */
class BootstrapActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val metadata=File(filesDir,"provision.json")
        val key=File(filesDir,"provision-key")
        val result=File(filesDir,"provision-result")
        try {
            val data=JSONObject(metadata.readText())
            val profile=ConnectionProfile(host=data.getString("host"),user=data.getString("user"),fingerprint=data.getString("fingerprint"),name=data.optString("name","本地 Codex"))
            val secret=SshCredential(privateKey=key.readBytes())
            try { val store=ConnectionStore(this);val old=store.list().firstOrNull {it.profile.host==profile.host&&it.profile.user==profile.user};val saved=store.save(profile,secret,old?.id);store.setActive(saved.id);store.workspace="/home/codex" }
            finally {secret.close()}
            result.writeText("OK")
        } catch(e:Exception) {result.writeText("FAILED: "+e.javaClass.simpleName)}
        finally {metadata.delete();key.delete()}
        startActivity(Intent(this,MainActivity::class.java));finish()
    }
}
