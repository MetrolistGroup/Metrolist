package com.metrolist.music.ui.screens.equalizer.wizard

/**
 * UI State for Wizard Screen
 * Manages the two-step device setup flow
 */
data class WizardState(
    val currentStep: WizardStep = WizardStep.MODEL_SELECTION,

    // Step 1: Model Selection
    val modelSearchQuery: String = "",
    val models: List<DeviceModel> = emptyList(),
    val selectedModel: DeviceModel? = null,

    // Step 2: Variant Selection
    val variants: List<EQProfileVariant> = emptyList(),
    val selectedVariantIds: Set<String> = emptySet(),

    // UI state flags
    val isDatabaseReady: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isComplete: Boolean = false
) {
    val canProceed: Boolean
        get() = when (currentStep) {
            WizardStep.MODEL_SELECTION -> selectedModel != null
            WizardStep.VARIANT_SELECTION -> selectedVariantIds.isNotEmpty()
        }

    val canGoBack: Boolean
        get() = currentStep != WizardStep.MODEL_SELECTION
}

enum class WizardStep {
    MODEL_SELECTION,
    VARIANT_SELECTION
}

/**
 * Device Model
 */
data class DeviceModel(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val hasMultipleVariants: Boolean = false
)

/**
 * EQ Profile Variant
 */
data class EQProfileVariant(
    val id: String,
    val modelId: String,
    val name: String,
    val variant: String? = null,
    val isANC: Boolean = false,
    val isPadModified: Boolean = false,
    val description: String? = null,
    val source: String = "unknown",
    val rig: String = "unknown"
) {
    val displayName: String
        get() = buildString {
            append(name)
            if (variant != null) {
                append(" - $variant")
            }
            when {
                isANC -> append(" (ANC)")
                isPadModified -> append(" (Velour pads)")
            }
        }

    val sourceDisplay: String
        get() = if (source != "unknown") "Source: $source" else ""

    val rigDisplay: String
        get() = if (rig != "unknown") "Rig: $rig" else ""
}
