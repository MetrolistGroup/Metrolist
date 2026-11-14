package com.metrolist.android.playback

import com.metrolist.shared.equalizer.EqualizerConfiguration
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.FilterType
import com.metrolist.shared.equalizer.ParametricEqBand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOffloadEligibilityTest {
    private val flatProfile = EqualizerProfile("flat", "flat", listOf(ParametricEqBand(1_000.0, 0.0)))
    private val eligible =
        AudioOffloadEligibility(
            equalizer = EqualizerConfiguration(flatProfile, 0.0),
            normalizationGain = 1f,
            skipSilence = false,
            speed = 1f,
            pitch = 1f,
            crossfade = false,
            meterActive = false,
            audioResourceAvailable = true,
        )

    @Test
    fun flatOrBypassedEqWithAttenuationCanOffload() {
        assertTrue(eligible.canOffload)
        assertTrue(eligible.copy(equalizer = EqualizerConfiguration(null, 0.0), normalizationGain = 0.7f).canOffload)
        assertTrue(
            eligible
                .copy(
                    equalizer =
                        EqualizerConfiguration(
                            flatProfile.copy(bands = listOf(ParametricEqBand(1_000.0, 6.0, enabled = false))),
                            0.0,
                        ),
                ).canOffload,
        )
    }

    @Test
    fun everyPcmRequirementDisablesOffload() {
        val ineligible =
            listOf(
                eligible.copy(normalizationGain = 1.1f),
                eligible.copy(normalizationGain = Float.NaN),
                eligible.copy(skipSilence = true),
                eligible.copy(speed = 1.25f),
                eligible.copy(speed = 0.95f),
                eligible.copy(pitch = 0.9f),
                eligible.copy(crossfade = true),
                eligible.copy(meterActive = true),
                eligible.copy(audioResourceAvailable = false),
                eligible.copy(outputFailed = true),
                eligible.copy(equalizer = EqualizerConfiguration(flatProfile, 1.0)),
                eligible.copy(equalizer = EqualizerConfiguration(flatProfile.copy(preamp = -1.0), 0.0)),
                eligible.copy(equalizer = EqualizerConfiguration(flatProfile.copy(bands = listOf(ParametricEqBand(1_000.0, 1.0))), 0.0)),
                eligible.copy(
                    equalizer =
                        EqualizerConfiguration(
                            flatProfile.copy(bands = listOf(ParametricEqBand(1_000.0, 0.0, filterType = FilterType.LPQ))),
                            0.0,
                        ),
                ),
            )
        ineligible.forEach { assertFalse(it.canOffload) }
    }
}
