package com.nanci.codexremote.core

internal const val GPT_6_2_SOL = "gpt-6.2-sol"

enum class ComposerSpeed(val serviceTier: String) {
    STANDARD("default"),
    FAST("priority"),
    ULTRAFAST("ultrafast");

    companion object {
        fun fromServiceTier(value: String?): ComposerSpeed? =
            if (value == "fast") FAST else entries.firstOrNull { it.serviceTier == value }
    }
}

internal fun composerSpeeds(model: String, models: List<ModelInfo>): List<ComposerSpeed> {
    val metadata = models.firstOrNull { it.model == model }?.raw
    val serviceTiers = metadata?.optJSONArray("serviceTiers")?.objects()?.mapNotNull { it.stringOrNull("id") }
    val tierIds = serviceTiers?.takeIf { it.isNotEmpty() }
        ?: metadata?.optJSONArray("additionalSpeedTiers")?.strings().orEmpty()
    val advertised = tierIds.mapNotNull { ComposerSpeed.fromServiceTier(it) }
    // Older registries can omit ultrafast for Sol even though the app-server wire accepts the tier ID.
    return ComposerSpeed.entries.filter { speed ->
        speed == ComposerSpeed.STANDARD || speed in advertised || (model == GPT_6_2_SOL && speed == ComposerSpeed.ULTRAFAST)
    }
}
