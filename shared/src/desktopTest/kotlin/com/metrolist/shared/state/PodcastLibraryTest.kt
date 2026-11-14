package com.metrolist.shared.state

import androidx.room3.useWriterConnection
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.toSongItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodcastLibraryTest {
    @Test
    fun persistedCheckpointsDistinguishPartialAndCompletedWithoutLosingMetadata() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-checkpoint").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song =
                    SongItem(
                        id = "episode",
                        title = "Episode",
                        artists = emptyList(),
                        thumbnail = null,
                        album = null,
                        musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
                    )
                database.dao().upsert(SongEntity(song.id, song.title, liked = true, totalPlayTime = 60_000, customTitle = "Custom"))
                PodcastLibrary.updatePlaybackPosition(database, song, 2_813_000, 8_636_000)
                val partial =
                    database
                        .dao()
                        .episodeListeningProgress()
                        .first()
                        .single()
                assertEquals(false, partial.isComplete())
                assertEquals(2_813_000L, PodcastLibrary.resumePosition(database, song))
                val accountProgress = song.copy(playbackProgressPercent = 95)
                assertEquals(2_813_000L, PodcastLibrary.resumePosition(database, accountProgress))
                assertEquals(2813f / 8636f, partial.fraction())
                PodcastLibrary.updatePlaybackPosition(database, song, 8_636_000, 8_636_000)
                assertEquals(
                    true,
                    database
                        .dao()
                        .episodeListeningProgress()
                        .first()
                        .single()
                        .isComplete(),
                )
                assertEquals(0L, PodcastLibrary.resumePosition(database, song))
                assertEquals(0L, PodcastLibrary.resumePosition(database, accountProgress))
                assertEquals(45_000L, PodcastLibrary.resumePosition(database, accountProgress, 45_000L))
                val stored = database.dao().getSongById(song.id)!!.song
                assertEquals(true, stored.liked)
                assertEquals(60_000L, stored.totalPlayTime)
                assertEquals("Custom", stored.customTitle)
                PodcastLibrary.updatePlaybackPosition(database, song, 10_000, 8_636_000)
                assertEquals(
                    false,
                    database
                        .dao()
                        .episodeListeningProgress()
                        .first()
                        .single()
                        .isComplete(),
                )
                assertEquals(10_000L, PodcastLibrary.resumePosition(database, song))
                PodcastLibrary.updatePlaybackPosition(database, song, 0L, 8_636_000)
                assertEquals(0L, PodcastLibrary.resumePosition(database, accountProgress))
                assertEquals(
                    8_636_000L * 95L / 100L,
                    PodcastLibrary.resumePosition(database, accountProgress.copy(id = "unplayed", duration = 8636)),
                )
            } finally {
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun librarySyncImportsAccountProgressWithoutOverwritingLocalCheckpoints() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-account-progress").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val episode = EpisodeItem("episode", "Episode", null, duration = 7200, playbackProgressPercent = 95)
                val exact = episode.copy(id = "exact", playbackPositionMs = 2_813_000L)
                val complete = episode.copy(id = "complete", playbackProgressPercent = 100)
                PodcastLibrary.synchronize(database, emptyList(), listOf(episode, exact, complete))
                assertEquals(6_840_000L, PodcastLibrary.resumePosition(database, episode.toSongItem()))
                assertEquals(2_813_000L, PodcastLibrary.resumePosition(database, exact.toSongItem()))
                assertEquals(0L, PodcastLibrary.resumePosition(database, complete.toSongItem()))
                PodcastLibrary.updatePlaybackPosition(database, episode.toSongItem(), 120_000L)
                PodcastLibrary.updatePlaybackPosition(database, exact.toSongItem(), 0L)
                PodcastLibrary.synchronize(database, emptyList(), listOf(episode, exact))
                assertEquals(120_000L, PodcastLibrary.resumePosition(database, episode.toSongItem()))
                assertEquals(0L, PodcastLibrary.resumePosition(database, exact.toSongItem()))
            } finally {
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun steadyCheckpointsOnlyUpdatePositionAndRecreateMissingEpisodes() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-position-only").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song = SongItem("episode", "Episode", null, emptyList(), null, musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
                PodcastLibrary.updatePlaybackPosition(database, song, 1_000, 100_000)
                val initial = database.dao().getSongById(song.id)!!.song
                database.useWriterConnection { connection ->
                    connection.usePrepared(
                        "CREATE TRIGGER reject_metadata_update BEFORE UPDATE OF title ON song BEGIN SELECT RAISE(ABORT, 'metadata rewrite'); END",
                    ) { it.step() }
                    connection.usePrepared(
                        "CREATE TRIGGER reject_insert BEFORE INSERT ON song BEGIN SELECT RAISE(ABORT, 'entity insert'); END",
                    ) { it.step() }
                }
                PodcastLibrary.updatePlaybackPosition(database, song, 30_000, 100_000, updateMetadata = false)
                assertEquals(initial.copy(playbackPosition = 30_000), database.dao().getSongById(song.id)!!.song)
                database.useWriterConnection { connection ->
                    connection.usePrepared("DROP TRIGGER reject_metadata_update") { it.step() }
                    connection.usePrepared("DROP TRIGGER reject_insert") { it.step() }
                    connection.usePrepared("DELETE FROM song WHERE id = 'episode'") { it.step() }
                }
                PodcastLibrary.updatePlaybackPosition(database, song, 60_000, 100_000, updateMetadata = false)
                assertEquals(60_000L, PodcastLibrary.resumePosition(database, song))
                PodcastLibrary.updatePlaybackPosition(database, song.copy(title = "Updated"), 70_000, 200_000)
                val updated = database.dao().getSongById(song.id)!!.song
                assertEquals("Updated", updated.title)
                assertEquals(200, updated.duration)
                assertEquals(70_000L, updated.playbackPosition)
            } finally {
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun listeningProgressIncludesHistoryAndCheckpointsButNotUnplayedSavedEpisodes() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-progress").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                dao.upsert(SongEntity("history", "History", isEpisode = true, duration = 100))
                dao.upsert(SongEntity("partial", "Partial", isEpisode = true, duration = 100, playbackPosition = 25_000))
                dao.upsert(SongEntity("saved", "Saved", isEpisode = true))
                dao.upsert(SongEntity("music", "Music", totalPlayTime = 10_000))
                dao.insert(Event(songId = "history", timestamp = LocalDateTime(2026, 1, 1, 0, 0), playTime = 0, playTimeKnown = false))
                val progress = dao.episodeListeningProgress().first().associateBy { it.id }
                assertEquals(setOf("history", "partial", "saved"), progress.keys)
                assertEquals(true, progress.getValue("history").hasStarted)
                assertNull(progress.getValue("history").fraction())
                assertEquals(0.25f, progress.getValue("partial").fraction())
                assertEquals(false, progress.getValue("saved").hasStarted)
            } finally {
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun savePublishesMutationTokenWithoutFetchingSavedCollection() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-save").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        check(request.url.encodedPath.endsWith("/browse/edit_playlist")) { request.url.encodedPath }
                        respond(
                            if (requests ==
                                3
                            ) {
                                "{}"
                            } else {
                                """{"status":"STATUS_SUCCEEDED","playlistEditResults":[{"playlistEditVideoAddedResultData":{"videoId":"episode","setVideoId":"entry"}}]}"""
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=test" })
                val episode = EpisodeItem("episode", "Episode", null, podcastId = "show")
                val saved = PodcastLibrary.setEpisodeSaved(database, episode, true, repository).getOrThrow()
                assertEquals(1, requests)
                assertEquals("entry", saved.playlistSetVideoId)
                assertEquals("show", saved.podcastId)
                assertNotNull(
                    database
                        .dao()
                        .getSongById(episode.id)
                        ?.song
                        ?.inLibrary,
                )
                val removed = PodcastLibrary.setEpisodeSaved(database, saved, false, repository).getOrThrow()
                assertEquals(2, requests)
                assertNull(removed.playlistSetVideoId)
                assertNull(
                    database
                        .dao()
                        .getSongById(episode.id)
                        ?.song
                        ?.inLibrary,
                )
            } finally {
                client.close()
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun rejectedEpisodeEditsKeepSavedMembershipAndOccurrenceToken() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-rejected-save").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var rejected = false
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            if (rejected) {
                                """{"status":"STATUS_FAILED","playlistEditResults":[{"playlistEditVideoAddedResultData":{"setVideoId":"wrong"}}]}"""
                            } else {
                                """{"status":"STATUS_SUCCEEDED","playlistEditResults":[{"playlistEditVideoAddedResultData":{"setVideoId":"entry"}}]}"""
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client).apply { cookie = "SAPISID=test" }
                val repository = YouTubeRepository(tube)
                val episode = EpisodeItem("episode", "Episode", null)

                suspend fun storedToken(): String? =
                    database.useWriterConnection { connection ->
                        connection.usePrepared("SELECT setVideoId FROM set_video_id WHERE videoId = 'episode'") {
                            if (it.step()) it.getText(0) else null
                        }
                    }
                rejected = true
                assertTrue(PodcastLibrary.setEpisodeSaved(database, episode, true, repository).isFailure)
                assertNull(database.dao().getSongById(episode.id))
                assertNull(storedToken())
                rejected = false
                val saved = PodcastLibrary.setEpisodeSaved(database, episode, true, repository).getOrThrow()
                val before = database.dao().getSongById(episode.id)
                rejected = true
                assertTrue(PodcastLibrary.setEpisodeSaved(database, saved, false, repository).isFailure)
                assertEquals(before, database.dao().getSongById(episode.id))
                assertEquals("entry", saved.playlistSetVideoId)
                assertEquals("entry", storedToken())
            } finally {
                client.close()
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun saveSearchPodcastUpdatesLibraryOnlyAfterRemoteSuccess() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-search-save").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var fail = true
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertTrue(request.url.encodedPath.endsWith("/like/like"))
                        assertTrue((request.body as TextContent).text.contains("PLshow"))
                        respond(
                            "{}",
                            status = if (fail) HttpStatusCode.Forbidden else HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val podcast = PodcastItem("MPSPPLshow", "rSlash", "cover.jpg", "rSlash", channelId = "channel")
                assertTrue(PodcastLibrary.setPodcastSaved(database, podcast, true, repository).isFailure)
                assertNull(database.dao().podcast(podcast.id).first())

                fail = false
                PodcastLibrary.setPodcastSaved(database, podcast, true, repository).getOrThrow()
                val saved = assertNotNull(database.dao().podcast(podcast.id).first())
                assertNotNull(saved.bookmarkedAt)
                assertEquals(podcast.title, saved.title)
                assertEquals(podcast.channelId, saved.channelId)
                assertEquals(listOf(saved), database.dao().subscribedPodcasts().first())

                PodcastLibrary.setPodcastSaved(database, podcast, true, repository).getOrThrow()
                assertEquals(
                    saved.bookmarkedAt,
                    database
                        .dao()
                        .podcast(podcast.id)
                        .first()
                        ?.bookmarkedAt,
                )
                assertEquals(
                    1,
                    database
                        .dao()
                        .subscribedPodcasts()
                        .first()
                        .size,
                )
            } finally {
                client.close()
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun batchedSyncPreservesProgressCustomMetadataAndLibraryDatesAcrossRefreshes() =
        runBlocking {
            val path = Files.createTempDirectory("podcast-sync").resolve("music.db")
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val original = SongEntity(id = "episode-0", title = "Old title", playbackPosition = 1234L, customTitle = "My title")
                database.dao().upsert(original)
                val author = ArtistTiny("channel", "Channel", "channel.jpg")
                val episodes =
                    (0 until 205).map {
                        EpisodeItem(
                            "episode-$it",
                            "Episode $it",
                            "episode.jpg",
                            author,
                            podcastId = "show",
                            playlistSetVideoId = "entry-$it",
                        )
                    }
                val podcasts = listOf(PodcastItem("show", "Show", "show.jpg", "Channel", channelId = "channel"))
                PodcastLibrary.synchronize(database, podcasts, episodes)
                val first = database.dao().getSongsByIds(episodes.map { it.id }).associateBy { it.id }
                assertEquals(205, first.size)
                assertEquals(1234L, first.getValue("episode-0").song.playbackPosition)
                assertEquals("My title", first.getValue("episode-0").song.customTitle)
                assertEquals(
                    "Channel",
                    first
                        .getValue("episode-204")
                        .artists
                        .single()
                        .name,
                )
                val podcast = database.dao().podcast("show").first()
                PodcastLibrary.synchronize(database, podcasts, episodes)
                assertEquals(first, database.dao().getSongsByIds(episodes.map { it.id }).associateBy { it.id })
                assertEquals(podcast, database.dao().podcast("show").first())
            } finally {
                database.close()
                path.parent.toFile().deleteRecursively()
            }
        }
}
