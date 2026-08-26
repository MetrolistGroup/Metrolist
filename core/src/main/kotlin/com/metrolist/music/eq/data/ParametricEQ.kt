package com.metrolist.music.eq.data

import kotlinx.serialization.Serializable

/**
 * Represents a single parametric EQ filter/band
 * Supports APO Parametric EQ filters
 */
@Serializable
data class ParametricEQBand(
    val frequency: Double,                      // Center frequency in Hz
    val gain: Double,                           // Gain in dB
    val q: Double = 1.41,                       // Q factor (bandwidth) - default to sqrt(2)
    val filterType: FilterType = FilterType.PK, // Filter type
    val enabled: Boolean = true                 // Whether this band is active
)

/**
 * Represents a complete parametric EQ configuration for a headphone
 * Parsed from AutoEQ preset files (...ParametricEQ.txt)
 */
@Serializable
data class ParametricEQ(
    val preamp: Double,                         // Preamp/gain in dB (to prevent clipping)
    val bands: List<ParametricEQBand>,          // List of EQ bands
    val metadata: Map<String, String> = emptyMap()  // Additional metadata from file
) {
    companion object {
        const val MAX_BANDS = 20  // Maximum bands supported by the implementation
    }
}