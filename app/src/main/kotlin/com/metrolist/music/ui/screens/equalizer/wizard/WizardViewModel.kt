package com.metrolist.music.ui.screens.equalizer.wizard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.eq.data.EQProfileRepository
import com.metrolist.music.eq.data.GitHubAutoEqSearch
import com.metrolist.music.eq.data.SavedEQProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for Wizard Screen
 * Handles device search and EQ profile selection using the AutoEQ database
 */
@HiltViewModel
class WizardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eqProfileRepository: EQProfileRepository
) : ViewModel() {

    private val autoEqSearch = GitHubAutoEqSearch(context)

    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        if (autoEqSearch.isDatabaseCached()) {
            downloadDatabase()
        }
    }

    fun downloadDatabase() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val success = autoEqSearch.buildIndex()
            if (!success) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load AutoEQ database"
                    )
                }
            } else {
                _state.update { it.copy(isLoading = false, isDatabaseReady = true) }
                searchModels("")
            }
        }
    }

    // STEP 1: MODEL SELECTION

    fun onModelSearchQueryChanged(query: String) {
        _state.update { it.copy(modelSearchQuery = query) }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            searchModels(query)
        }
    }

    private fun searchModels(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                val groupedEntries = autoEqSearch.searchModels(query)

                val models = groupedEntries.map { (modelName, entriesForModel) ->
                    DeviceModel(
                        id = modelName.lowercase().replace(" ", "_").replace("[^a-z0-9_]".toRegex(), ""),
                        name = modelName,
                        hasMultipleVariants = entriesForModel.size > 1
                    )
                }

                _state.update {
                    it.copy(
                        models = models,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        models = emptyList(),
                        isLoading = false,
                        error = "Failed to search models: ${e.message}"
                    )
                }
            }
        }
    }

    fun onModelSelected(model: DeviceModel) {
        _state.update { it.copy(selectedModel = model) }

        loadVariants(model.id)
    }

    private fun loadVariants(modelId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                val modelName = _state.value.selectedModel?.name ?: ""

                val entries = autoEqSearch.getVariantsForModel(modelName)

                if (entries.isEmpty()) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "No EQ profiles found for $modelName"
                        )
                    }
                    return@launch
                }

                val variants = entries.mapIndexed { index, entry ->
                    val label = entry.label
                    val isANC = label.contains("(ANC", ignoreCase = true) ||
                            label.contains("ANC ON", ignoreCase = true) ||
                            label.contains("ANC Off", ignoreCase = true)
                    val isPadModified = label.contains("velour", ignoreCase = true) ||
                            (label.contains("pad", ignoreCase = true) &&
                            !label.contains("(sample", ignoreCase = true))

                    EQProfileVariant(
                        id = "${modelId}_${index}",
                        modelId = modelId,
                        name = entry.label,
                        variant = if (entries.size > 1) {
                            "${entry.source} - ${entry.rig}"
                        } else {
                            null
                        },
                        isANC = isANC,
                        isPadModified = isPadModified,
                        source = entry.source,
                        rig = entry.rig,
                        description = "Measured by ${entry.source} on ${entry.rig} (${entry.form})"
                    )
                }

                _state.update {
                    it.copy(
                        variants = variants,
                        currentStep = WizardStep.VARIANT_SELECTION,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        variants = emptyList(),
                        isLoading = false,
                        error = "Failed to load variants: ${e.message}"
                    )
                }
            }
        }
    }

    // STEP 2: VARIANT SELECTION

    fun onVariantToggled(variantId: String) {
        _state.update { currentState ->
            val selectedIds = currentState.selectedVariantIds.toMutableSet()
            if (selectedIds.contains(variantId)) {
                selectedIds.remove(variantId)
            } else {
                selectedIds.add(variantId)
            }
            currentState.copy(selectedVariantIds = selectedIds)
        }
    }

    // NAVIGATION

    fun onNextClicked() {
        when (_state.value.currentStep) {
            WizardStep.MODEL_SELECTION -> {
                // Already handled in onModelSelected
            }
            WizardStep.VARIANT_SELECTION -> {
                completeWizard()
            }
        }
    }

    fun onBackClicked() {
        _state.update { currentState ->
            when (currentState.currentStep) {
                WizardStep.VARIANT_SELECTION -> {
                    searchModels(currentState.modelSearchQuery)
                    currentState.copy(
                        currentStep = WizardStep.MODEL_SELECTION,
                        variants = emptyList(),
                        selectedVariantIds = emptySet()
                    )
                }
                else -> currentState
            }
        }
    }

    private fun completeWizard() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                val currentState = _state.value
                val selectedVariants = currentState.variants.filter {
                    currentState.selectedVariantIds.contains(it.id)
                }

                val modelName = currentState.selectedModel?.name ?: "Unknown"

                val profiles = mutableListOf<SavedEQProfile>()
                val entries = autoEqSearch.getVariantsForModel(modelName)

                for (variant in selectedVariants) {
                    try {
                        val matchingEntry = entries.find {
                            it.label == variant.name &&
                                    it.source == variant.source &&
                                    it.rig == variant.rig
                        }

                        if (matchingEntry != null) {
                            val parametricEQ = autoEqSearch.loadEQ(matchingEntry)

                            if (parametricEQ != null) {
                                val profile = SavedEQProfile(
                                    id = variant.id,
                                    name = variant.name,
                                    deviceModel = variant.name,
                                    source = variant.source,
                                    rig = variant.rig,
                                    bands = parametricEQ.bands,
                                    preamp = parametricEQ.preamp,
                                    isCustom = true,
                                    isActive = false
                                )
                                profiles.add(profile)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load EQ for variant %s", variant.id)
                    }
                }

                if (profiles.isNotEmpty()) {
                    eqProfileRepository.saveProfiles(profiles)

                    _state.update {
                        it.copy(
                            isLoading = false,
                            isComplete = true
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to load any EQ profiles for the selected variants"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to save profiles: ${e.message}"
                    )
                }
            }
        }
    }
}
