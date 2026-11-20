package com.nanci.codexremote.connection

import android.content.Context
import com.nanci.codexremote.core.ComposerSpeed
import com.nanci.codexremote.core.GPT_6_2_SOL
import com.nanci.codexremote.core.ModelInfo
import com.nanci.codexremote.core.composerSpeeds

/** Composer defaults belong to this device and connection, never a shared server config. */
data class ModelSelection(
    val model: String = GPT_6_2_SOL,
    val effort: String = "xhigh",
    val speed: ComposerSpeed = if (model == GPT_6_2_SOL) ComposerSpeed.ULTRAFAST else ComposerSpeed.STANDARD,
) {
    fun available(models: List<ModelInfo>): ModelSelection {
        if (models.isEmpty()) return this
        val selected = models.firstOrNull { it.model == model }
            ?: models.firstOrNull { it.model == GPT_6_2_SOL }
            ?: models.firstOrNull { it.isDefault }
            ?: models.first()
        val reasoning = when {
            effort in selected.efforts -> effort
            "xhigh" in selected.efforts -> "xhigh"
            selected.defaultEffort in selected.efforts -> selected.defaultEffort.orEmpty()
            else -> selected.efforts.firstOrNull().orEmpty()
        }
        val requestedSpeed = if (selected.model == model) speed else ModelSelection(selected.model).speed
        val availableSpeed = requestedSpeed.takeIf { it in composerSpeeds(selected.model, models) } ?: ComposerSpeed.STANDARD
        return ModelSelection(selected.model, reasoning, availableSpeed)
    }
}

class ModelPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("model-defaults-v1", Context.MODE_PRIVATE)

    /** One upgrade migration. A pending choice survives failed connections and process death. */
    fun migrate(connections: List<SavedConnection>) {
        if (prefs.getBoolean(MIGRATION, false)) return
        val edit = prefs.edit()
        val defaults = ModelSelection()
        connections.forEach { connection ->
            val prefix = scope(connection)
            edit.putString("$prefix:model", defaults.model)
                .putString("$prefix:speed", defaults.speed.serviceTier)
                .putBoolean("$prefix:initial-choice", true)
        }
        check(edit.putBoolean(MIGRATION, true).commit()) { "无法保存默认模型，请检查设备存储空间。" }
    }

    fun initialChoicePending(connection: SavedConnection?): Boolean {
        val prefix = connection?.let(::scope) ?: return false
        return prefs.getBoolean("$prefix:initial-choice", !prefs.contains("$prefix:model"))
    }

    /** Only an explicit choice or an accepted turn consumes the initial selection. */
    fun initialChoiceUsed(connection: SavedConnection) {
        if (initialChoicePending(connection))
            check(prefs.edit().putBoolean("${scope(connection)}:initial-choice", false).commit()) { "无法保存默认模型，请检查设备存储空间。" }
    }

    fun read(connection: SavedConnection?): ModelSelection {
        val prefix = connection?.let(::scope) ?: return ModelSelection()
        val selection = ModelSelection(
            prefs.getString("$prefix:model", null)?.takeIf { it.isNotBlank() } ?: GPT_6_2_SOL,
            prefs.getString("$prefix:effort", null) ?: "xhigh",
        )
        return selection.copy(speed = ComposerSpeed.fromServiceTier(prefs.getString("$prefix:speed", null)) ?: selection.speed)
    }

    fun save(connection: SavedConnection, selection: ModelSelection) {
        val prefix = scope(connection)
        // Persist a complete choice together so all three settings survive an app restart.
        check(prefs.edit().putString("$prefix:model", selection.model)
            .putString("$prefix:effort", selection.effort).putString("$prefix:speed", selection.speed.serviceTier)
            .remove("$prefix:initial-choice").commit()) { "无法保存默认模型，请检查设备存储空间。" }
    }

    private fun scope(connection: SavedConnection): String = connection.identityKey

    private companion object { const val MIGRATION = "sol-ultrafast-upgrade-v2" }
}
