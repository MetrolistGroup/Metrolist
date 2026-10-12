package com.sydforum.musicplay.ui.screens.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sydforum.musicplay.eq.EqualizerService
import com.sydforum.musicplay.eq.data.EQProfileRepository
import com.sydforum.musicplay.eq.data.ParametricEQParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

/**
 * ViewModel for EQ Screen
 * Manages EQ profiles and applies them to the EqualizerService
 */
@HiltViewModel
class EQViewModel @Inject constructor(
    private val eqProfileRepository: EQProfileRepository,
    private val equalizerService: EqualizerService
) : ViewModel() {

    private val _state = MutableStateFlow(EQState())
    val state: StateFlow<EQState> = _state.asStateFlow()

    init {
        loadProfiles()
    }

    /**
     * Load all saved EQ profiles (sorted: AutoEQ first, then custom)
     */
    private fun loadProfiles() {
        // Observe profiles changes
        viewModelScope.launch {
            eqProfileRepository.profiles.collect { _ ->
                val sortedProfiles = eqProfileRepository.getSortedProfiles()
                _state.update {
                    it.copy(profiles = sortedProfiles)
                }
            }
        }

        // Observe active profile changes separately
        viewModelScope.launch {
            eqProfileRepository.activeProfile.collect { activeProfile ->
                _state.update {
                    it.copy(activeProfileId = activeProfile?.id)
                }
            }
        }
    }

    /**
     * Select and apply an EQ profile
     * Pass null to disable EQ
     */
    fun selectProfile(profileId: String?) {
        viewModelScope.launch {
            if (profileId == null) {
                // Disable EQ
                equalizerService.disable()
                eqProfileRepository.setActiveProfile(null)
            } else {
                // Apply the selected profile
                val profile = _state.value.profiles.find { it.id == profileId }
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    result.onSuccess {
                        eqProfileRepository.setActiveProfile(profileId)
                    }.onFailure { e ->
                        _state.update { it.copy(error = e.message ?: "Unknown error") }
                    }
                }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Delete an EQ profile
     */
    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            eqProfileRepository.deleteProfile(profileId)
        }
    }

    /**
     * Import a custom EQ profile from a file
     */
    fun importCustomProfile(
        fileName: String,
        inputStream: InputStream,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Read the file content
                val content = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()

                // Parse the ParametricEQ format
                val parametricEQ = ParametricEQParser.parseText(content)

                // Validate the parsed EQ
                val validationErrors = ParametricEQParser.validate(parametricEQ)
                if (validationErrors.isNotEmpty()) {
                    onError(Exception("Invalid EQ file: ${validationErrors.first()}"))
                    return@launch
                }

                // Extract profile name from file name (remove .txt extension)
                val profileName = fileName.removeSuffix(".txt")

                // Import the profile
                eqProfileRepository.importCustomProfile(profileName, parametricEQ)

                _state.update { it.copy(importStatus = "Successfully imported $profileName") }
                onSuccess()
            } catch (e: Exception) {
                onError(Exception("Failed to import EQ profile: ${e.message}"))
            }
        }
    }
}