package com.metrolist.shared.playlistimport

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_default_imported_playlist_title
import com.metrolist.shared.generated.resources.import_source_text_file
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getString

enum class ImportErrorKey {
    EmptyInput,
    YouTubeDedicatedFlow,
    InvalidYouTubeUrl,
    NoTracksRecognized,
    NoTracksToImport,
    NoLibraryMatches,
    ResolutionFailed,
    IncompleteSource,
    UnsupportedPlaylistEntries,
    LocalFilesRequireLocalPlaylist,
    FileReadFailed,
    TooManyTracks,
    Generic,
}

data class PlaylistImportFallbackTitles(
    val playlist: String,
    val importedPlaylist: String,
    val album: String,
    val track: String,
)

data class ImportedPlaylistData(
    val title: String,
    val tracks: List<PlaylistImportManager.CsvTrack>,
    val description: String? = null,
    val source: String,
    val provider: ImportSourceType? = null,
    val csv: CsvPlaylist? = null,
    val sourcePlaylistCount: Int = 1,
    val sourceYouTubePlaylistId: String? = null,
    val coverUrl: String? = null,
)

sealed interface ImportProgressState {
    data object Idle : ImportProgressState

    data class FetchingMetadata(
        val source: String,
    ) : ImportProgressState

    data class LoadingTracks(
        val source: String,
        val loaded: Int,
        val total: Int?,
    ) : ImportProgressState

    data class ResolvingTracks(
        val resolved: Int,
        val total: Int,
        val currentTrackTitle: String,
        val currentTrackArtist: String,
    ) : ImportProgressState

    data class SavingToLibrary(
        val saved: Int,
        val total: Int,
    ) : ImportProgressState

    data class Cancelled(
        val saved: Int,
        val destinationName: String,
    ) : ImportProgressState

    data class Completed(
        val playlistTitle: String,
        val totalSourceTracks: Int,
        val resolvedTracks: Int,
        val importedTracks: Int,
        val destinationName: String,
        val unresolvedTracks: List<PlaylistImportManager.CsvTrack> = emptyList(),
    ) : ImportProgressState

    data class Failed(
        val message: String,
        val cause: Throwable? = null,
        val errorKey: ImportErrorKey? = null,
    ) : ImportProgressState

    data class DuplicateDetected(
        val existingPlaylistName: String,
        val existingPlaylistId: String,
        val trackCount: Int,
    ) : ImportProgressState
}

enum class ImportSourceType(
    val displayName: String,
) {
    SPOTIFY("Spotify"),
    APPLE_MUSIC("Apple Music"),
    DEEZER("Deezer"),
    YOUTUBE("YouTube Music"),
    TIDAL("Tidal"),
    AMAZON("Amazon Music"),
    FILE_OR_TEXT("File / Text"),
    UNKNOWN("Link / Text"),
}

object UnifiedPlaylistImporter {
    private val bareYouTubePlaylistId =
        Regex("""(?:(?:VL)?(?:PL|UU|LL|FL|WL|HL|RD|TL)|OLAK5uy_)[A-Za-z0-9_-]{8,}""", RegexOption.IGNORE_CASE)

    fun detectSource(input: String): ImportSourceType {
        val trimmed = input.trim().lowercase()
        return when {
            SpotifyUrlPlaylistImporter.canHandle(trimmed) -> ImportSourceType.SPOTIFY
            trimmed.contains("music.apple.com") || trimmed.startsWith("pl.") -> ImportSourceType.APPLE_MUSIC
            DeezerPlaylistImporter.canHandle(trimmed) -> ImportSourceType.DEEZER
            trimmed.contains("youtube.com") || trimmed.contains("youtu.be") -> ImportSourceType.YOUTUBE
            trimmed.contains("tidal.com") -> ImportSourceType.TIDAL
            AmazonMusicPlaylistImporter.canHandle(trimmed) -> ImportSourceType.AMAZON
            else -> ImportSourceType.UNKNOWN
        }
    }

    fun isYouTubePlaylistInput(input: String): Boolean {
        val trimmed = input.trim()
        val source = detectSource(trimmed)
        return source == ImportSourceType.YOUTUBE ||
            (source == ImportSourceType.UNKNOWN && trimmed.matches(bareYouTubePlaylistId))
    }

