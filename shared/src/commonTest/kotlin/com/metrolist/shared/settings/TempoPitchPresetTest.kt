package com.metrolist.shared.settings

import com.metrolist.shared.constants.PlaybackPitchKey
import com.metrolist.shared.constants.TempoPitchPresetsJsonKey
import com.metrolist.shared.utils.playbackPitchToSemitones
import com.metrolist.shared.utils.semitonesToPlaybackPitch
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempoPitchPresetTest {
    @Test
    fun fractionalPitchPreservesExistingStorageAndSurvivesRestartAndBackup() {
        val backing = MapSettings(PlaybackPitchKey to 2f)
        val settings = Settings(backing)
        assertEquals(12f, playbackPitchToSemitones(settings.playbackPitch))
        val pitch = semitonesToPlaybackPitch(-1.25f)
        settings.playbackPitch = pitch
        settings.saveTempoPitchPreset("Fractional", 1f, pitch)
        val restarted = Settings(backing)
        assertEquals(pitch, restarted.playbackPitch)
        val restored = Settings(MapSettings())
        restored.importBackupSettings(restarted.exportBackupSettings())
        assertEquals(pitch, restored.playbackPitch)
        restored.tempoPitchPresets.single().apply(true, false, {}, { applied ->
            assertEquals(-1.25f, playbackPitchToSemitones(applied), 0.00001f)
        })
        assertEquals(pitch, restored.tempoPitchPresets.single().pitch)
    }

    @Test
    fun presetsSurviveRestartAndBackupAndDeleteIndividually() {
        val backing = MapSettings()
        val settings = Settings(backing)
        settings.saveTempoPitchPreset(" Slow ", 0.75f, 1.25f)
        settings.saveTempoPitchPreset("Slow", 0.75f, 1.25f)
        val saved = settings.tempoPitchPresets
        assertEquals(2, saved.size)
        assertEquals("Slow", saved.first().name)
        assertEquals(2, saved.map { it.id }.toSet().size)
        val restored = Settings(MapSettings())
        restored.importBackupSettings(settings.exportBackupSettings())
        assertEquals(saved, restored.tempoPitchPresets)
        assertEquals(saved, Settings(backing).tempoPitchPresets)
        settings.deleteTempoPitchPreset(saved.first().id)
        assertEquals(saved.drop(1), Settings(backing).tempoPitchPresets)
        assertEquals(1f, settings.playbackSpeed)
        assertEquals(1f, settings.playbackPitch)
    }

    @Test
    fun invalidSavedEntriesAreIgnoredWithoutDroppingValidPresets() {
        val raw = """[
            {"id":"ok","name":"Valid","speed":0.5,"pitch":2.0},
            {"id":"bad","name":"Out of range","speed":3.0,"pitch":1.0},
            {"id":"blank","name":" ","speed":1.0,"pitch":1.0},
            {"name":"Missing id","speed":1.0,"pitch":1.0},
            {"id":"ok","name":"Duplicate id","speed":1.0,"pitch":1.0},
            null
        ]"""
        val settings = Settings(MapSettings(TempoPitchPresetsJsonKey to raw))
        assertEquals(listOf(TempoPitchPreset("ok", "Valid", 0.5f, 2f)), settings.tempoPitchPresets)
        for (value in listOf("broken", "{}", "")) {
            assertTrue(Settings(MapSettings(TempoPitchPresetsJsonKey to value)).tempoPitchPresets.isEmpty())
        }
        val empty = Settings(MapSettings())
        empty.saveTempoPitchPreset(" ", 1f, 1f)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 3f)) {
            empty.saveTempoPitchPreset("Invalid speed", value, 1f)
            empty.saveTempoPitchPreset("Invalid pitch", 1f, value)
        }
        assertTrue(empty.tempoPitchPresets.isEmpty())
    }

    @Test
    fun speedOnlyPlaybackRejectsPresetsThatRequirePitchChanges() {
        val preset = TempoPitchPreset("speed", "Speed only", 0.75f, 1f)
        val speeds = mutableListOf<Float>()
        assertTrue(preset.canApply(true, false, pitchEnabled = false))
        preset.apply(true, false, speeds::add, null)
        assertEquals(listOf(0.75f), speeds)
        speeds.clear()
        val pitched = preset.copy(pitch = 1.25f)
        assertFalse(pitched.canApply(true, false, pitchEnabled = false))
        pitched.apply(true, false, speeds::add, null)
        assertTrue(speeds.isEmpty())
    }

    @Test
    fun applyingPresetChangesBothValuesAndHonorsPlaybackRestrictions() {
        val preset = TempoPitchPreset("test", "Independent", 0.75f, 1.25f)
        val speeds = mutableListOf<Float>()
        val pitches = mutableListOf<Float>()
        preset.apply(true, false, speeds::add, pitches::add)
        assertEquals(listOf(0.75f), speeds)
        assertEquals(listOf(1.25f), pitches)
        speeds.clear()
        pitches.clear()
        for ((speedEnabled, varispeed) in listOf(false to false, false to true, true to true)) {
            assertFalse(preset.canApply(speedEnabled, varispeed))
            preset.apply(speedEnabled, varispeed, speeds::add, pitches::add)
        }
        assertTrue(speeds.isEmpty())
        assertTrue(pitches.isEmpty())
        val linked = preset.copy(pitch = preset.speed)
        assertTrue(linked.canApply(true, true))
        linked.apply(true, true, speeds::add, pitches::add)
        assertEquals(listOf(0.75f), speeds)
        assertEquals(speeds, pitches)
    }
}
