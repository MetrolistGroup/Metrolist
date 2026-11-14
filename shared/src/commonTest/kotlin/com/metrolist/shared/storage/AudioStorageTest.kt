package com.metrolist.shared.storage

import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioStorageTest {
    @Test
    fun filenamesCannotEscapeStorageDirectory() {
        assertEquals("_.._song", safeAudioFileId("../../song"))
        assertEquals("audio", safeAudioFileId("..."))
        assertEquals(120, safeAudioFileId("a".repeat(200)).length)
    }

    @Test
    fun pathsFromAPreviousAppContainerMoveIntoTheCurrentDirectory() {
        val current = "/var/mobile/Containers/Data/Application/NEW/Library/Application Support/metrolist/downloads"
        assertEquals(
            "$current/song.m4a",
            rebasedAudioPath(
                "/var/mobile/Containers/Data/Application/OLD/Library/Application Support/metrolist/downloads/song.m4a",
                current,
            ),
        )
        assertEquals(null, rebasedAudioPath("$current/song.m4a", current))
    }

    @Test
    fun streamedChunksRespectAvailableSpaceAndReserve() {
        assertTrue(canWriteAudioChunk(0, 4, 16, 8))
        assertTrue(canWriteAudioChunk(4, 4, 16, 8))
        assertFalse(canWriteAudioChunk(5, 4, 16, 8))
        assertFalse(canWriteAudioChunk(Long.MAX_VALUE, 1, Long.MAX_VALUE, 0))
        assertFalse(canWriteAudioChunk(0, 1, 4, 8))
        assertFalse(canWriteAudioChunk(0, 0, 12, 0))
    }

    @Test
    fun audioDownloadUrlsAreLimitedToTrustedHttpsMediaEndpoints() {
        assertTrue(isTrustedAudioDownloadUrl("https://rr1.googlevideo.com/videoplayback?sig=abc"))
        assertTrue(isTrustedAudioDownloadUrl("https://music.youtube.com/videoplayback"))
        assertTrue(canFollowAudioDownloadRedirect(0, "https://rr1.googlevideo.com/videoplayback"))
        assertFalse(canFollowAudioDownloadRedirect(MAX_AUDIO_DOWNLOAD_REDIRECTS, "https://rr1.googlevideo.com/videoplayback"))
        assertFalse(canFollowAudioDownloadRedirect(0, "https://example.com/videoplayback"))
        for (url in listOf(
            "http://rr1.googlevideo.com/videoplayback",
            "https://googlevideo.com.evil.test/videoplayback",
            "https://youtube.com.evil.test/videoplayback",
            "https://rr1.googlevideo.com:444/videoplayback",
            "https://user@rr1.googlevideo.com/videoplayback",
            "https://rr1.googlevideo.com/watch",
            "https://127.0.0.1/videoplayback",
        )) {
            assertFalse(isTrustedAudioDownloadUrl(url), url)
        }
    }

    @Test
    fun playbackOnlyStoresFormatsSupportedByIos() {
        assertTrue(isIosPlayableAudioMimeType("audio/mp4; codecs=mp4a.40.2"))
        assertTrue(isIosPlayableAudioMimeType("audio/mpeg"))
        assertFalse(isIosPlayableAudioMimeType("audio/webm"))
        assertFalse(isIosPlayableAudioMimeType(null))
    }

    @Test
    fun quarantinedDownloadsStayStoredButCanBeExplicitlyReplaced() {
        val original = downloadEntry("/downloads/song.m4a")
        val quarantined = quarantineAudioDownload(original).copy()

        assertTrue(isQuarantinedDownload(quarantined))
        assertFalse(isAvailableDownload(quarantined))
        assertFalse(isAvailableCachedAudio(quarantined))
        assertFalse(canUseDownloadedAudio(quarantined, fileIsValid = true))
        assertTrue(shouldKeepDownloadInsteadOfCache(quarantined, fileIsValid = false))
        assertTrue(shouldRetainAudioFile(quarantined, fileIsValid = false))
        assertFalse(shouldRetainAudioFile(original, fileIsValid = false))
        assertTrue(shouldRetainAudioFile(original, fileIsValid = true))
        assertTrue(matchesAudioFile(quarantined, original.filePath))
        assertFalse(matchesAudioFile(quarantined, "/downloads/replacement.m4a"))
    }

    @Test
    fun completedTransfersMustMeetMinimumAndDeclaredLength() {
        assertTrue(isCompleteAudioDownload(MINIMUM_AUDIO_BYTES, MINIMUM_AUDIO_BYTES))
        assertTrue(isCompleteAudioDownload(MINIMUM_AUDIO_BYTES, null))
        assertFalse(isCompleteAudioDownload(MINIMUM_AUDIO_BYTES - 1, null))
        assertFalse(isCompleteAudioDownload(MINIMUM_AUDIO_BYTES, MINIMUM_AUDIO_BYTES + 1))
        assertFalse(isCompleteAudioDownload(MINIMUM_AUDIO_BYTES, MINIMUM_AUDIO_BYTES * 2))
    }

    @Test
    fun recoveryAndCancellationKeepTerminalQueueStates() {
        assertEquals(AudioDownloadState.QUEUED, recoverInterruptedAudioDownload(AudioDownloadState.RUNNING))
        assertEquals(AudioDownloadState.COMPLETED, recoverInterruptedAudioDownload(AudioDownloadState.COMPLETED))
        assertEquals(AudioDownloadState.CANCELLED, cancelAudioDownload(AudioDownloadState.RUNNING))
        assertEquals(AudioDownloadState.CANCELLED, cancelAudioDownload(AudioDownloadState.QUEUED))
        assertEquals(AudioDownloadState.CANCELLED, cancelAudioDownload(AudioDownloadState.PAUSED))
        assertEquals(AudioDownloadState.COMPLETED, cancelAudioDownload(AudioDownloadState.COMPLETED))
        assertTrue(shouldKeepTrackedPlaylistRequest(AudioDownloadState.PAUSED))
        assertTrue(shouldKeepTrackedPlaylistRequest(AudioDownloadState.CANCELLED))
        assertFalse(shouldKeepTrackedPlaylistRequest(AudioDownloadState.FAILED))
    }

    @Test
    fun failedFileDeletionRetainsMetadataAndAccounting() =
        runTest {
            val entry = downloadEntry("/downloads/song.m4a")
            var metadata: CachedAudioEntity? = entry
            var used = entry.fileSize
            val removed = removeAudioAfterFileDeletion(entry, { false }) { metadata = null }
            if (removed) used -= entry.fileSize
            assertFalse(removed)
            assertEquals(entry, metadata)
            assertEquals(entry.fileSize, used)
            assertTrue(removeAudioAfterFileDeletion(entry, { true }) { metadata = null })
            assertEquals(null, metadata)
        }

    private fun downloadEntry(path: String) =
        CachedAudioEntity(
            songId = "song",
            title = "title",
            artist = "artist",
            filePath = path,
            fileSize = MINIMUM_AUDIO_BYTES,
            cachedAt = 1L,
            lastAccessedAt = 1L,
            cacheLocation = "IOS_APPLICATION_SUPPORT",
            kind = KIND_DOWNLOAD,
        )

    @Test
    fun storageAccountingIgnoresNegativeSizesAndSaturates() {
        assertEquals(12, totalAudioSize(listOf(10, -5, 2)))
        assertEquals(Long.MAX_VALUE, totalAudioSize(listOf(Long.MAX_VALUE, 1)))
    }
}
