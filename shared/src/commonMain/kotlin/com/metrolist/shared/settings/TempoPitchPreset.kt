package com.metrolist.shared.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class TempoPitchPreset(
    val id: String,
    val name: String,
    val speed: Float,
    val pitch: Float,
) {
    internal val isValid: Boolean
        get() = id.isNotBlank() && name.isNotBlank() && speed in 0.5f..2f && pitch in 0.5f..2f

    fun canApply(
        speedEnabled: Boolean,
        varispeed: Boolean,
        pitchEnabled: Boolean = true,
    ): Boolean = isValid && speedEnabled && (!varispeed || speed == pitch) && (pitchEnabled || pitch == 1f)

    fun apply(
        speedEnabled: Boolean,
        varispeed: Boolean,
        onSpeedChange: (Float) -> Unit,
        onPitchChange: ((Float) -> Unit)?,
    ) {
        if (!canApply(speedEnabled, varispeed, onPitchChange != null)) return
        onSpeedChange(speed)
        onPitchChange?.invoke(pitch)
    }

    companion object {
        internal fun decode(value: String): List<TempoPitchPreset> {
            val entries = runCatching { Json.parseToJsonElement(value) as? JsonArray }.getOrNull() ?: return emptyList()
            return entries
                .mapNotNull { entry ->
                    runCatching { Json.decodeFromJsonElement<TempoPitchPreset>(entry) }.getOrNull()?.takeIf { it.isValid }
                }.distinctBy { it.id }
        }
    }
}
