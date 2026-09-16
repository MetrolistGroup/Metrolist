package com.metrolist.music.eq.data

import kotlinx.serialization.Serializable

/**
 * Represents a searchable entry in the measurement database.
 * Corresponds to the entries structure in the webapp's entries.json
 */
@Serializable
data class Entry(
    val label: String,          // Display name (headphone name)
    val form: String,           // Form factor: "in-ear", "over-ear", "earbud"
    val rig: String,            // Measurement rig: "HMS II.3", "Bruel & Kjaer 5128", "711", etc.
    val source: String,         // Source: "oratory1990", "crinacle", "HypetheSonics", etc.
    val formDirectory: String   // Actual directory name in filesystem (e.g., "over-ear", "711 in-ear", "Bruel & Kjaer 5128 over-ear")
)
