package com.metrolist.android.playback

import com.metrolist.shared.api.SongPlayerInformation
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.api.musicvideo.MusicTrackResult
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidAudioTrackSelectionTest {
    @Test
    fun stagedRemoteLoadMergesWithTheRequestedSongInsteadOfTheOldQueue() {
        val previous = SongItem(id = "previous", title = "Previous", thumbnail = null, artists = emptyList(), album = null)
        val room = SongItem(id = "room", title = "Room track", thumbnail = null, artists = emptyList(), album = null)
        val staged = selectionQueueSong(stagedQueueReplacement = true, sourceSong = room) { previous }
        assertEquals("room", mergeSelectedQueueSong(room, room, requireNotNull(staged)).id)
        assertEquals("previous", selectionQueueSong(stagedQueueReplacement = false, sourceSong = room) { previous }?.id)
    }

    @Test
    fun songModeReplacesQueuedAndRestoredNothingOnMeVideoWithAlbumTrack() =
        runTest {
            val video = nothingOnMeVideo()
            val track = audioSong().copy(id = "obGydj8nf7o", title = "NOTHING ON ME", artists = video.artists, duration = 160L)
            var searches = 0
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = { null },
                    search = { _, _ ->
                        searches++
                        SearchResultPage(listOf(track))
                    },
                    songLookup = { error("No authoritative counterpart") },
                    settings = Settings(MapSettings()),
                )
            for (restore in listOf(false, true)) {
                val queue = MusicQueue().apply { setQueue(listOf(audioSong(), video, audioSong()), startIndex = 1) }
                if (restore) queue.restore(Json.decodeFromString<MusicQueueSnapshot>(Json.encodeToString(queue.snapshot())))
                val source = requireNotNull(queue.currentSong())
                val positionMs = if (restore) 4_000L else 0L
                val cachedVideoAudio = PlaybackResource(source.id, "file://cached-video", emptyMap(), null, null)
                val selection =
                    resolveAndroidAudioTrackSelection(
                        source,
                        positionMs,
                        wantVideo = shouldRequestQueueVideo(source.isVideoSong, isVideoModeActive = false),
                        cachedAudio = cachedVideoAudio,
                        resolveMusicTrack = resolver::resolveMusicTrack,
                    )

                assertEquals(track.id, selection.song.id)
                assertEquals(positionMs, selection.positionMs)
                assertNull(selection.cachedAudio)
                queue.replaceCurrent(mergeSelectedQueueSong(selection.song, source, requireNotNull(queue.currentSong())))
                assertEquals(listOf("track", track.id, "track"), queue.items.value.map(SongItem::id))
                assertEquals(queue.items.value, queue.snapshot().originalItems)
                assertEquals("https://music.youtube.com/watch?v=obGydj8nf7o", ShareUrls.song(requireNotNull(queue.currentSong()).id))
            }
            assertEquals(1, searches)
        }

    @Test
    fun explicitVideoModeAndVideoWithoutAlbumCounterpartKeepTheirIdentity() =
        runTest {
            val video = nothingOnMeVideo()
            val explicitVideo =
                resolveAndroidAudioTrackSelection(
                    video,
                    4_000L,
                    wantVideo = shouldRequestQueueVideo(video.isVideoSong, isVideoModeActive = true),
                ) { error("Explicit video mode must not select album audio") }
            assertEquals(video, explicitVideo.song)

            val upload = video.copy(id = "video-only", musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
            var lookups = 0
            val videoOnly =
                resolveAndroidAudioTrackSelection(
                    upload,
                    4_000L,
                    wantVideo = shouldRequestQueueVideo(upload.isVideoSong, isVideoModeActive = false),
                ) {
                    lookups++
                    MusicTrackResult.NotFound
                }
            assertEquals(AndroidAudioTrackSelection(upload, 4_000L), videoOnly)
            assertEquals(1, lookups)
        }

    private fun nothingOnMeVideo() =
        videoSong().copy(
            id = "4CmYE-fbVWc",
            title = "The Skinner Brothers - Nothing on Me (Official Music Video)",
            artists = listOf(ArtistTiny(null, "The Skinner Brothers")),
            duration = 163L,
        )

    @Test
    fun incomingVideoKeepsRichMetadataAndFallbackVideoType() =
        runTest {
            val video = videoSong().copy(title = "Official recording", duration = 214L)
            val resolved = resolveIncomingPlaybackSong(video.id, "Service", { video }, { error("Metadata already available") })
            assertEquals(video, resolved)
            val selection = resolveAndroidAudioTrackSelection(resolved, 0L, wantVideo = false) { MusicTrackResult.Found(audioSong()) }
            assertEquals("track", selection.song.id)

            val fallback =
                resolveIncomingPlaybackSong(video.id, "Service", { null }) {
                    SongPlayerInformation(title = video.title, musicVideoType = video.musicVideoType)
                }
            assertEquals(video.musicVideoType, fallback.musicVideoType)
            assertEquals(video.title, fallback.title)
            assertEquals("Service", fallback.artists.single().name)
            try {
                resolveIncomingPlaybackSong(video.id, "Service", { throw CancellationException() }, { error("Must not continue") })
                fail("Expected cancellation")
            } catch (_: CancellationException) {
            }
        }

    @Test
    fun incomingAudioPrefetchPreservesVideoSelectionAndIgnoresInternalFailures() =
        runTest {
            val audio = audioSong()
            var resolved = 0
            prefetchIncomingAudio(audio, wantVideo = false, canPlayLocally = true) {
                assertEquals(audio, it)
                resolved++
            }
            prefetchIncomingAudio(audio, wantVideo = true, canPlayLocally = true) { error("Video mode must use the service") }
            prefetchIncomingAudio(audio, wantVideo = false, canPlayLocally = false) { error("Local playback is unavailable") }
            prefetchIncomingAudio(videoSong(), wantVideo = false, canPlayLocally = true) { error("Select the counterpart first") }
            prefetchIncomingAudio(audio.copy(isUnavailable = true), false, true) { error("Unavailable track") }
            prefetchIncomingAudio(audio, false, true) { throw IllegalStateException("The service must present the failure") }
            assertEquals(1, resolved)
            prefetchIncomingAudio(audio, false, true) { throw CancellationException() }
        }

    @Test
    fun mapsDifferentLengthRecordingsAndPreservesQueueMetadata() =
        runTest {
            val source = videoSong().copy(queueSourceTitle = "album", customTitle = "Edited", customArtist = "Singer")
            val target = audioSong().copy(duration = 200L)
            val map = CounterpartPositionMap(listOf(CounterpartSegment(10_000L, 20_000L, 100_000L)))

            val selection =
                resolveAndroidAudioTrackSelection(source, 35_000L, wantVideo = false) {
                    delay(3_000L)
                    MusicTrackResult.Found(target, map)
                }

            assertEquals("track", selection.song.id)
            assertEquals(45_000L, selection.positionMs)
            assertEquals("album", selection.song.queueSourceTitle)
            assertEquals("Edited", selection.song.customTitle)
            assertEquals("Edited", selection.song.title)
            assertEquals(
                "Singer",
                selection.song.artists
                    .single()
                    .name,
            )
            assertEquals(200L, selection.song.duration)
        }

    @Test
    fun clampsMappedPositionToDifferentTargetDuration() =
        runTest {
            val selection =
                resolveAndroidAudioTrackSelection(videoSong(), 20_000L, wantVideo = false) {
                    MusicTrackResult.Found(audioSong().copy(duration = 15L))
                }

            assertEquals(14_999L, selection.positionMs)
        }

    @Test
    fun initialRestoreAndPreloadUseTheSameCanonicalSelection() =
        runTest {
            val source = videoSong()
            var lookups = 0
            val lookup: suspend (SongItem) -> MusicTrackResult = {
                lookups++
                MusicTrackResult.Found(audioSong(), CounterpartPositionMap(listOf(CounterpartSegment(0L, 1_000L, 90_000L))))
            }

            val initial = resolveAndroidAudioTrackSelection(source, 4_000L, wantVideo = false, resolveMusicTrack = lookup)
            val restored = resolveAndroidAudioTrackSelection(source, 4_000L, wantVideo = false, resolveMusicTrack = lookup)
            val preloaded = resolveAndroidAudioTrackSelection(source, 0L, wantVideo = false, resolveMusicTrack = lookup)

            assertEquals("track", initial.song.id)
            assertEquals(5_000L, restored.positionMs)
            assertEquals("track", preloaded.song.id)
            assertEquals(1_000L, preloaded.positionMs)
            assertEquals(3, lookups)
        }

    @Test
    fun canonicalQueueReplacementKeepsTheSelectedOccurrenceAndNeighbors() =
        runTest {
            val source = videoSong().copy(queueSourceTitle = "playlist")
            val queue =
                MusicQueue().apply {
                    setQueue(listOf(audioSong().copy(id = "before"), source, audioSong().copy(id = "after")), startIndex = 1)
                }
            val selection =
                resolveAndroidAudioTrackSelection(source, 0L, wantVideo = false) {
                    MusicTrackResult.Found(audioSong())
                }

            queue.replaceCurrent(selection.song)

            assertEquals(listOf("before", "track", "after"), queue.items.value.map(SongItem::id))
            assertEquals("playlist", queue.currentSong()?.queueSourceTitle)
            assertEquals(1, queue.currentIndex.value)
            assertEquals(queue.items.value, queue.snapshot().originalItems)
        }

    @Test
    fun blockedCounterpartKeepsThePlayableSource() =
        runTest {
            val selection =
                resolveAndroidAudioTrackSelection(
                    videoSong(),
                    500L,
                    wantVideo = false,
                    isCounterpartAllowed = { it.id != "track" },
                ) {
                    MusicTrackResult.Found(audioSong())
                }

            assertEquals("video", selection.song.id)
            assertEquals(500L, selection.positionMs)
        }

    @Test
    fun preloadedCounterpartPreservesStoredSourceMetadata() =
        runTest {
            val source =
                videoSong().copy(
                    title = "Stored title",
                    artists = listOf(ArtistTiny(null, "Stored artist")),
                    queueSourceTitle = "Playlist",
                    customTitle = "Stored title",
                    customArtist = "Stored artist",
                    canonicalTitle = "Video recording",
                    canonicalArtists = videoSong().artists,
                )
            val selection =
                resolveAndroidAudioTrackSelection(source, 0L, wantVideo = false) {
                    MusicTrackResult.Found(audioSong())
                }
            val promoted = mergeSelectedQueueSong(selection.song, source, source)

            assertEquals("Stored title", promoted.title)
            assertEquals("Stored artist", promoted.artists.single().name)
            assertEquals("Playlist", promoted.queueSourceTitle)
        }

    @Test
    fun preloadedCounterpartUsesLatestQueueOccurrenceMetadata() {
        val source = videoSong().copy(queueSourceTitle = "Recommended", customTitle = "Old title", customArtist = "Old artist")
        val selected =
            audioSong().copy(
                title = "Old title",
                artists = listOf(ArtistTiny(null, "Old artist")),
                canonicalTitle = "Original recording",
                canonicalArtists = audioSong().artists,
                customTitle = "Old title",
                customArtist = "Old artist",
            )
        val updatedSource = source.copy(queueSourceTitle = "Added manually", customTitle = "New title", customArtist = null)

        val promoted = mergeSelectedQueueSong(selected, source, updatedSource)

        assertEquals("track", promoted.id)
        assertEquals("Added manually", promoted.queueSourceTitle)
        assertEquals("New title", promoted.title)
        assertNull(promoted.customArtist)
        assertEquals("Original artist", promoted.artists.single().name)

        val editedDuringPreparation = updatedSource.copy(customTitle = null, customArtist = "Latest artist", queueSourceTitle = "Moved")
        val committed = mergeSelectedQueueSong(promoted, updatedSource, editedDuringPreparation)
        assertEquals("Original recording", committed.title)
        assertNull(committed.customTitle)
        assertEquals("Latest artist", committed.artists.single().name)
        assertEquals("Moved", committed.queueSourceTitle)

        val counterpartEdit = audioSong().copy(customTitle = "Music title", title = "Music title")
        val unchangedSource = videoSong().copy(queueSourceTitle = "Recommended")
        val promotedWithCounterpartEdit =
            mergeSelectedQueueSong(counterpartEdit, unchangedSource, unchangedSource.copy(queueSourceTitle = "Added manually"))

        assertEquals("Music title", promotedWithCounterpartEdit.title)
        assertEquals("Added manually", promotedWithCounterpartEdit.queueSourceTitle)
    }

    @Test
    fun counterpartCommitRechecksOwnershipAfterLookupAndPreparation() {
        assertTrue(canCommitAudioTrackSelection("video", "track", false, false))
        assertFalse(canCommitAudioTrackSelection("video", "track", true, false))
        assertFalse(canCommitAudioTrackSelection("video", "track", false, true))
        assertFalse(canCommitAudioTrackSelection("video", "track", true, true))
        assertTrue(canCommitAudioTrackSelection("video", "video", true, true))
    }

    @Test
    fun failedInitialCounterpartPreparationWaitsBeforeQueueCommitAndFallsBackToSource() {
        val source = videoSong()
        val counterpart = audioSong()
        val cachedAudio = PlaybackResource(source.id, "file://cached-video", emptyMap(), null, null)

        assertTrue(shouldAwaitQueueReplacementReadiness(prepareInBackground = false, replaceCurrentQueueItem = true))
        assertFalse(shouldAwaitQueueReplacementReadiness(prepareInBackground = false, replaceCurrentQueueItem = false))
        assertFalse(shouldAwaitQueueReplacementReadiness(prepareInBackground = true, replaceCurrentQueueItem = true))
        assertEquals(
            AndroidAudioTrackSelection(source, 12_000L),
            fallbackToOriginalAudioSelection(source, counterpart, 12_000L),
        )
        assertNull(fallbackToOriginalAudioSelection(source, source, 12_000L))
        assertEquals(
            AndroidAudioTrackSelection(source, 12_000L, cachedAudio),
            fallbackToOriginalAudioSelection(source, counterpart, 12_000L, cachedAudio),
        )
    }

    @Test
    fun cachedVideoStillSelectsCounterpartForInitialPlaybackAndPreload() =
        runTest {
            val source = videoSong()
            val cachedAudio = PlaybackResource(source.id, "file://cached-video", emptyMap(), null, null)
            val map = CounterpartPositionMap(listOf(CounterpartSegment(0L, 1_000L, 90_000L)))
            for (positionMs in listOf(0L, 4_000L)) {
                val selection =
                    resolveAndroidAudioTrackSelection(source, positionMs, wantVideo = false, cachedAudio = cachedAudio) {
                        MusicTrackResult.Found(audioSong(), map)
                    }

                assertEquals("track", selection.song.id)
                assertEquals(positionMs + 1_000L, selection.positionMs)
                assertNull(selection.cachedAudio)
            }
        }

    @Test
    fun offlineCachedVideoDoesNotAttemptCounterpartDiscovery() =
        runTest {
            val source = videoSong()
            val cachedAudio = PlaybackResource(source.id, "file://cached-video", emptyMap(), null, null)
            val selection =
                resolveAndroidAudioTrackSelection(
                    source,
                    12_000L,
                    wantVideo = false,
                    cachedAudio = cachedAudio,
                    allowCounterpartLookup = false,
                ) {
                    MusicTrackResult.Found(audioSong())
                }

            assertEquals(AndroidAudioTrackSelection(source, 12_000L, cachedAudio), selection)
        }

    @Test
    fun missingFailedTimedOutAndBlockedLookupsKeepPlayableSource() =
        runTest {
            val source = videoSong()
            val cachedAudio =
                PlaybackResource(
                    videoId = source.id,
                    url = "file://cached",
                    headers = emptyMap(),
                    loudnessDb = null,
                    expiresAt = null,
                )

            for (resource in listOf(null, cachedAudio)) {
                val expected = AndroidAudioTrackSelection(source, 500L, resource)
                assertEquals(
                    expected,
                    resolveAndroidAudioTrackSelection(source, 500L, false, cachedAudio = resource) { MusicTrackResult.NotFound },
                )
                assertEquals(
                    expected,
                    resolveAndroidAudioTrackSelection(source, 500L, false, cachedAudio = resource) { MusicTrackResult.Error },
                )
                assertEquals(
                    expected,
                    resolveAndroidAudioTrackSelection(source, 500L, false, cachedAudio = resource) {
                        throw IllegalStateException("lookup unavailable")
                    },
                )
                assertEquals(
                    expected,
                    resolveAndroidAudioTrackSelection(source, 500L, false, cachedAudio = resource, lookupTimeoutMs = 1L) {
                        delay(10L)
                        MusicTrackResult.Found(audioSong())
                    },
                )
                assertEquals(
                    expected,
                    resolveAndroidAudioTrackSelection(source, 500L, false, cachedAudio = resource, isCounterpartAllowed = { false }) {
                        MusicTrackResult.Found(audioSong())
                    },
                )
            }
        }

    @Test
    fun streamedAndDownloadedAlbumAudioKeepTheAlbumRowId() =
        runTest {
            val song = audioSong().copy(id = "Ei8UnOPJX7w")
            val cachedAudio = PlaybackResource(song.id, "file://album-audio", emptyMap(), null, null)
            for (resource in listOf(null, cachedAudio)) {
                val selection =
                    resolveAndroidAudioTrackSelection(song, 12_000L, wantVideo = false, cachedAudio = resource) {
                        error("Canonical album audio must not resolve a counterpart")
                    }
                assertEquals(song.id, selection.song.id)
                assertEquals(12_000L, selection.positionMs)
                assertEquals(resource, selection.cachedAudio)
            }
        }

    @Test
    fun explicitVideoAndLocalPodcastItemsDoNotResolveCounterparts() =
        runTest {
            val source = videoSong()
            var lookups = 0
            val lookup: suspend (SongItem) -> MusicTrackResult = {
                lookups++
                MusicTrackResult.Found(audioSong())
            }

            assertEquals(source.id, resolveAndroidAudioTrackSelection(source, 0L, wantVideo = true, resolveMusicTrack = lookup).song.id)
            assertEquals(
                "local_1",
                resolveAndroidAudioTrackSelection(
                    source.copy(id = "local_1", isLocal = true),
                    0L,
                    false,
                    resolveMusicTrack = lookup,
                ).song.id,
            )
            assertEquals(
                source.id,
                resolveAndroidAudioTrackSelection(source.copy(podcastId = "podcast"), 0L, false, resolveMusicTrack = lookup).song.id,
            )
            assertEquals(
                source.id,
                resolveAndroidAudioTrackSelection(
                    source,
                    0L,
                    false,
                    allowCounterpartLookup = false,
                    resolveMusicTrack = lookup,
                ).song.id,
            )
            assertEquals(0, lookups)
        }

    @Test
    fun externalCancellationPropagatesInsteadOfFallingBack() =
        runTest {
            val cancellation = CancellationException("superseded request")
            try {
                resolveAndroidAudioTrackSelection(videoSong(), 0L, wantVideo = false) { throw cancellation }
                fail("Cancellation must propagate")
            } catch (error: CancellationException) {
                assertEquals(cancellation.message, error.message)
            }
        }

    @Test
    fun staleQueueRequestsCannotCommitCanonicalSelection() {
        assertTrue(isCurrentQueuePlaybackTarget(4L, 4L, 9L, 9L, "track", "track"))
        assertFalse(isCurrentQueuePlaybackTarget(4L, 4L, 8L, 9L, "track", "track"))
        assertFalse(isCurrentQueuePlaybackTarget(3L, 4L, 9L, 9L, "track", "track"))
        assertFalse(isCurrentQueuePlaybackTarget(4L, 4L, 9L, 9L, "other", "track"))
    }

    private fun videoSong() =
        SongItem(
            id = "video",
            title = "Video recording",
            thumbnail = null,
            artists = listOf(ArtistTiny(null, "Video artist")),
            album = null,
            duration = 240L,
            musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
        )

    private fun audioSong() =
        SongItem(
            id = "track",
            title = "Original recording",
            thumbnail = null,
            artists = listOf(ArtistTiny(null, "Original artist")),
            album = null,
            duration = 180L,
            musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
        )
}
