package com.metrolist.music.eq.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved EQ Profile with metadata
 */
@Serializable
data class SavedEQProfile(
    val id: String,                       // Unique identifier
    val name: String,                     // Display name
    val deviceModel: String,              // e.g., "Sony WH-1000XM4"
    val bands: List<ParametricEQBand>,    // EQ bands
    val preamp: Double = 0.0,             // Preamp gain in dB
    val source: String = "unknown",       // Measurement source (e.g., oratory1990, crinacle)
    val rig: String = "unknown",          // Measurement rig (e.g., HMS II.3, GRAS 43AG-7)
    val isCustom: Boolean = false,        // Whether this is a custom imported profile
    val isActive: Boolean = false,        // Whether this profile is currently active
    val addedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Repository for managing EQ profiles
 * Handles saving, loading, and activating EQ profiles
 */
@Singleton
class EQProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "nanosonic_eq_profiles",
        Context.MODE_PRIVATE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _profiles = MutableStateFlow<List<SavedEQProfile>>(emptyList())
    val profiles: StateFlow<List<SavedEQProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<SavedEQProfile?>(null)
    val activeProfile: StateFlow<SavedEQProfile?> = _activeProfile.asStateFlow()

    companion object {
        private const val TAG = "EQProfileRepository"
        private const val KEY_PROFILES = "eq_profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }

    init {
        loadProfiles()
    }

    /**
     * Load all saved profiles from SharedPreferences
     */
    private fun loadProfiles() {
        try {
            val profilesJson = prefs.getString(KEY_PROFILES, null)
            if (profilesJson != null) {
                val loadedProfiles = json.decodeFromString<List<SavedEQProfile>>(profilesJson)
                _profiles.value = loadedProfiles

                // Load active profile
                val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
                _activeProfile.value = loadedProfiles.find { it.id == activeId }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error loading EQ profiles")
            _profiles.value = emptyList()
            _activeProfile.value = null
        }
    }

    /**
     * Save a new EQ profile
     */
    suspend fun saveProfile(profile: SavedEQProfile) = withContext(Dispatchers.IO) {
        val currentProfiles = _profiles.value.toMutableList()

        // Check if profile with same ID already exists
        val existingIndex = currentProfiles.indexOfFirst { it.id == profile.id }

        if (existingIndex >= 0) {
            // Update existing profile
            currentProfiles[existingIndex] = profile
        } else {
            // Add new profile
            currentProfiles.add(profile)
        }

        if (profile.id == _activeProfile.value?.id) {
            _activeProfile.value = profile
        }

        // Save to SharedPreferences
        val profilesJson = json.encodeToString<List<SavedEQProfile>>(currentProfiles)
        prefs.edit { putString(KEY_PROFILES, profilesJson) }

        _profiles.value = currentProfiles
    }

    /**
     * Save multiple profiles at once (useful for wizard)
     */
    suspend fun saveProfiles(profiles: List<SavedEQProfile>) = withContext(Dispatchers.IO) {
        val currentProfiles = _profiles.value.toMutableList()

        val activeId = _activeProfile.value?.id

        profiles.forEach { newProfile ->
            val existingIndex = currentProfiles.indexOfFirst { it.id == newProfile.id }
            if (existingIndex >= 0) {
                currentProfiles[existingIndex] = newProfile
            } else {
                currentProfiles.add(newProfile)
            }
            if (newProfile.id == activeId) {
                _activeProfile.value = newProfile
            }
        }

        val profilesJson = json.encodeToString<List<SavedEQProfile>>(currentProfiles)
        prefs.edit { putString(KEY_PROFILES, profilesJson) }

        _profiles.value = currentProfiles
    }

    /**
     * Delete a profile
     */
    suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
        val currentProfiles = _profiles.value.toMutableList()
        currentProfiles.removeAll { it.id == profileId }

        val profilesJson = json.encodeToString<List<SavedEQProfile>>(currentProfiles)
        prefs.edit { putString(KEY_PROFILES, profilesJson) }

        // If deleted profile was active, clear active profile
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = null
            prefs.edit { remove(KEY_ACTIVE_PROFILE_ID) }
        }

        _profiles.value = currentProfiles
    }

    /**
     * Set a profile as active (only one profile can be active at a time)
     * Pass null to deactivate all profiles
     */
    suspend fun setActiveProfile(profileId: String?) = withContext(Dispatchers.IO) {
        val currentProfiles = _profiles.value

        if (profileId == null) {
            // Deactivate all profiles
            _activeProfile.value = null
            prefs.edit { remove(KEY_ACTIVE_PROFILE_ID) }
        } else {
            val profile = currentProfiles.find { it.id == profileId }
            _activeProfile.value = profile
            prefs.edit { putString(KEY_ACTIVE_PROFILE_ID, profileId) }
        }
    }

    /**
     * Get all saved profiles
     */
    fun getAllProfiles(): List<SavedEQProfile> {
        return _profiles.value
    }

    /**
     * Get active profile
     */
    fun getActiveProfile(): SavedEQProfile? {
        return _activeProfile.value
    }

    /**
     * Import a custom EQ profile from ParametricEQ data
     */
    suspend fun importCustomProfile(
        name: String,
        parametricEQ: ParametricEQ
    ) = withContext(Dispatchers.IO) {
        // Generate unique ID for custom profile
        val id = "custom_${System.currentTimeMillis()}_${name.hashCode()}"

        val customProfile = SavedEQProfile(
            id = id,
            name = name,
            deviceModel = name,
            bands = parametricEQ.bands,  // Already ParametricEQBand
            preamp = parametricEQ.preamp,
            isActive = false,
            isCustom = true // Ensure this flag is set!
        )

        saveProfile(customProfile)
    }

    /**
     * Get profiles sorted by type: AutoEQ first, then custom profiles
     * Within each group, sort by timestamp (newest first)
     */
    fun getSortedProfiles(): List<SavedEQProfile> {
        // Only custom profiles are supported now
        return _profiles.value
            .filter { it.isCustom }
            .sortedByDescending { it.addedTimestamp }
    }
}
