package com.metrolist.shared.api.musicvideo

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongDisplayMetadata
import com.metrolist.shared.state.withStoredMetadata
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CanonicalAudioVariantTest {
    private val artists = listOf(ArtistTiny("artist", "Artist"))
    private val studio =
        SongItem("studio", "A Long Song", null, artists, null, duration = 224, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
    private val originalVideo = studio.copy(id = "video", musicVideoType = "MUSIC_VIDEO_TYPE_OMV")

    private fun editedVideo(canonicalTitle: String) =
        originalVideo.withStoredMetadata(
            SongDisplayMetadata(canonicalTitle, artists, customTitle = "A Long Song", customArtist = null),
        )

    private suspend fun resolveRenamedLiveRecording(authoritative: Boolean): MusicTrackResult {
        val edited = editedVideo("A Long Song (Live)")
        assertEquals("A Long Song", edited.title)
        assertEquals("A Long Song (Live)", edited.canonicalTitle)
        return MusicVideoResolver(
            counterpartLookup = {
                studio.takeIf { authoritative }?.let { track ->
                    MusicVideoCounterpart(edited.id, track.id, track.musicVideoType, null)
                }
            },
            songLookup = { studio },
            search = { _, _ -> SearchResultPage(listOf(studio)) },
            settings = Settings(MapSettings()),
        ).resolveMusicTrack(edited)
    }

    @Test
    fun aLocalTitleEditCannotHideTheLiveEditionFromAudioSearch() =
        runBlocking {
            assertEquals(MusicTrackResult.NotFound, resolveRenamedLiveRecording(authoritative = false))
        }

    @Test
    fun aLocalTitleEditCannotHideTheLiveEditionFromAuthoritativeAudioMatching() =
        runBlocking {
            assertEquals(MusicTrackResult.NotFound, resolveRenamedLiveRecording(authoritative = true))
        }

    @Test
    fun anEditedCandidateDoesNotHideItsDifferentCanonicalEdition() {
        val editedCandidate =
            studio.withStoredMetadata(
                SongDisplayMetadata("A Long Song (Live)", artists, customTitle = "A Long Song", customArtist = null),
            )

        assertNull(MusicVideoMatcher.findBestTrackMatch(originalVideo, listOf(editedCandidate)))
    }

    @Test
    fun aWarmLookupDoesNotReuseStudioAudioWhenTheCanonicalEditionChanges() =
        runBlocking {
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = { MusicVideoCounterpart(it.id, studio.id, studio.musicVideoType, null) },
                    songLookup = { studio },
                    search = { _, _ -> SearchResultPage(listOf(studio)) },
                    settings = Settings(MapSettings()),
                )
            val first = assertIs<MusicTrackResult.Found>(resolver.resolveMusicTrack(editedVideo("A Long Song")))
            assertEquals(studio.id, first.song.id)
            assertEquals(MusicTrackResult.NotFound, resolver.resolveMusicTrack(editedVideo("A Long Song (Live)")))
        }

    @Test
    fun normalTitleEditsAndMatchingAuthoritativeLiveVersionsStillResolve() =
        runBlocking {
            for (canonicalTitle in listOf("A Long Song", "A Long Song (Live)")) {
                val edited = editedVideo(canonicalTitle)
                val corresponding =
                    studio.withStoredMetadata(
                        SongDisplayMetadata(canonicalTitle, artists, customTitle = "A Long Song", customArtist = null),
                    )
                val resolver =
                    MusicVideoResolver(
                        counterpartLookup = { MusicVideoCounterpart(it.id, corresponding.id, corresponding.musicVideoType, null) },
                        songLookup = { corresponding },
                        search = { _, _ -> error("A valid authoritative pair does not need search") },
                        settings = Settings(MapSettings()),
                    )
                val result = assertIs<MusicTrackResult.Found>(resolver.resolveMusicTrack(edited))
                assertEquals(corresponding.id, result.song.id)
                assertEquals(CounterpartResolutionSource.AUTHORITATIVE, result.source)
            }
        }
}
