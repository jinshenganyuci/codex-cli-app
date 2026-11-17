package com.nanci.codexremote.connection

import com.nanci.codexremote.core.ConnectionProfile
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Safe messages only: raw JSON, SSH exceptions and passwords must never reach UI or logs. */
class BootstrapException(val safeMessage: String) : Exception(safeMessage)

class BootstrapSpec private constructor(
    val inviteId: String,
    val enrollment: ConnectionProfile,
    val enrollmentPassword: CharArray,
    val connection: ConnectionProfile,
    val workspace: String,
) : AutoCloseable {
    override fun close() { enrollmentPassword.fill('\u0000') }
    override fun toString() = "BootstrapSpec([redacted])"

    companion object {
        const val ASSET_NAME = "codex-bootstrap.json"
        const val MAX_BYTES = 64 * 1024

        fun parse(bytes: ByteArray): BootstrapSpec {
            try {
                require(bytes.size in 1..MAX_BYTES)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val root = JSONObject(text)
                require(root.keys().asSequence().toSet() == setOf("schemaVersion", "inviteId", "enrollment", "connection", "workspace"))
                require(root.get("schemaVersion") == 1)
                val invite = string(root, "inviteId")
                require(invite.matches(Regex("[A-Za-z0-9_-]{16,80}")))
                val enrollment = root.getJSONObject("enrollment")
                require(enrollment.keys().asSequence().toSet() == setOf("host", "port", "user", "password", "fingerprint", "hostKeyAlgorithm"))
                val connection = root.getJSONObject("connection")
                require(connection.keys().asSequence().toSet() == setOf("host", "port", "user", "fingerprint", "name", "hostKeyAlgorithm", "codexPath"))
                val enrollmentProfile = profile(enrollment, false)
                val connectionProfile = profile(connection, true)
                val workspace = string(root, "workspace")
                require(workspace.startsWith('/') && workspace.length <= 4096 && workspace.none { it.code < 32 })
                val password = string(enrollment, "password")
                require(password.length in 24..512 && password.none { it.code < 33 || it.code > 126 })
                return BootstrapSpec(invite, enrollmentProfile, password.toCharArray(), connectionProfile, workspace)
            } catch (_: Exception) {
                throw BootstrapException("安装包中的连接资料无效，请获取新的安装包或手动配置。")
            }
        }

        private fun string(json: JSONObject, name: String): String = json.get(name) as? String ?: error("string")
        private fun profile(json: JSONObject, target: Boolean): ConnectionProfile {
            val host = string(json, "host")
            val user = string(json, "user")
            val port = json.get("port") as? Int ?: error("port")
            val fingerprint = string(json, "fingerprint")
            val algorithm = string(json, "hostKeyAlgorithm")
            require(host.length in 1..253 && host.none { it.isWhitespace() || it.code < 33 || it in "/\\" })
            require(user.matches(Regex("[a-z_][a-z0-9_-]{0,63}")) && port in 1..65535)
            require(fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?")))
            require(algorithm.matches(Regex("[A-Za-z0-9@._+-]{1,80}")))
            val name = if (target) string(json, "name") else "首次连接"
            val executable = if (target) string(json, "codexPath") else "~/.local/bin/codex"
            require(name.isNotBlank() && name.length <= 80 && name.none { it.code < 32 })
            require(executable.isNotBlank() && executable.length <= 4096 && executable.none { it.code < 32 })
            return ConnectionProfile(host, port, user, fingerprint, name, algorithm, executable)
        }
    }
}
