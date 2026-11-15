package com.metrolist.desktop.ui.components

import androidx.compose.ui.graphics.Color
import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.theme.parseThemeSeedColor
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkThemeTest {
    private var previousSeed: Color? = null
    private var previousSettings: Settings? = null

    @BeforeTest
    fun setUp() {
        previousSeed = UiState.artworkSeedColor
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
        UiState.artworkSeedColor = null
    }

    @AfterTest
    fun tearDown() {
        UiState.artworkSeedColor = previousSeed
        Settings.replaceSharedForTest(previousSettings)
    }

    @Test
    fun artworkUpdatesRespectTheSelectedStaticTheme() {
        Settings.shared.materialYouEnabled = true
        Settings.shared.dynamicTheme = true
        UiState.artworkSeedColor = Color.Blue
        assertEquals(Color.Blue, UiState.seedColor)

        Settings.shared.materialYouEnabled = false
        Settings.shared.selectedThemeColor = 0xFF259642
        val custom = parseThemeSeedColor(Settings.shared.selectedThemeColor)
        assertEquals(custom, UiState.seedColor)
        UiState.artworkSeedColor = Color.Yellow
        assertEquals(custom, UiState.seedColor)

        Settings.shared.materialYouEnabled = true
        assertEquals(Color.Yellow, UiState.seedColor)
    }

    @Test
    fun cancelledArtworkCannotOverwriteTheCurrentTrackEvenIfItsLoaderFinishes() =
        runTest {
            val oldArtwork = CompletableDeferred<Color>()
            val oldRequest =
                launch {
                    updateArtworkTheme("old") {
                        withContext(NonCancellable) { oldArtwork.await() }
                    }
                }
            runCurrent()
            oldRequest.cancel()
            updateArtworkTheme("current") { Color.Blue }
            oldArtwork.complete(Color.Red)
            oldRequest.join()
            assertEquals(Color.Blue, UiState.artworkSeedColor)
        }

    @Test
    fun cancellationKeepsThePreviousSeedWhileFailuresAndMissingArtworkClearIt() =
        runTest {
            UiState.artworkSeedColor = Color.Blue
            val request = launch { updateArtworkTheme("pending") { CompletableDeferred<Color>().await() } }
            runCurrent()
            request.cancelAndJoin()
            assertEquals(Color.Blue, UiState.artworkSeedColor)
            updateArtworkTheme("failed") { error("Decode failed") }
            assertNull(UiState.artworkSeedColor)
            UiState.artworkSeedColor = Color.Blue
            updateArtworkTheme("") { error("Missing artwork must not be loaded") }
            assertNull(UiState.artworkSeedColor)
        }
}