    suspend fun parseFromInput(
        input: String,
        fallbackTitles: PlaylistImportFallbackTitles? = null,
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): Result<ImportedPlaylistData> {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return Result.failure(ImportException(ImportErrorKey.EmptyInput))
        }

        return try {
            val source = detectSource(trimmed)
            val parsed =
                when (source) {
                    ImportSourceType.SPOTIFY -> {
                        SpotifyUrlPlaylistImporter.loadPlaylist(trimmed, fallbackTitles, onProgress)
                    }

                    ImportSourceType.APPLE_MUSIC -> {
                        AppleMusicPlaylistImporter.loadPlaylist(trimmed, fallbackTitles = fallbackTitles, onProgress = onProgress)
                    }

                    ImportSourceType.DEEZER -> {
                        DeezerPlaylistImporter.loadPlaylist(trimmed, fallbackTitles, onProgress)
                    }

                    ImportSourceType.TIDAL -> {
                        if (TidalPlaylistImporter.canHandle(trimmed)) {
                            TidalPlaylistImporter.loadPlaylist(trimmed, fallbackTitles, onProgress)
                        } else {
                            fallbackTextImport(trimmed, source, fallbackTitles)
                        }
                    }

                    ImportSourceType.AMAZON -> {
                        if (AmazonMusicPlaylistImporter.canHandle(trimmed)) {
                            AmazonMusicPlaylistImporter.loadPlaylist(trimmed, fallbackTitles, onProgress)
                        } else {
                            fallbackTextImport(trimmed, source, fallbackTitles)
                        }
                    }

                    ImportSourceType.YOUTUBE -> {
                        val playlistId = PlaylistImportManager.parsePlaylistId(trimmed)
                        if (playlistId != null) {
                            Result.failure(ImportException(ImportErrorKey.YouTubeDedicatedFlow))
                        } else {
                            Result.failure(ImportException(ImportErrorKey.InvalidYouTubeUrl))
                        }
                    }

                    else -> {
                        fallbackTextImport(trimmed, source, fallbackTitles)
                    }
                }
            parsed.map {
                it.copy(
                    provider =
                        source.takeIf { provider ->
                            provider !in setOf(ImportSourceType.UNKNOWN, ImportSourceType.YOUTUBE) &&
                                (provider != ImportSourceType.TIDAL || TidalPlaylistImporter.canHandle(trimmed)) &&
                                (provider != ImportSourceType.AMAZON || AmazonMusicPlaylistImporter.canHandle(trimmed))
                        },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fallbackTextImport(
        text: String,
        source: ImportSourceType,
        fallbackTitles: PlaylistImportFallbackTitles?,
    ): Result<ImportedPlaylistData> {
        val tracks = UniversalPlaylistParser.parse(text)
        if (tracks.isEmpty()) {
            return Result.failure(ImportException(ImportErrorKey.NoTracksRecognized))
        }
        return Result.success(
            ImportedPlaylistData(
                title = fallbackTitles?.importedPlaylist ?: getString(Res.string.import_default_imported_playlist_title),
                tracks = tracks,
                source = if (source != ImportSourceType.UNKNOWN) source.displayName else getString(Res.string.import_source_text_file),
            ),
        )
    }

    suspend fun checkForDuplicate(
        database: MusicDatabase,
        title: String,
        destination: PlaylistImportManager.Destination,
    ): ImportProgressState.DuplicateDetected? {
        if (destination is PlaylistImportManager.Destination.EXISTING) return null
        if (destination == PlaylistImportManager.Destination.Favorites) return null
        val existing = database.dao().findPlaylistByName(title.trim()) ?: return null
        val songCount = database.dao().playlistSongMaps(existing.id, 0).size
        return ImportProgressState.DuplicateDetected(
            existingPlaylistName = existing.name,
            existingPlaylistId = existing.id,
            trackCount = songCount,
        )
    }

    suspend fun executeImport(
        manager: PlaylistImportManager,
        title: String,
        tracks: List<PlaylistImportManager.CsvTrack>,
        destination: PlaylistImportManager.Destination,
        provider: ImportSourceType? = null,
        sourceYouTubePlaylistId: String? = null,
        coverUrl: String? = null,
        onStateUpdate: (ImportProgressState) -> Unit,
    ): Result<ImportProgressState.Completed> {
        return try {
            if (tracks.isEmpty()) {
                val failure =
                    ImportProgressState.Failed(
                        message = "",
                        errorKey = ImportErrorKey.NoTracksToImport,
                    )
                onStateUpdate(failure)
                return Result.failure(ImportException(ImportErrorKey.NoTracksToImport))
            }

            onStateUpdate(
                ImportProgressState.ResolvingTracks(
                    resolved = 0,
                    total = tracks.size,
                    currentTrackTitle = tracks.firstOrNull()?.title.orEmpty(),
                    currentTrackArtist = tracks.firstOrNull()?.artist.orEmpty(),
                ),
            )

            val matches =
                manager.resolveImportTracks(tracks, destination, provider) { completed, total, currentTrack ->
                    onStateUpdate(
                        ImportProgressState.ResolvingTracks(
                            resolved = completed,
                            total = total,
                            currentTrackTitle = currentTrack?.title.orEmpty(),
                            currentTrackArtist = currentTrack?.artist.orEmpty(),
                        ),
                    )
                }

            val resolvedSongs = matches.map { it.second }

            val unresolvedTracks = unresolvedTracks(tracks, matches)

            if (resolvedSongs.isEmpty()) {
                val failure =
                    ImportProgressState.Failed(
                        message = "",
                        errorKey = ImportErrorKey.NoLibraryMatches,
                    )
                onStateUpdate(failure)
                return Result.failure(ImportException(ImportErrorKey.NoLibraryMatches))
            }

            onStateUpdate(ImportProgressState.SavingToLibrary(0, resolvedSongs.size))

            val importResult =
                manager.importResolvedTracks(
                    name = title,
                    matches = matches,
                    destination = destination,
                    provider = provider,
                    sourceYouTubePlaylistId = sourceYouTubePlaylistId,
                    coverUrl = coverUrl,
                ) { imported, total, _ ->
                    onStateUpdate(ImportProgressState.SavingToLibrary(imported, total))
                }

            val finalResult = importResult.getOrThrow()
            val destinationName =
                when (destination) {
                    is PlaylistImportManager.Destination.EXISTING -> destination.playlist.name
                    PlaylistImportManager.Destination.NewLocal -> title
                    PlaylistImportManager.Destination.NewSynced -> title
                    PlaylistImportManager.Destination.Favorites -> title
                }

            val completed =
                ImportProgressState.Completed(
                    playlistTitle = title,
                    totalSourceTracks = tracks.size,
                    resolvedTracks = resolvedSongs.size,
                    importedTracks = finalResult.importedSongCount,
                    destinationName = destinationName,
                    unresolvedTracks = unresolvedTracks,
                )
            onStateUpdate(completed)
            Result.success(completed)
        } catch (e: CancellationException) {
            (e as? PlaylistImportManager.CancelledImportException)?.let {
                onStateUpdate(ImportProgressState.Cancelled(it.importedSongCount, it.playlist.name))
            }
            throw e
        } catch (e: Exception) {
            val failure =
                ImportProgressState.Failed(
                    message = "",
                    cause = e,
                    errorKey = (e as? ImportException)?.errorKey ?: ImportErrorKey.Generic,
                )
            onStateUpdate(failure)
            Result.failure(e)
        }
    }

    fun unresolvedTracks(
        tracks: List<PlaylistImportManager.CsvTrack>,
        matches: List<Pair<PlaylistImportManager.CsvTrack, SongItem>>,
    ): List<PlaylistImportManager.CsvTrack> {
        val remainingMatches = matches.groupingBy { it.first }.eachCount().toMutableMap()
        return tracks.filter { track ->
            val remaining = remainingMatches[track] ?: 0
            if (remaining > 0) remainingMatches[track] = remaining - 1
            remaining == 0
        }
    }
}

class ImportException(
    val errorKey: ImportErrorKey,
    cause: Throwable? = null,
) : Exception(errorKey.name, cause)
