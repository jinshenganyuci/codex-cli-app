package com.nanci.codexremote.connection

import android.content.Context
import com.nanci.codexremote.core.ModelInfo

/** Composer defaults belong to this device and connection, never a shared server config. */
data class ModelSelection(val model: String = "gpt-6.1-terra", val effort: String = "xhigh") {
    fun available(models: List<ModelInfo>): ModelSelection {
        if (models.isEmpty()) return this
        val selected = models.firstOrNull { it.model == model }
            ?: models.firstOrNull { it.model == "gpt-6.1-terra" }
            ?: models.firstOrNull { it.isDefault }
            ?: models.first()
        val reasoning = when {
            effort in selected.efforts -> effort
            "xhigh" in selected.efforts -> "xhigh"
            selected.defaultEffort in selected.efforts -> selected.defaultEffort.orEmpty()
            else -> selected.efforts.firstOrNull().orEmpty()
        }
        return ModelSelection(selected.model, reasoning)
    }
}

class ModelPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("model-defaults-v1", Context.MODE_PRIVATE)

    /** One upgrade migration. A pending choice survives failed connections and process death. */
    fun migrate(connections: List<SavedConnection>) {
        if (prefs.getBoolean(MIGRATION, false)) return
        val edit = prefs.edit()
        connections.forEach { connection ->
            val prefix = scope(connection)
            edit.putString("$prefix:model", "gpt-6.1-terra").putString("$prefix:effort", "xhigh")
                .putBoolean("$prefix:initial-choice", true)
        }
        check(edit.putBoolean(MIGRATION, true).commit()) { "无法保存默认模型，请检查设备存储空间。" }
    }

    fun initialChoicePending(connection: SavedConnection?): Boolean = connection != null &&
        prefs.getBoolean("${scope(connection)}:initial-choice", false)

    /** Only an explicit choice or an accepted turn consumes the upgrade's forced selection. */
    fun initialChoiceUsed(connection: SavedConnection) {
        if (initialChoicePending(connection))
            check(prefs.edit().remove("${scope(connection)}:initial-choice").commit()) { "无法保存默认模型，请检查设备存储空间。" }
    }

    fun read(connection: SavedConnection?): ModelSelection {
        val prefix = connection?.let(::scope) ?: return ModelSelection()
        return ModelSelection(
            prefs.getString("$prefix:model", null)?.takeIf { it.isNotBlank() } ?: "gpt-6.1-terra",
            prefs.getString("$prefix:effort", null) ?: "xhigh",
        )
    }

    fun save(connection: SavedConnection, selection: ModelSelection) {
        val prefix = scope(connection)
        // Both values must describe the same user choice after an app restart.
        check(prefs.edit().putString("$prefix:model", selection.model)
            .putString("$prefix:effort", selection.effort).remove("$prefix:initial-choice").commit()) { "无法保存默认模型，请检查设备存储空间。" }
    }

    private fun scope(connection: SavedConnection): String = connection.identityKey

    private companion object { const val MIGRATION = "terra-xhigh-upgrade-v1" }
}
