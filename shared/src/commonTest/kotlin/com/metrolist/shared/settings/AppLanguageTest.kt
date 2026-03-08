package com.metrolist.shared.settings

import com.metrolist.shared.constants.AppLanguageKey
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun legacyHinglishSelectionSurvivesRestartAndBackupImport() {
        val oldSettings = Settings(MapSettings(AppLanguageKey to "hin"))
        assertEquals(HINGLISH_LANGUAGE_TAG, oldSettings.appLanguage)

        val backing = MapSettings()
        val settings = Settings(backing)
        settings.appLanguage = "fr"
        settings.importBackupSettings(oldSettings.exportBackupSettings())
        assertEquals(HINGLISH_LANGUAGE_TAG, settings.appLanguage)
        assertEquals(HINGLISH_LANGUAGE_TAG, Settings(backing).appLanguage)

        settings.appLanguage = "hin"
        assertEquals(HINGLISH_LANGUAGE_TAG, backing.getString(AppLanguageKey, ""))
        settings.appLanguage = "SYSTEM"
        assertEquals("SYSTEM", Settings(backing).appLanguage)
    }
}
