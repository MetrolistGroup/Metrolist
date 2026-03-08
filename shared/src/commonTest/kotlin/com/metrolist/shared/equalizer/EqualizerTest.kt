package com.metrolist.shared.equalizer

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqualizerTest {
    private var spectrumConsumers = 0

    private fun startSpectrumConsumer() {
        AudioSpectrumMeter.addConsumer()
        spectrumConsumers++
    }

    private fun stopSpectrumConsumer() {
        AudioSpectrumMeter.removeConsumer()
        spectrumConsumers--
    }

    private fun <T> withSpectrumConsumer(block: () -> T): T {
        startSpectrumConsumer()
        return try {
            block()
        } finally {
            stopSpectrumConsumer()
        }
    }

    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() {
        while (spectrumConsumers > 0) stopSpectrumConsumer()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun importingSameCatalogEntryKeepsItsPreviouslySavedEdits() {
        val catalogProfile = EqualizerProfile("catalog-entry", "Catalog", createEqualizerBands())
        EqualizerProfiles.importProfile(catalogProfile)
        val importedId = requireNotNull(EqualizerProfiles.activeProfileId)
        val current = requireNotNull(EqualizerProfiles.activeProfile)
        EqualizerProfiles.editCurrent(
            current.copy(bands = current.bands.mapIndexed { index, band -> if (index == 0) band.copy(gain = 8.0) else band }),
        )
        EqualizerProfiles.saveCurrent("Saved catalog profile")

        EqualizerProfiles.importProfile(catalogProfile)

        assertTrue(EqualizerProfiles.activeProfileId != importedId)
        assertEquals(
            8.0,
            EqualizerProfiles.profiles
                .first { it.id == importedId }
                .bands
                .first()
                .gain,
        )
    }

    @Test
    fun gainEditsKeepImportedPrecisionUntilGainChangesAndAllowTheFullRange() {
        val imported = ParametricEqBand(1000.0, 18.3)
        assertEquals(imported, imported.withAdjustedGain(imported.gain))
        assertEquals(29.5, imported.withAdjustedGain(29.3).gain)
        assertEquals(30.0, imported.withAdjustedGain(40.0).gain)
        assertEquals(-30.0, imported.withAdjustedGain(-40.0).gain)
        for (type in listOf(FilterType.LPQ, FilterType.HPQ)) {
            val passFilter = imported.copy(filterType = type)
            assertEquals(passFilter, passFilter.withAdjustedGain(6.0))
        }
    }

    @Test
    fun bandEditsClampToAudibleFrequenciesAndSupportedGainWithoutChangingOtherParameters() {
        val band = ParametricEqBand(1000.0, 18.3, q = 2.5, filterType = FilterType.HSC)
        val profile = EqualizerProfile("limits", "Limits", listOf(band), preamp = -6.0)
        val low = profile.withAdjustedBand(0, -60.0, -100.0, 20_000.0)
        assertEquals(profile.copy(bands = listOf(band.copy(frequency = 20.0, gain = -30.0))), low)
        val high = low.withAdjustedBand(0, 60.0, 100_000.0, 20_000.0)
        assertEquals(profile.copy(bands = listOf(band.copy(frequency = 20_000.0, gain = 30.0))), high)

        val lowRate = profile.withAdjustedBand(0, band.gain, 20_000.0, 7600.0)
        assertEquals(band.copy(frequency = 7600.0), lowRate.bands.single())
        assertEquals(profile, profile.withAdjustedBand(0, 0.0, Double.NaN, 20_000.0))
        assertEquals(band, band.withAdjustedGain(Double.NaN))
    }

    @Test
    fun bandEditsStopAtFrequencyNeighboursAndRetainStorageIndices() {
        val bands =
            listOf(
                ParametricEqBand(1000.0, 3.0),
                ParametricEqBand(500.0, 1.0),
                ParametricEqBand(100.0, -2.0, enabled = false),
            )
        val profile = EqualizerProfile("order", "Order", bands)
        assertEquals(listOf(2, 1, 0), profile.bandsByFrequency.map { it.index })
        val high = profile.withAdjustedBand(1, 18.0, 20_000.0, 20_000.0)
        assertEquals(bands[1].copy(frequency = 999.0, gain = 18.0), high.bands[1])
        val low = high.withAdjustedBand(1, -24.0, 20.0, 20_000.0)
        assertEquals(bands[1].copy(frequency = 101.0, gain = -24.0), low.bands[1])
        assertEquals(bands[0], low.bands[0])
        assertEquals(bands[2], low.bands[2])
        assertEquals(listOf(2, 1, 0), low.bandsByFrequency.map { it.index })
        assertEquals(profile, profile.withAdjustedBand(2, 10.0, 800.0, 20_000.0))
    }

    @Test
    fun orderedBandEditsPreserveImportedFilterHistoryAndResetTargets() {
        val bands = listOf(ParametricEqBand(1000.0, 6.0), ParametricEqBand(100.0, -3.3))
        val profile = EqualizerProfile("imported", "Imported", bands)
        EqualizerProfiles.add(profile)
        val eq = ParametricEqualizer(48_000, 1).apply { update(profile) }
        eq.process(0.1, 0)
        val edited = profile.withAdjustedBand(0, 6.0, 1100.0, 20_000.0)
        EqualizerProfiles.editCurrent(edited)
        eq.update(edited)
        assertTrue(abs(eq.process(0.0, 0)) > 1e-6)
        assertEquals(listOf(1, 0), edited.bandsByFrequency.map { it.index })
        EqualizerProfiles.resetBand(0)
        assertEquals(bands, EqualizerProfiles.activeProfile?.bands)
    }

    @Test
    fun editsPreserveCloselySpacedImportedFiltersAndPassFilterGains() {
        val bands = listOf(ParametricEqBand(100.0, 0.0, filterType = FilterType.HPQ), ParametricEqBand(100.5, 4.3))
        val profile = EqualizerProfile("close", "Close", bands)
        assertEquals(profile, profile.withAdjustedBand(0, 30.0, 1000.0, 20_000.0))
        assertEquals(profile, profile.withAdjustedBand(1, 4.3, 20.0, 20_000.0))
        val moved = profile.withAdjustedBand(0, 30.0, 20.0, 20_000.0)
        assertEquals(bands[0].copy(frequency = 20.0), moved.bands[0])
    }

    @Test
    fun importedBandGainsKeepTheirFullRangeWhenSavedAndReloaded() {
        val imported =
            EqualizerProfiles.importText(
                "Imported.txt",
                "Filter 1: ON PK Fc 100 Hz Gain 24 dB Q 1\nFilter 2: ON PK Fc 1000 Hz Gain -24 dB Q 1",
            )
        EqualizerProfiles.saveCurrent("Saved")
        EqualizerProfiles.setActive(null)
        EqualizerProfiles.setActive(imported.id)
        assertEquals(listOf(24.0, -24.0), EqualizerProfiles.activeProfile?.bands?.map { it.gain })
    }

    @Test
    fun factoryPresetsAreCleanUntilBandsOrPreampChange() {
        for (preset in EqualizerPreset.entries) {
            EqualizerProfiles.activatePreset(preset, "Localized name")
            assertFalse(EqualizerProfiles.hasUnsavedChanges)
            EqualizerProfiles.setPreamp(-2.0)
            assertTrue(EqualizerProfiles.hasUnsavedChanges)
            EqualizerProfiles.setPreamp(0.0)
            assertFalse(EqualizerProfiles.hasUnsavedChanges)
            val profile = checkNotNull(EqualizerProfiles.activeProfile)
            EqualizerProfiles.editCurrent(profile.copy(bands = profile.bands.map { it.copy(gain = it.gain + 1) }))
            assertTrue(EqualizerProfiles.hasUnsavedChanges)
        }
    }

    @Test
    fun resetBandRestoresSavedGainFrequencyAndQ() {
        val original = ParametricEqBand(1000.0, 5.0, q = 1.7)
        EqualizerProfiles.add(EqualizerProfile("saved", "Saved", listOf(original)))
        EqualizerProfiles.editCurrent(
            requireNotNull(EqualizerProfiles.activeProfile).copy(
                bands = listOf(original.copy(frequency = 1800.0, gain = 9.0, q = 2.2)),
            ),
        )

        assertTrue(EqualizerProfiles.bandHasChanges(0))
        EqualizerProfiles.resetBand(0)

        assertEquals(original, EqualizerProfiles.activeProfile?.bands?.single())
        assertFalse(EqualizerProfiles.bandHasChanges(0))
    }

    @Test
    fun defaultFlatPresetStartsDisabledAndCanBeEnabledWithoutLosingSelection() {
        Settings.shared.audioBoostDb = 4.5f
        EqualizerProfiles.ensureDefaultProfile()

        val selected = checkNotNull(EqualizerProfiles.activeProfile)
        assertEquals(EqualizerPreset.FLAT.profileId, selected.id)
        assertTrue(selected.bands.all { it.gain == 0.0 })
        assertEquals(4.5, selected.outputGainDb)
        assertFalse(EqualizerProfiles.isEnabled)
        assertNull(EqualizerProfiles.configuration.profile)

        EqualizerProfiles.setEnabled(true)
        EqualizerProfiles.ensureDefaultProfile()

        assertTrue(EqualizerProfiles.isEnabled)
        assertEquals(selected, EqualizerProfiles.configuration.profile)
    }

    @Test
    fun defaultProfileInitializationPreservesExistingSelectionAndDisabledState() {
        val existing =
            EqualizerProfiles.createCustom("Existing").also {
                EqualizerProfiles.setEnabled(false)
            }

        EqualizerProfiles.ensureDefaultProfile()

        assertEquals(existing.id, EqualizerProfiles.activeProfileId)
        assertFalse(EqualizerProfiles.isEnabled)
    }

    @Test
    fun enablingWithoutASelectedProfileActivatesTheFirstPresetAndKeepsPreamp() {
        EqualizerProfiles.createCustom("Saved")
        EqualizerProfiles.setActive(null)
        EqualizerProfiles.setPreamp(-2.5)
        EqualizerProfiles.setEnabled(false)

        EqualizerProfiles.setEnabled(true)

        assertTrue(EqualizerProfiles.isEnabled)
        assertEquals(EqualizerPreset.entries.first().profileId, EqualizerProfiles.configuration.profile?.id)
        assertEquals(-2.5, EqualizerProfiles.configuration.preampDb)
        assertEquals(1, EqualizerProfiles.profiles.size)
    }

    @Test
    fun bandLayoutUsesRequestedCountAndLogarithmicBandwidth() {
        for (count in listOf(1, 5, 10, 20)) {
            val bands = createEqualizerBands(count, 40.0, 16_000.0)
            val ratio = (16_000.0 / 40.0).pow(1.0 / count)
            assertEquals(count, bands.size)
            bands.forEach { band ->
                assertTrue(band.frequency in 40.0..16_000.0)
                assertEquals(sqrt(ratio) / (ratio - 1.0), band.q, 1e-10)
            }
            bands.zipWithNext().forEach { (before, after) ->
                assertEquals(ratio, after.frequency / before.frequency, 1e-10)
            }
        }
    }

    @Test
    fun invalidBandLayoutsAreRejected() {
        assertFailsWith<IllegalArgumentException> { createEqualizerBands(0) }
        assertFailsWith<IllegalArgumentException> { createEqualizerBands(MAX_EQUALIZER_BANDS + 1) }
        assertFailsWith<IllegalArgumentException> { createEqualizerBands(minFrequency = 0.0) }
        assertFailsWith<IllegalArgumentException> { createEqualizerBands(maxFrequency = Double.NaN) }
    }

    @Test
    fun invalidFiltersCannotPoisonAudioState() {
        val profile =
            EqualizerProfile(
                "invalid",
                "invalid",
                listOf(
                    ParametricEqBand(0.0, 6.0),
                    ParametricEqBand(1000.0, Double.NaN),
                    ParametricEqBand(1000.0, 6.0, q = 0.0),
                    ParametricEqBand(1000.0, 6.0, q = Double.POSITIVE_INFINITY),
                ),
            )
        val eq = ParametricEqualizer(48_000, 1).apply { update(profile) }
        assertEquals(0.1, eq.process(0.1, 0))
        assertEquals(0.0, eq.responseDb(1000.0))
    }

    @Test
    fun editedFrequenciesAndBandCountSurviveSavingAndReloading() {
        val profile = EqualizerProfiles.createCustom("Custom")
        val bands =
            createEqualizerBands(5).mapIndexed { index, band ->
                band.copy(frequency = band.frequency + index, q = band.q + index * 0.1, gain = index.toDouble())
            }
        EqualizerProfiles.editCurrent(profile.copy(bands = bands))
        val saved = EqualizerProfiles.saveCurrent("Edited")
        EqualizerProfiles.setActive(null)
        EqualizerProfiles.setActive(saved.id)
        assertEquals(bands, EqualizerProfiles.activeProfile?.bands)
    }

    @Test
    fun addingAndRemovingBandsPreservesExistingFiltersAndLimitsCount() {
        val profile = EqualizerProfiles.createCustom("Custom")
        EqualizerProfiles.addBand(20.0, 20_000.0)
        assertEquals(profile.bands, EqualizerProfiles.activeProfile?.bands?.dropLast(1))
        val added = checkNotNull(EqualizerProfiles.activeProfile).bands.last()
        assertTrue(added.frequency in 20.0..20_000.0 && added.q.isFinite() && added.q > 0.0)
        EqualizerProfiles.removeBand(profile.bands.size)
        assertEquals(profile.bands, EqualizerProfiles.activeProfile?.bands)
        repeat(MAX_EQUALIZER_BANDS) { EqualizerProfiles.addBand(20.0, 20_000.0) }
        assertEquals(MAX_EQUALIZER_BANDS, EqualizerProfiles.activeProfile?.bands?.size)
        repeat(MAX_EQUALIZER_BANDS) { EqualizerProfiles.removeBand(0) }
        assertTrue(checkNotNull(EqualizerProfiles.activeProfile).bands.isEmpty())
        EqualizerProfiles.addBand(20.0, 20_000.0)
        assertEquals(
            sqrt(20.0 * 20_000.0),
            EqualizerProfiles.activeProfile
                ?.bands
                ?.single()
                ?.frequency,
        )
    }

    @Test
    fun frequencyEditsPreserveFilterHistory() {
        val profile = EqualizerProfile("history", "history", listOf(ParametricEqBand(1000.0, 6.0)))
        val eq = ParametricEqualizer(48_000, 1).apply { update(profile) }
        eq.process(0.1, 0)
        eq.update(profile.copy(bands = listOf(profile.bands.single().copy(frequency = 1100.0))))
        assertTrue(abs(eq.process(0.0, 0)) > 1e-6)
    }

    @Test
    fun parsesAutoEqProfile() {
        val (preamp, bands) =
            parseParametricEq(
                """
                Preamp: -6.0 dB
                Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
                Filter 2: ON PK Fc 180 Hz Gain -3.3 dB Q 0.57
                Filter 3: OFF PK Fc 900 Hz Gain 3.0 dB Q 1.00
                Filter 4: ON HSC Fc 10000 Hz Gain -1.1 dB Q 0.70
                Filter 5: ON HP Fc 20 Hz
                """.trimIndent(),
            )!!
        assertEquals(-6.0, preamp)
        assertEquals(listOf(FilterType.LSC, FilterType.PK, FilterType.HSC, FilterType.HPQ), bands.map { it.filterType })
        assertEquals(ParametricEqBand(180.0, -3.3, 0.57, FilterType.PK), bands[1])
    }

    @Test
    fun rejectsNonProfileText() {
        assertNull(parseParametricEq("hello world"))
        assertNull(parseParametricEq("Filter 1: ON PK Fc 100 Hz Gain 90 dB Q 1"))
    }

    @Test
    fun importedGainLimitsMatchTheEditorAndFilterResponse() {
        for (gain in listOf(-30.0, 30.0)) {
            val (preamp, bands) = checkNotNull(parseParametricEq("Filter 1: ON PK Fc 1000 Hz Gain $gain dB Q 1"))
            val eq = ParametricEqualizer(48_000, 1).apply { update(EqualizerProfile("range", "Range", bands, preamp)) }
            assertEquals(gain, eq.responseDb(1000.0), 1e-6)
        }
        for (gain in listOf(-30.1, 30.1, -60.0, 60.0)) {
            assertNull(parseParametricEq("Filter 1: ON PK Fc 1000 Hz Gain $gain dB Q 1"))
        }
    }

    @Test
    fun migratesLegacyProfilesOnce() {
        val legacyJson =
            """
            [{"id":"custom_1","name":"Unboost bass","deviceModel":"Unboost bass","preamp":-1.5,
              "bands":[{"frequency":100.0,"gain":-4.0,"q":0.7,"filterType":"LSC","enabled":true}],
              "source":"unknown","rig":"unknown","isCustom":true,"isActive":false,"addedTimestamp":10},
             {"id":"sony_0","name":"Sony WH-1000XM4","deviceModel":"Sony WH-1000XM4",
              "bands":[{"frequency":1000.0,"gain":2.0}],"source":"oratory1990","rig":"GRAS 43AG-7",
              "isCustom":true,"addedTimestamp":20}]
            """.trimIndent()

        assertTrue(EqualizerProfiles.migrateLegacy(legacyJson, "custom_1"))

        assertEquals(listOf("sony_0", "custom_1"), EqualizerProfiles.profiles.map { it.id })
        assertEquals("custom_1", EqualizerProfiles.activeProfile?.id)
        assertEquals(-1.5, EqualizerProfiles.activeProfile?.preamp)

        EqualizerProfiles.delete("sony_0")
        assertFalse(EqualizerProfiles.migrateLegacy(legacyJson, "custom_1"))
        assertEquals(listOf("custom_1"), EqualizerProfiles.profiles.map { it.id })
    }

    @Test
    fun unreadableLegacyDataIsNotMigrated() {
        assertFalse(EqualizerProfiles.migrateLegacy("{broken", null))
        assertEquals("", Settings.shared.equalizerProfilesJson)
    }

    @Test
    fun deletingActiveProfileDisablesEqualizer() {
        val profile = EqualizerProfiles.importText("Bass ParametricEQ.txt", "Filter 1: ON PK Fc 100 Hz Gain 3 dB Q 1")
        EqualizerProfiles.setPreamp(6.0)
        assertTrue(EqualizerProfiles.isEnabled)

        EqualizerProfiles.delete(profile.id)

        assertNull(EqualizerProfiles.activeProfile)
        assertTrue(EqualizerProfiles.profiles.isEmpty())
        assertFalse(Settings.shared.equalizerEnabled)
        assertEquals(EqualizerConfiguration(null, 0.0), EqualizerProfiles.configuration)
    }

    @Test
    fun deletingAnotherProfilePreservesDisabledLiveEditsAndPreamp() {
        val other = EqualizerProfiles.createCustom("Other")
        val selected = EqualizerProfiles.createCustom("Selected")
        EqualizerProfiles.editCurrent(selected.copy(bands = listOf(ParametricEqBand(100.0, 3.0))))
        EqualizerProfiles.setPreamp(4.0)
        val edited = EqualizerProfiles.selectedConfiguration
        EqualizerProfiles.setEnabled(false)

        EqualizerProfiles.delete(other.id)

        assertEquals(listOf(selected.id), EqualizerProfiles.profiles.map { it.id })
        assertEquals(edited, EqualizerProfiles.selectedConfiguration)
        assertEquals(EqualizerConfiguration(null, 0.0), EqualizerProfiles.configuration)
        EqualizerProfiles.setEnabled(true)
        assertEquals(edited, EqualizerProfiles.configuration)
    }

    @Test
    fun createsEditableTenBandProfileAndActivatesIt() {
        val profile = EqualizerProfiles.createCustom("Custom EQ")

        assertEquals(10, profile.bands.size)
        assertTrue(profile.bands.all { it.gain == 0.0 && it.enabled })
        assertEquals(profile.id, EqualizerProfiles.activeProfileId)
        assertEquals(profile, EqualizerProfiles.activeProfile)
    }

    @Test
    fun profileCacheUsesSerializedContentInsteadOfStringIdentity() {
        EqualizerProfiles.importText("Bass ParametricEQ.txt", "Filter 1: ON PK Fc 100 Hz Gain 3 dB Q 1")
        val first = EqualizerProfiles.profiles
        val serialized = Settings.shared.equalizerProfilesJson
        val equivalentCopy = serialized.toCharArray().concatToString()
        assertFalse(serialized === equivalentCopy)

        Settings.shared.equalizerProfilesJson = equivalentCopy

        assertTrue(first === EqualizerProfiles.profiles)
    }

    @Test
    fun preampSnapsToHalfDecibelStepsAndSurvivesNewCustomProfile() {
        EqualizerProfiles.setPreamp(-0.9999995)
        assertEquals(-1.0, EqualizerProfiles.selectedConfiguration.preampDb)

        EqualizerProfiles.createCustom("Custom EQ")

        assertEquals(-1.0, EqualizerProfiles.selectedConfiguration.preampDb)
    }

    @Test
    fun liveEditsDoNotOverwriteSavedProfilesUntilSaveAndSaveAsKeepsBothConfigurations() {
        val first = EqualizerProfiles.createCustom("Original")
        val bands = first.bands.toMutableList().apply { this[0] = this[0].copy(gain = 4.0) }
        EqualizerProfiles.editCurrent(first.copy(bands = bands))
        EqualizerProfiles.setPreamp(-3.0)
        assertEquals(
            0.0,
            EqualizerProfiles.profiles
                .single()
                .bands[0]
                .gain,
        )
        assertEquals(0.0, EqualizerProfiles.profiles.single().outputGainDb)
        assertTrue(EqualizerProfiles.hasUnsavedChanges)

        val saved = EqualizerProfiles.saveCurrent("Named")
        assertEquals(first.id, saved.id)
        assertEquals(4.0, saved.bands[0].gain)
        assertEquals(-3.0, saved.outputGainDb)
        assertFalse(EqualizerProfiles.hasUnsavedChanges)

        val second = EqualizerProfiles.saveCurrent("Copy", saveAs = true)
        assertTrue(saved.id != second.id)
        assertEquals(2, EqualizerProfiles.profiles.size)
        EqualizerProfiles.setPreamp(6.0)
        assertEquals(-3.0, EqualizerProfiles.profiles.first { it.id == second.id }.outputGainDb)
        EqualizerProfiles.setActive(saved.id)
        assertEquals(-3.0, EqualizerProfiles.configuration.preampDb)
        EqualizerProfiles.setActive(second.id)
        assertEquals(-3.0, EqualizerProfiles.configuration.preampDb)
    }

    @Test
    fun bypassPreservesUnsavedTuningAndRestoresItLive() {
        val profile = EqualizerProfiles.createCustom("Custom EQ")
        EqualizerProfiles.editCurrent(
            profile.copy(
                bands =
                    profile.bands.mapIndexed { index, band ->
                        if (index ==
                            0
                        ) {
                            band.copy(gain = 4.5)
                        } else {
                            band
                        }
                    },
            ),
        )
        EqualizerProfiles.setPreamp(-3.0)

        EqualizerProfiles.setEnabled(false)
        assertEquals(EqualizerConfiguration(null, 0.0), EqualizerProfiles.configuration)
        assertEquals(
            4.5,
            EqualizerProfiles.activeProfile
                ?.bands
                ?.first()
                ?.gain,
        )
        assertEquals(-3.0, EqualizerProfiles.selectedConfiguration.preampDb)
        assertTrue(EqualizerProfiles.hasUnsavedChanges)

        EqualizerProfiles.saveCurrent("Saved while bypassed")
        assertFalse(EqualizerProfiles.isEnabled)
        assertEquals(EqualizerConfiguration(null, 0.0), EqualizerProfiles.configuration)

        EqualizerProfiles.setEnabled(true)
        assertEquals(
            4.5,
            EqualizerProfiles.configuration.profile
                ?.bands
                ?.first()
                ?.gain,
        )
        assertEquals(-3.0, EqualizerProfiles.configuration.preampDb)
    }

    @Test
    fun existingPreampWithoutAProfileRemainsActiveUntilBypassed() {
        Settings.shared.audioBoostDb = 6f
        EqualizerProfiles.setEnabled(true)
        assertEquals(6.0, EqualizerProfiles.configuration.preampDb)
        assertTrue(EqualizerProfiles.isEnabled)

        EqualizerProfiles.setEnabled(false)
        assertEquals(0.0, EqualizerProfiles.configuration.preampDb)
        assertEquals(6.0, EqualizerProfiles.selectedConfiguration.preampDb)
    }

    @Test
    fun resetRestoresSavedBandParametersWithoutDiscardingOtherEdits() {
        val saved = EqualizerProfiles.createCustom("Custom EQ")
        EqualizerProfiles.editCurrent(
            saved.copy(
                bands =
                    saved.bands.mapIndexed { index, band ->
                        when (index) {
                            0 -> band.copy(frequency = 45.0, gain = 5.0, q = 2.5)
                            1 -> band.copy(gain = -4.0)
                            else -> band
                        }
                    },
            ),
        )

        EqualizerProfiles.resetBand(0)
        assertEquals(saved.bands[0], EqualizerProfiles.activeProfile?.bands?.get(0))
        assertEquals(
            -4.0,
            EqualizerProfiles.activeProfile
                ?.bands
                ?.get(1)
                ?.gain,
        )

        EqualizerProfiles.resetAllBands()
        assertEquals(saved.bands, EqualizerProfiles.activeProfile?.bands)
        assertFalse(EqualizerProfiles.hasUnsavedChanges)
    }

    @Test
    fun resetBuiltInPresetRestoresFactoryBands() {
        EqualizerProfiles.activatePreset(EqualizerPreset.BASS, "Bass")
        val original = checkNotNull(EqualizerProfiles.activeProfile)
        EqualizerProfiles.editCurrent(original.copy(bands = original.bands.map { it.copy(gain = 0.0) }))

        EqualizerProfiles.resetAllBands()

        assertEquals(original.bands, EqualizerProfiles.activeProfile?.bands)
    }

    @Test
    fun workingConfigurationAndNamedProfilesSurviveRestartAndBackupRestore() {
        val store = MapSettings()
        Settings.replaceSharedForTest(Settings(store))
        EqualizerProfiles.activatePreset(EqualizerPreset.BASS, "Bass")
        EqualizerProfiles.setPreamp(-2.0)
        val expected = EqualizerProfiles.configuration
        Settings.replaceSharedForTest(Settings(store))
        assertEquals(expected, EqualizerProfiles.configuration)
        val saved = EqualizerProfiles.saveCurrent("Saved bass")
        val backup = Settings.shared.exportBackupSettings()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.importBackupSettings(backup)
        assertEquals(saved, EqualizerProfiles.activeProfile)
        assertEquals(saved, EqualizerProfiles.profiles.single())
        assertEquals(-2.0, EqualizerProfiles.configuration.preampDb)
    }

    @Test
    fun disablingEqualizerBypassesAudioButKeepsSelectedProfileForEditing() {
        val profile = EqualizerProfiles.createCustom("Custom")
        EqualizerProfiles.setPreamp(3.0)

        EqualizerProfiles.setEnabled(false)

        assertFalse(EqualizerProfiles.isEnabled)
        assertEquals(profile.id, EqualizerProfiles.activeProfile?.id)
        assertEquals(EqualizerConfiguration(null, 0.0), EqualizerProfiles.configuration)
        assertEquals(3.0, EqualizerProfiles.selectedConfiguration.preampDb)

        EqualizerProfiles.setEnabled(true)

        assertEquals(profile.id, EqualizerProfiles.configuration.profile?.id)
        assertEquals(3.0, EqualizerProfiles.configuration.preampDb)
    }

    @Test
    fun defaultFlatProfileIsInitializedWithoutChangingTheEnableSetting() {
        EqualizerProfiles.setEnabled(false)

        EqualizerProfiles.ensureDefaultProfile()

        assertEquals(EqualizerPreset.FLAT.profileId, EqualizerProfiles.activeProfile?.id)
        assertEquals(EqualizerPreset.FLAT.gains, EqualizerProfiles.activeProfile?.bands?.map { it.gain })
        assertFalse(EqualizerProfiles.isEnabled)
        assertNull(EqualizerProfiles.configuration.profile)
        assertFalse(EqualizerProfiles.hasUnsavedChanges)
    }

    @Test
    fun presetsRemainImmutableAndEveryPresetUsesTheSharedPeakProtection() {
        for (preset in EqualizerPreset.entries) {
            EqualizerProfiles.activatePreset(preset, preset.name)
            val profile = checkNotNull(EqualizerProfiles.activeProfile)
            assertEquals(10, profile.bands.size)
            assertEquals(preset.gains, profile.bands.map { it.gain })
            assertTrue(EqualizerProfiles.profiles.isEmpty())
            val samples = ShortArray(4800) { i -> (30_000 * sin(2 * PI * 80 * i / 48_000)).toInt().toShort() }
            ParametricEqualizer(48_000, 1).apply { update(profile) }.processPcm16(samples, outputGainDb = 12.0)
            assertTrue(samples.all { abs(it.toInt()) <= 32113 })
            EqualizerProfiles.editCurrent(profile.copy(bands = profile.bands.map { it.copy(gain = 0.0) }))
            EqualizerProfiles.activatePreset(preset, preset.name)
            assertEquals(preset.gains, EqualizerProfiles.activeProfile?.bands?.map { it.gain })
        }
    }

    @Test
    fun legacyProfilesResolveTheirPreampFromTheExistingMasterSetting() {
        Settings.shared.equalizerProfilesJson = """[{"id":"old","name":"Old","bands":[]}]"""
        Settings.shared.equalizerActiveProfileId = "old"
        Settings.shared.audioBoostDb = 6f
        EqualizerProfiles.setEnabled(true)
        assertEquals(6.0, EqualizerProfiles.configuration.preampDb)
        EqualizerProfiles.setActive("old")
        assertEquals(6.0, EqualizerProfiles.activeProfile?.outputGainDb)
        EqualizerProfiles.saveCurrent("Old")
        EqualizerProfiles.setPreamp(2.0)
        EqualizerProfiles.setActive("old")
        assertEquals(6.0, EqualizerProfiles.configuration.preampDb)
    }

    @Test
    fun peakingFilterBoostsCenterFrequencyOnly() {
        val profile = EqualizerProfile("p", "p", listOf(ParametricEqBand(1000.0, 6.0, 1.0)))
        assertEquals(6.0, gainDb(profile, 1000.0), 0.2)
        assertEquals(0.0, gainDb(profile, 10_000.0), 0.3)
    }

    @Test
    fun shelvesAndPreampApply() {
        val profile =
            EqualizerProfile(
                "p",
                "p",
                listOf(ParametricEqBand(200.0, -6.0, 0.707, FilterType.LSC), ParametricEqBand(8000.0, 4.0, 0.707, FilterType.HSC)),
                preamp = -2.0,
            )
        assertEquals(-8.0, gainDb(profile, 30.0), 0.3)
        assertEquals(-2.0, gainDb(profile, 1500.0), 0.5)
        assertEquals(2.0, gainDb(profile, 18_000.0), 0.3)
    }

    @Test
    fun outputBoostRaisesSignalAndClampsToSupportedRange() {
        val eq = ParametricEqualizer(48_000, 1).apply { update(null) }
        val samples = shortArrayOf(5_000)

        eq.processPcm16(samples, outputGainDb = 6.0)

        assertEquals(9_976.toShort(), samples.single())
        val clipped = shortArrayOf(30_000)
        eq.processPcm16(clipped, outputGainDb = 30.0)
        assertTrue(clipped.single() in 32_000..32_200)
    }

    @Test
    fun outputBoostCapsRestoredValuesAtTwelveDecibelsForBothPcmFormats() {
        val pcm16 = shortArrayOf(100)
        ParametricEqualizer(48_000, 1).processPcm16(pcm16, outputGainDb = 100.0)
        assertEquals(398.toShort(), pcm16.single())
        val floats = floatArrayOf(0.01f)
        ParametricEqualizer(48_000, 1).processPcmFloat(floats, outputGainDb = 100.0)
        assertEquals(0.039811f, floats.single(), 0.000001f)
    }

    @Test
    fun peakProtectionRemainsActiveWhenProfileAndPreampAreDisabled() {
        val eq = ParametricEqualizer(48_000, 2)
        val samples = floatArrayOf(1.2f, -0.6f)
        eq.processPcmFloat(samples)
        assertEquals(0.98f, samples[0], 0.000001f)
        assertEquals(-0.49f, samples[1], 0.000001f)
        assertTrue(eq.gainReductionDb > 0.0)

        val quiet = floatArrayOf(0.2f, -0.1f)
        eq.processPcmFloat(quiet)
        assertEquals(0.2f * 0.98f / 1.2f, quiet[0], 0.000001f)
        assertEquals(-0.1f * 0.98f / 1.2f, quiet[1], 0.000001f)

        eq.reset()
        val pcm = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
        eq.processPcm16(pcm)
        assertTrue(pcm.all { abs(it.toInt()) <= 32113 })
        assertTrue(eq.gainReductionDb > 0.0)
    }

    @Test
    fun peakProtectionHoldsThenReleasesWithoutReshapingWaveformCycles() {
        val eq = ParametricEqualizer(48_000, 1).apply { update(null) }
        val loud = ShortArray(1024) { 20_000 }

        eq.processPcm16(loud, outputGainDb = 6.0)

        assertTrue(loud.max() in 32_000..32_200)
        val firstRecovery = shortArrayOf(1_000)
        eq.processPcm16(firstRecovery, outputGainDb = 6.0)
        val recovered = ShortArray(12_000) { 1_000 }
        eq.processPcm16(recovered, outputGainDb = 6.0)

        assertTrue(firstRecovery.single() < 1_995)
        assertEquals(firstRecovery.single(), recovered.first())
        assertEquals(1_995.toShort(), recovered.last())
        assertEquals(0.0, eq.gainReductionDb, 0.0001)
    }

    @Test
    fun preampKeepsSustainedBassAndTrebleSinusoidalWhenPeakProtectionIsRequired() {
        val rate = 48_000
        for (frequency in listOf(40, 1000, 10000)) {
            val samples = FloatArray(rate) { i -> (0.9 * sin(2 * PI * frequency * i / rate)).toFloat() }
            val eq = ParametricEqualizer(rate, 1)
            eq.processPcmFloat(samples, outputGainDb = 12.0)
            assertTrue(samples.all { abs(it) <= 0.980001f })
            val start = rate / 2
            val count = rate - start
            var inPhase = 0.0
            var quadrature = 0.0
            var energy = 0.0
            for (i in start until rate) {
                val angle = 2 * PI * frequency * i / rate
                inPhase += samples[i] * sin(angle)
                quadrature += samples[i] * cos(angle)
                energy += samples[i].toDouble() * samples[i]
            }
            val fundamentalEnergy = 2 * (inPhase * inPhase + quadrature * quadrature) / count
            val residualRatio = sqrt(((energy - fundamentalEnergy) / energy).coerceAtLeast(0.0))
            assertTrue(residualRatio < 0.0001, "Limiter introduced harmonics at $frequency Hz: $residualRatio")
            assertTrue(eq.gainReductionDb > 10.0)
        }
    }

    @Test
    fun boostedBandsAndPreampPreventSampleClippingAcrossTransientsAndProfileChanges() {
        val rate = 48_000
        val eq = ParametricEqualizer(rate, 2)
        for (preset in EqualizerPreset.entries) {
            eq.update(
                EqualizerProfile(
                    preset.profileId,
                    preset.name,
                    createEqualizerBands(preset.gains.size)
                        .zip(preset.gains) { band, gain -> band.copy(gain = gain) },
                ),
            )
            val input =
                FloatArray(4096) { i ->
                    val frame = i / 2
                    val amplitude = if (frame % 257 == 0) 1.0 else 0.85
                    (amplitude * sin(2 * PI * 1000 * frame / rate) * if (i % 2 == 0) 1.0 else -0.5).toFloat()
                }
            eq.processPcmFloat(input, outputGainDb = 12.0)
            assertTrue(input.all { it.isFinite() && abs(it) <= 0.980001f }, "Clipping with $preset")
            val pcm = ShortArray(4096) { if (it % 7 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
            eq.processPcm16(pcm, outputGainDb = 12.0)
            assertTrue(pcm.all { abs(it.toInt()) <= 32113 }, "PCM16 clipping with $preset")
        }
    }

    @Test
    fun negativePreampAttenuatesLinearlyAndIsCappedAtMinusTwelveDecibels() {
        val samples = shortArrayOf(10000, -10000)
        ParametricEqualizer(48_000, 1).processPcm16(samples, outputGainDb = -100.0)
        assertContentEquals(shortArrayOf(2512, -2512), samples)
        val floats = floatArrayOf(0.1f, -0.1f)
        ParametricEqualizer(48_000, 1).processPcmFloat(floats, outputGainDb = -100.0)
        assertEquals(0.025119f, floats[0], 0.000001f)
        assertEquals(-floats[0], floats[1])
    }

    @Test
    fun savingProfileMetadataDoesNotResetAudioFilterHistory() {
        val profile = EqualizerProfile("history", "history", listOf(ParametricEqBand(1000.0, 6.0, 4.0)))
        val reference = ParametricEqualizer(48_000, 1).apply { update(profile) }
        val edited = ParametricEqualizer(48_000, 1).apply { update(profile) }
        for (i in 0 until 1024) {
            if (i == 512) edited.update(profile.copy(name = "Renamed", addedTimestamp = 1L))
            val sample = 0.1 * sin(2 * PI * 1000 * i / 48_000)
            assertEquals(reference.process(sample, 0), edited.process(sample, 0), 0.000000001)
        }
    }

    @Test
    fun floatPcmUsesTheSameEqualizerAndOutputBoost() {
        val eq = ParametricEqualizer(48_000, 1).apply { update(null) }
        val samples = floatArrayOf(0.25f, -0.25f)

        eq.processPcmFloat(samples, outputGainDb = 6.0)

        assertEquals(0.4988f, samples[0], 0.0001f)
        assertEquals(-0.4988f, samples[1], 0.0001f)
        val clipped = floatArrayOf(0.9f)
        eq.processPcmFloat(clipped, outputGainDb = 24.0)
        assertEquals(0.98f, clipped.single(), 0.0001f)
    }

    @Test
    fun limiterOutputIsIndependentOfBackendBufferSizesAndLinksStereoChannels() {
        val input =
            ShortArray(2048) { i ->
                when {
                    i < 512 -> if (i % 2 == 0) 1000 else -500
                    i < 1024 -> if (i % 2 == 0) 30000 else -15000
                    else -> if (i % 2 == 0) 1000 else -500
                }.toShort()
            }
        val whole = input.copyOf()
        ParametricEqualizer(48000, 2).processPcm16(whole, outputGainDb = 6.0)
        val eq = ParametricEqualizer(48000, 2)
        val chunked = ShortArray(input.size)
        for (offset in input.indices step 128) {
            val chunk = input.copyOfRange(offset, offset + 128)
            eq.processPcm16(chunk, outputGainDb = 6.0)
            chunk.copyInto(chunked, offset)
        }
        assertContentEquals(whole, chunked)
        for (i in whole.indices step 2) assertEquals(whole[i].toDouble(), -2.0 * whole[i + 1], 1.0)
        val floats = FloatArray(input.size) { input[it] / 32768f }
        ParametricEqualizer(48000, 2).processPcmFloat(floats, outputGainDb = 6.0)
        for (i in whole.indices) assertEquals(whole[i] / 32768f, floats[i], 1f / 32768f)
    }

    @Test
    fun spectrumMeterIdentifiesDominantToneBand() =
        withSpectrumConsumer {
            AudioSpectrumMeter.reset()
            val samples =
                ShortArray(2048) { index ->
                    (8_000 * sin(2 * PI * 1_000 * index / 48_000)).toInt().toShort()
                }

            AudioSpectrumMeter.accept(samples, samples.size, channels = 1, rate = 48_000)

            val levels = AudioSpectrumMeter.levels()
            assertEquals(18, levels.size)
            assertTrue(levels[9] > levels[7])
            assertTrue(levels[9] > levels[12])
        }

    @Test
    fun spectrumMeterSeparatesLowBassBands() {
        fun measure(frequency: Int): FloatArray =
            withSpectrumConsumer {
                AudioSpectrumMeter.reset()
                val samples =
                    ShortArray(2048) { index ->
                        (8_000 * sin(2 * PI * frequency * index / 48_000)).toInt().toShort()
                    }
                AudioSpectrumMeter.accept(samples, samples.size, channels = 1, rate = 48_000)
                val levels = AudioSpectrumMeter.levels()
                levels
            }

        val fortyFiveHertz = measure(45)
        assertTrue(fortyFiveHertz[0] > fortyFiveHertz[1])
        assertTrue(fortyFiveHertz[0] > fortyFiveHertz[2])

        val sixtyThreeHertz = measure(63)
        assertTrue(sixtyThreeHertz[1] > sixtyThreeHertz[0])
        assertTrue(sixtyThreeHertz[1] > sixtyThreeHertz[2])
    }

    @Test
    fun spectrumMeterPreservesAntiphaseStereoContent() =
        withSpectrumConsumer {
            AudioSpectrumMeter.reset()
            val samples =
                ShortArray(2048 * 2) { index ->
                    val frame = index / 2
                    val left = (8_000 * sin(2 * PI * 1_000 * frame / 48_000)).toInt().toShort()
                    if (index % 2 == 0) left else (-left.toInt()).toShort()
                }

            AudioSpectrumMeter.accept(samples, samples.size, channels = 2, rate = 48_000)

            val levels = AudioSpectrumMeter.levels()
            assertTrue(levels[9] > 0.2f)
            assertTrue(levels[9] > levels[7])
            assertTrue(levels[9] > levels[12])
        }

    @Test
    fun spectrumMeterFallsWhenAudioFramesStopArriving() =
        withSpectrumConsumer {
            AudioSpectrumMeter.reset()
            val samples =
                ShortArray(2048) { index ->
                    (8_000 * sin(2 * PI * 1_000 * index / 48_000)).toInt().toShort()
                }
            AudioSpectrumMeter.accept(samples, samples.size, channels = 1, rate = 48_000)
            val activeLevel = AudioSpectrumMeter.levels()[9]

            repeat(30) { AudioSpectrumMeter.levels() }

            assertTrue(AudioSpectrumMeter.levels()[9] < activeLevel)
        }

    @Test
    fun spectrumMeterDoesNotCapturePcmWhenNotVisible() {
        val samples = ShortArray(2048) { Short.MAX_VALUE }
        AudioSpectrumMeter.accept(samples, samples.size, channels = 1, rate = 48_000)
        assertTrue(AudioSpectrumMeter.snapshotLevels().all { it == 0f })
    }

    @Test
    fun meterTracksActiveConsumersUntilTheLastOneLeaves() {
        assertFalse(AudioSpectrumMeter.hasConsumers.value)
        AudioSpectrumMeter.addConsumer()
        AudioSpectrumMeter.addConsumer()
        try {
            assertTrue(AudioSpectrumMeter.hasConsumers.value)
            AudioSpectrumMeter.removeConsumer()
            assertTrue(AudioSpectrumMeter.hasConsumers.value)
        } finally {
            AudioSpectrumMeter.removeConsumer()
        }
        assertFalse(AudioSpectrumMeter.hasConsumers.value)
    }

    @Test
    fun disabledEqualizerPassesAudioThrough() {
        val eq = ParametricEqualizer(48_000, 2)
        assertFalse(eq.update(null))
        assertEquals(0.25, eq.process(0.25, 1))
    }

    @Test
    fun unityPcmBypassPreservesSamplesWithoutPeakReduction() {
        val samples = shortArrayOf(12_345, -12_345, 30_000, -30_000)
        val original = samples.copyOf()
        val eq = ParametricEqualizer(48_000, 2).apply { update(null) }

        eq.processPcm16(samples)

        assertContentEquals(original, samples)
        assertEquals(0.0, eq.gainReductionDb, 1e-9)
    }

    @Test
    fun parsesAutoEqIndexAndSearchesModels() {
        val entries =
            parseAutoEqIndex(
                """
                # Index
                - [Sony WH-1000XM4](./oratory1990/over-ear/Sony%20WH-1000XM4) by oratory1990
                - [Sony WH-1000XM4 (ANC Off)](./crinacle/GRAS%2043AG-7%20over-ear/Sony%20WH-1000XM4%20(ANC%20Off)) by crinacle on GRAS 43AG-7
                - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
                """.trimIndent(),
            )
        assertEquals(
            AutoEqEntry(
                "Sony WH-1000XM4 (ANC Off)",
                "crinacle",
                "GRAS 43AG-7",
                "over-ear",
                "crinacle/GRAS%2043AG-7%20over-ear/Sony%20WH-1000XM4%20(ANC%20Off)",
            ),
            entries[1],
        )
        assertNull(entries[0].rig)

        val models = groupAutoEqModels(entries)
        assertEquals(listOf("Sennheiser HD 600", "Sony WH-1000XM4"), models.map { it.name })
        assertEquals(2, models[1].entries.size)
        assertEquals(listOf("Sony WH-1000XM4"), searchAutoEqModels(models, "sony 1000xm4").map { it.name })
        assertEquals(listOf("Sennheiser HD 600"), searchAutoEqModels(models, "hd 600").map { it.name })
    }

    private fun gainDb(
        profile: EqualizerProfile,
        frequency: Double,
    ): Double {
        val sampleRate = 48_000
        val eq = ParametricEqualizer(sampleRate, 1).apply { update(profile) }
        val samples = sampleRate / 2
        var sumIn = 0.0
        var sumOut = 0.0
        for (i in 0 until samples) {
            val x = 0.1 * sin(2 * PI * frequency * i / sampleRate)
            val y = eq.process(x, 0)
            if (i > samples / 2) {
                sumIn += x * x
                sumOut += y * y
            }
        }
        return 20 * log10(sqrt(sumOut / sumIn))
    }
}
