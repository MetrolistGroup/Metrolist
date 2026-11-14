package com.metrolist.shared.playlist

import coil3.Uri
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.withDatabaseTransaction
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

private const val COVER_PREFIX = "playlist_covers/"
private val coverFilename = Regex("[0-9A-Fa-f]{8}-(?:[0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}\\.jpg")
private val legacyCoverDirectory =
    Regex(".*/Containers/Data/Application/[0-9A-Fa-f-]+/Library/Application Support/playlist_covers")

@OptIn(ExperimentalForeignApi::class)
internal fun iosPlaylistCoverDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/playlist_covers"

internal fun iosPlaylistCoverIdentifier(
    path: String,
    directory: String = iosPlaylistCoverDirectory(),
): String? {
    val parent = path.substringBeforeLast('/', "")
    val isManaged =
        parent == "playlist_covers" || parent == directory ||
            (path.startsWith('/') && legacyCoverDirectory.matches(parent))
    val filename = path.substringAfterLast('/')
    return if (isManaged && coverFilename.matches(filename)) {
        COVER_PREFIX + filename
    } else {
        null
    }
}

internal fun resolveIosPlaylistCover(
    path: String,
    directory: String = iosPlaylistCoverDirectory(),
): String? = iosPlaylistCoverIdentifier(path, directory)?.let { "$directory/${it.removePrefix(COVER_PREFIX)}" }

internal class IosPlaylistCoverMapper(
    private val directory: String = iosPlaylistCoverDirectory(),
) : Mapper<String, Uri> {
    override fun map(
        data: String,
        options: Options,
    ): Uri? = resolveIosPlaylistCover(data, directory)?.toUri()
}

internal suspend fun migrateIosPlaylistCovers(
    database: MusicDatabase,
    directory: String = iosPlaylistCoverDirectory(),
) {
    val dao = database.dao()
    val playlists = dao.allPlaylists().first()
    database.withDatabaseTransaction {
        for (playlist in playlists) {
            val current = dao.findPlaylistEntity(playlist.id) ?: continue
            val path = current.customThumbnailUrl ?: continue
            val identifier = iosPlaylistCoverIdentifier(path, directory) ?: continue
            if (identifier != path) dao.update(current.copy(customThumbnailUrl = identifier))
        }
    }
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal suspend fun storeIosPlaylistCover(
    bytes: ByteArray?,
    previousPath: String?,
    directory: String = iosPlaylistCoverDirectory(),
    save: suspend (String?) -> Unit,
) {
    withContext(NonCancellable + Dispatchers.IO) {
        val files = NSFileManager.defaultManager
        var created: String? = null
        try {
            if (bytes != null) {
                require(bytes.isNotEmpty())
                check(files.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null))
                val path = "$directory/${NSUUID().UUIDString}.jpg"
                created = path
                check(bytes.usePinned { NSData.create(it.addressOf(0), bytes.size.toULong()).writeToFile(path, atomically = true) })
            }
            save(created?.let { checkNotNull(iosPlaylistCoverIdentifier(it, directory)) })
            created = null
            previousPath?.let { resolveIosPlaylistCover(it, directory) }?.let {
                files.removeItemAtPath(it, error = null)
            }
        } finally {
            created?.let { files.removeItemAtPath(it, error = null) }
        }
    }
}
