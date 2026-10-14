/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

/** What the user wants to receive. */
enum class ExportFormat {
    /** Bytes of the source stream, re-wrapped without re-encoding when a tag-capable container exists. */
    ORIGINAL,

    /** Decoded and re-encoded with LAME. Lossy -> lossy, so quality is bounded by the source. */
    MP3,
}

/** What to do when a file with the same name already exists in the destination. */
enum class ConflictPolicy {
    /** Never touch the existing file; pick a free name ("name (1).m4a"). */
    KEEP_BOTH,

    /** Delete the existing file first. Must only be used after an explicit user confirmation. */
    REPLACE,
}

/** Audio container actually found in the downloaded bytes. */
enum class SourceContainer {
    MP4,
    WEBM,
    UNKNOWN,
}

/** Tags written into the exported file. Every field is optional. */
data class ExportTags(
    val title: String,
    val artist: String?,
    val album: String?,
    val trackNumber: Int? = null,
    val year: Int? = null,
    /** Encoded JPEG/PNG cover. */
    val cover: Cover? = null,
) {
    class Cover(
        val data: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
    )
}

/** Where the file goes. A null [treeUri] means the public Music/Metrolist folder through MediaStore. */
data class ExportDestination(
    val treeUri: String? = null,
)

data class ExportRequest(
    val songId: String,
    val format: ExportFormat,
    val destination: ExportDestination,
    val conflictPolicy: ConflictPolicy,
)

/** Result of the preflight step: what the file will be called and whether that name is taken. */
data class ExportPlan(
    val fileName: String,
    val extension: String,
    val alreadyExists: Boolean,
)

data class ExportResult(
    val displayName: String,
    /** Human readable destination, e.g. "Music/Metrolist". */
    val location: String,
    val uri: String,
    val format: ExportFormat,
    /** Non-fatal problems, e.g. the cover could not be fetched. */
    val warnings: List<ExportWarning> = emptyList(),
)

enum class ExportWarning {
    COVER_UNAVAILABLE,
    TAGS_NOT_SUPPORTED,
    TAGS_FAILED,
}

sealed interface ExportState {
    data object Preparing : ExportState

    /** [progress] is 0..1 or null when the total size is unknown. */
    data class Downloading(val progress: Float?) : ExportState

    data class Converting(val progress: Float?) : ExportState

    data object Writing : ExportState

    data class Done(val result: ExportResult) : ExportState

    data class Failed(val error: ExportError) : ExportState

    data object Cancelled : ExportState

    val isActive: Boolean
        get() = this is Preparing || this is Downloading || this is Converting || this is Writing
}

class ExportException(
    val error: ExportError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

sealed interface ExportError {
    data object NoStream : ExportError

    data object Network : ExportError

    data object StreamExpired : ExportError

    data class InsufficientSpace(val requiredBytes: Long) : ExportError

    data object AccessDenied : ExportError

    data object WriteFailed : ExportError

    /** Fewer bytes arrived than the server promised. */
    data object Incomplete : ExportError

    data object UnsupportedSource : ExportError

    data object Mp3Unavailable : ExportError

    data object TranscodeFailed : ExportError
}
